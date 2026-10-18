package dev.nasawa.configlauncher.store

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import java.io.IOException

/**
 * launcher.json inside a user-picked SAF tree (persisted READ|WRITE grant, no storage permission).
 * SAF has no atomic replace, so writes follow AQ-4: write `.configlauncher/launcher.json.tmp`,
 * verify, truncate-write launcher.json ("wt"), verify, restore from temp on failure.
 */
class SafSource(private val resolver: ContentResolver, val treeUri: Uri) : ConfigSource {
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    override val displayName = "Folder: " + rootDocId.substringAfterLast(':').ifEmpty { rootDocId }

    private data class Child(val docId: String, val mime: String, val modified: Long, val size: Long)

    private fun children(parentDocId: String): Map<String, Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
        val out = mutableMapOf<String, Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = Child(c.getString(0), c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
            }
        } ?: throw IOException("folder is not accessible (reconnect it in settings)")
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

    private fun ensure(parentDocId: String, name: String, mime: String): String =
        children(parentDocId)[name]?.docId
            ?: DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)
                ?.let { DocumentsContract.getDocumentId(it) }
            ?: throw IOException("can't create $name")

    override fun write(bytes: ByteArray) {
        val want = ConfigCodec.sha256(bytes)
        val dir = ensure(rootDocId, ".configlauncher", Document.MIME_TYPE_DIR)
        // Some providers append ".json" for application/json; octet-stream keeps the exact name.
        val tmp = docUri(ensure(dir, "$FILE.tmp", "application/octet-stream"))
        writeUri(tmp, bytes)
        if (readUri(tmp)?.let { ConfigCodec.sha256(it) } != want) throw IOException("temp copy verification failed")
        val target = docUri(children(rootDocId)[FILE]?.docId
            ?: DocumentsContract.createDocument(resolver, docUri(rootDocId), "application/json", FILE)?.let { DocumentsContract.getDocumentId(it) }
            ?: throw IOException("can't create $FILE"))
        try {
            writeUri(target, bytes)
            if (readUri(target)?.let { ConfigCodec.sha256(it) } != want) throw IOException("write verification failed")
        } catch (e: Exception) {
            runCatching { writeUri(target, readUri(tmp)!!) }
            throw IOException("Couldn't save layout: ${e.message}", e)
        }
    }

    companion object { const val FILE = "launcher.json" }
}
