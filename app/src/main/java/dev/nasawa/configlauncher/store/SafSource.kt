package dev.nasawa.configlauncher.store

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import java.io.IOException

/**
 * launcher.json inside a user-picked SAF tree (persisted READ|WRITE grant, no storage permission).
 * SAF has no atomic replace, so writes follow AQ-4 ([SafWrites]): write `.configlauncher/launcher.json.tmp`
 * (or `.launcher.json.tmp` in the root while `.configlauncher` is unusable, #251), verify, truncate-write
 * launcher.json ("wt"), verify, restore from temp on failure.
 */
class SafSource(private val resolver: ContentResolver, val treeUri: Uri, private val journal: TempJournal = TempJournal.NONE) : ConfigSource {
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    override val displayName = "Folder: " + rootDocId.substringAfterLast(':').ifEmpty { rootDocId }
    override val location = FolderPath.launcherJson(treeUri.authority, rootDocId, treeUri.toString())

    private fun children(parentDocId: String): Map<String, DocChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val out = mutableMapOf<String, DocChild>()
        if (resolver.persistedUriPermissions.none { it.uri == treeUri && it.isReadPermission }) throw AccessLostException("folder permission was revoked")
        try { resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = DocChild(c.getString(0), c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        } ?: throw AccessLostException("folder is not accessible")
        } catch (e: SecurityException) { throw AccessLostException("folder permission was revoked") }
        catch (e: java.io.FileNotFoundException) { throw AccessLostException("folder no longer exists") }
        catch (e: IllegalArgumentException) { throw AccessLostException("folder no longer exists") }
        return out
    }

    private fun docUri(docId: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** URI that a ContentObserver can watch as a hint (providers may never notify; polling is the real mechanism). */
    val childrenUri: Uri get() = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)

    override fun stamp(): Stamp? = children(rootDocId)[FILE]?.let { Stamp(it.docId, it.modified, it.size) }

    override fun read(maxBytes: Int): ByteArray? {
        val child = children(rootDocId)[FILE] ?: return null
        if (child.size > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
        return resolver.openInputStream(docUri(child.docId))?.use { it.readCapped(maxBytes) }
    }

    private fun readUri(uri: Uri) = resolver.openInputStream(uri)?.use { it.readCapped(ConfigCodec.MAX_BYTES) }

    private fun writeUri(uri: Uri, bytes: ByteArray) {
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("can't open $uri")).use { it.write(bytes); it.flush() }
    }

    private val docs = object : DocTree {
        override fun list(parentDocId: String) = children(parentDocId)
        override fun create(parentDocId: String, mime: String, name: String) =
            DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)?.let { DocumentsContract.getDocumentId(it) }
        override fun displayName(docId: String): String? =
            resolver.query(docUri(docId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        override fun delete(docId: String) = DocumentsContract.deleteDocument(resolver, docUri(docId))
        override fun read(docId: String) = readUri(docUri(docId))
        override fun write(docId: String, bytes: ByteArray) = writeUri(docUri(docId), bytes)
        override fun info(docId: String): DocInfo? = try {
            resolver.query(docUri(docId), arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) DocInfo(c.getString(0) ?: "", if (c.isNull(1)) 0 else c.getLong(1)) else null
            }
        } catch (_: java.io.FileNotFoundException) { null } catch (_: IllegalArgumentException) { null }
        override fun childCount(docId: String): Int =
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.count } ?: throw IOException("can't list $docId")
    }
    private val dirs = SafDirs(docs, treeUri.toString())
    private val writes = SafWrites(docs, dirs, treeUri.toString(), journal)

    /** #251: start-up sweep of root save temps left by a killed save (by journaled document id; [SafWrites]). */
    fun sweepRootTemps() = writes.sweepRootTemps(rootDocId)

    /** #244: find-or-create through [SafDirs] (serialized, rename-checked), never a bare createDocument. */
    private fun ensure(parentDocId: String, name: String, mime: String): String = dirs.ensure(parentDocId, name, mime)

    override fun suppressedNames() = dirs.suppressed()
    override fun takeNewlySuppressed() = dirs.takeNew()
    override fun clearSuppression() = SafDirs.clearTree(treeUri.toString())

    override fun write(bytes: ByteArray) = writeVerified(FILE, "application/json", bytes, "layout")

    override fun readRoot(name: String, maxBytes: Int): ByteArray? {
        val child = children(rootDocId)[name] ?: return null
        if (child.size > maxBytes) throw IOException("$name is larger than ${maxBytes / 1024} KiB")
        return resolver.openInputStream(docUri(child.docId))?.use { it.readCapped(maxBytes) }
    }

    override fun writeRoot(name: String, bytes: ByteArray) = writeVerified(name, "application/octet-stream", bytes, name)

    // Some providers append ".json" for application/json; octet-stream keeps the exact temp names.
    private fun writeVerified(name: String, mime: String, bytes: ByteArray, what: String) =
        writes.writeVerified(rootDocId, name, mime, bytes, what)

    override fun siblingNames(): List<String> = children(rootDocId).keys.toList()

    override fun sidecarStamp(subdir: String, name: String): Stamp? {
        var dir = children(rootDocId)[".configlauncher"]?.docId ?: return null
        if (subdir.isNotEmpty()) dir = children(dir)[subdir]?.docId ?: return null
        return children(dir)[name]?.let { Stamp(it.docId, it.modified, it.size) }
    }

    override fun readSidecar(subdir: String, name: String, maxBytes: Int): ByteArray? {
        var dir = children(rootDocId)[".configlauncher"]?.docId ?: return null
        if (subdir.isNotEmpty()) dir = children(dir)[subdir]?.docId ?: return null
        val child = children(dir)[name] ?: return null
        if (child.size > maxBytes) throw IOException("$name is larger than ${maxBytes / 1024} KiB")
        return resolver.openInputStream(docUri(child.docId))?.use { it.readCapped(maxBytes) }
    }

    override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
        var dir = ensure(rootDocId, ".configlauncher", Document.MIME_TYPE_DIR)
        if (subdir.isNotEmpty()) dir = ensure(dir, subdir, Document.MIME_TYPE_DIR)
        writeUri(docUri(ensure(dir, name, "application/octet-stream")), bytes)
    }

    companion object { const val FILE = "launcher.json" }
}
