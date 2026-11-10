package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import java.io.File
import java.io.IOException

/**
 * #251: document ids of root save temps that may still exist, per tree, in app-private storage. A save adds its
 * temp right after creating it and removes it once deleted; [SafWrites.sweepRootTemps] deletes what's left on start.
 * That's how a temp the provider hides (MediaStore skips dot names) is still found after a killed save.
 */
interface TempJournal {
    fun add(treeKey: String, docId: String)
    fun remove(treeKey: String, docId: String)
    fun list(treeKey: String): List<String>

    companion object {
        val NONE = object : TempJournal {
            override fun add(treeKey: String, docId: String) {}
            override fun remove(treeKey: String, docId: String) {}
            override fun list(treeKey: String) = emptyList<String>()
        }
    }
}

/** [TempJournal] as `tree<TAB>docId` lines in one app-private file, replaced via a temp file and rename. */
class FileTempJournal(private val file: File) : TempJournal {
    private fun read(): List<Pair<String, String>> = synchronized(this) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    }
    private fun save(entries: List<Pair<String, String>>) {
        file.parentFile?.mkdirs()
        val t = File(file.path + ".tmp")
        t.writeText(entries.joinToString("") { "${it.first}\t${it.second}\n" })
        if (!t.renameTo(file)) { file.delete(); t.renameTo(file) }
    }
    override fun add(treeKey: String, docId: String) = synchronized(this) { save(read().filterNot { it == treeKey to docId } + (treeKey to docId)) }
    override fun remove(treeKey: String, docId: String) = synchronized(this) { save(read().filterNot { it == treeKey to docId }) }
    override fun list(treeKey: String) = read().filter { it.first == treeKey }.map { it.second }
}

/**
 * AQ-4 verified write of a root file (launcher.json, AGENTS.md) on a SAF tree, which has no atomic replace:
 * stage a temp copy, verify it, truncate-write the target, verify, and on failure put the target's original bytes back.
 *
 * Staging (#251, Grace):
 * - Normally `.configlauncher/<name>.tmp` (found or created through [SafDirs]).
 * - When `.configlauncher` can't be used (suppressed because the provider hides it, or otherwise failing), a root
 *   temp `.<name>.<token>.tmp` next to the target. The token is new per save, so a hidden leftover of a killed save
 *   never collides. It's made with [SafDirs.createScratch]: any name the provider returns is accepted, the returned
 *   document is used, and it's deleted by that document id in `finally`, never looked up by name.
 * - Each root temp's id is written to a [TempJournal] before any bytes go in, so one left by a killed save is
 *   deleted on the next start ([sweepRootTemps]) even when the provider's listing hides it. A process killed between
 *   the create and the journal write can orphan one empty hidden file; that's accepted.
 */
internal class SafWrites(
    private val tree: DocTree,
    private val dirs: SafDirs,
    private val treeKey: String,
    private val journal: TempJournal = TempJournal.NONE,
    private val token: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
) {

    fun writeVerified(rootDocId: String, name: String, mime: String, bytes: ByteArray, what: String) = synchronized(lock) {
        val want = ConfigCodec.sha256(bytes)
        val sidecarTmp = try {
            dirs.ensure(dirs.ensure(rootDocId, ".configlauncher", Document.MIME_TYPE_DIR), "$name.tmp", BIN)
        } catch (e: AccessLostException) { throw e } catch (_: IOException) { null }
        val tmp = sidecarTmp ?: dirs.createScratch(rootDocId, BIN, ".$name.${token()}.tmp").also { journal.add(treeKey, it) }
        var keepTmp = false
        try {
            tree.write(tmp, bytes)
            if (tree.read(tmp)?.let { ConfigCodec.sha256(it) } != want) throw IOException("temp copy verification failed")
            val target = dirs.ensure(rootDocId, name, mime)
            // The target's current bytes, kept in memory: the restore puts these back (not the temp's new bytes).
            val original = tree.read(target)
            try {
                tree.write(target, bytes)
                if (tree.read(target)?.let { ConfigCodec.sha256(it) } != want) throw IOException("write verification failed")
            } catch (e: Exception) {
                val restored = original != null &&
                    runCatching { tree.write(target, original); tree.read(target)?.contentEquals(original) == true }.getOrDefault(false)
                // Keep or delete the root temp:
                // - target restored to its original bytes: the file is as it was and the edit is still pending in
                //   memory for Retry, so the temp isn't needed and is deleted;
                // - target write and restore both failed (or there was no original): the target may be truncated or
                //   partial and the temp is the only durable copy of the new version, so it's kept, dropped from the
                //   journal (so no start sweep deletes it), and named in the error.
                // A `.configlauncher` temp is never deleted (a fixed name, overwritten by the next save).
                if (!restored && sidecarTmp == null) {
                    keepTmp = true
                    val kept = runCatching { tree.displayName(tmp) }.getOrNull() ?: ".$name.*.tmp"
                    throw IOException("Couldn't save $what: ${e.message}; the new version is kept in $kept", e)
                }
                throw IOException("Couldn't save $what: ${e.message}" + if (restored) "" else "; restoring the old version also failed", e)
            }
        } finally {
            if (sidecarTmp == null) {
                if (keepTmp) journal.remove(treeKey, tmp)
                else if (runCatching { tree.delete(tmp) }.getOrDefault(false)) journal.remove(treeKey, tmp)
                // A failed delete stays in the journal for the next start's sweep.
            }
        }
        // Saved through `.configlauncher`: an exact-name root temp from a 7803222 build is no longer needed.
        if (sidecarTmp != null) runCatching { sweepLegacy(rootDocId, name) }
    }

    /**
     * Start: delete root temps left by killed saves. Journaled ids are checked on the document itself (still there,
     * not a folder, a dot name ending in `.tmp`) before deletion; the exact legacy names [legacyTemp] are found by
     * listing. Never a folder, never anything else.
     */
    fun sweepRootTemps(rootDocId: String) = synchronized(lock) {
        for (id in journal.list(treeKey)) runCatching {
            val info = tree.info(id)
            val ours = info != null && info.mime != Document.MIME_TYPE_DIR &&
                tree.displayName(id)?.let { it.startsWith(".") && it.endsWith(".tmp") } == true
            if (info == null || !ours || tree.delete(id)) journal.remove(treeKey, id)
        }
        ROOT_FILES.forEach { runCatching { sweepLegacy(rootDocId, it) } }
    }

    private fun sweepLegacy(rootDocId: String, name: String) {
        val c = tree.list(rootDocId)[legacyTemp(name)] ?: return
        if ((tree.info(c.docId)?.mime ?: c.mime) != Document.MIME_TYPE_DIR) tree.delete(c.docId)
    }

    companion object {
        private const val BIN = "application/octet-stream"
        /** The root files that go through [writeVerified] (launcher.json, AGENTS.md). */
        val ROOT_FILES = listOf(SafSource.FILE, AgentDocs.NAME)
        /** The fixed root temp name of 7803222 builds (`.launcher.json.tmp`), swept where a listing shows it. */
        fun legacyTemp(name: String) = ".$name.tmp"
        /** One save or sweep at a time per process, so a start-up sweep never deletes a temp a save is using. */
        private val lock = Any()
    }
}
