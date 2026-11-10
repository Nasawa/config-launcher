package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import java.io.IOException

/**
 * AQ-4 verified write of a root file (launcher.json, AGENTS.md) on a SAF tree, which has no atomic replace:
 * stage a temp copy, verify it, truncate-write the target, verify, restore from the temp on failure.
 *
 * Staging (#251, Grace): normally `.configlauncher/<name>.tmp`. When `.configlauncher` can't be used (suppressed by
 * [SafDirs] because the provider hides it, or otherwise failing), the temp is `.<name>.tmp` next to the target in the
 * tree root, made through the same [SafDirs] find-or-create (collision and rename checks), and deleted after the
 * swap, success or not. A root temp left by a killed process is deleted by the next successful save of that file
 * and by [sweepRootTemps] on start. Only those exact names ([rootTemp] of the files written here) are ever deleted.
 */
internal class SafWrites(private val tree: DocTree, private val dirs: SafDirs) {

    fun writeVerified(rootDocId: String, name: String, mime: String, bytes: ByteArray, what: String) = synchronized(lock) {
        val want = ConfigCodec.sha256(bytes)
        val sidecarTmp = try {
            dirs.ensure(dirs.ensure(rootDocId, ".configlauncher", Document.MIME_TYPE_DIR), "$name.tmp", BIN)
        } catch (e: AccessLostException) { throw e } catch (_: IOException) { null }
        val tmp = sidecarTmp ?: dirs.ensure(rootDocId, rootTemp(name), BIN)
        try {
            tree.write(tmp, bytes)
            if (tree.read(tmp)?.let { ConfigCodec.sha256(it) } != want) throw IOException("temp copy verification failed")
            val target = dirs.ensure(rootDocId, name, mime)
            try {
                tree.write(target, bytes)
                if (tree.read(target)?.let { ConfigCodec.sha256(it) } != want) throw IOException("write verification failed")
            } catch (e: Exception) {
                runCatching { tree.write(target, tree.read(tmp)!!) }
                throw IOException("Couldn't save $what: ${e.message}", e)
            }
        } finally {
            if (sidecarTmp == null) runCatching { tree.delete(tmp) }
        }
        // Saved through `.configlauncher`: a root temp from an earlier suppressed or killed run is no longer needed.
        if (sidecarTmp != null) runCatching { sweep(rootDocId, name) }
    }

    /** Start: delete leftover root temps of the files this class writes. Never anything else. */
    fun sweepRootTemps(rootDocId: String) = synchronized(lock) { ROOT_FILES.forEach { runCatching { sweep(rootDocId, it) } } }

    private fun sweep(rootDocId: String, name: String) {
        tree.list(rootDocId)[rootTemp(name)]?.let { if (it.mime != Document.MIME_TYPE_DIR) tree.delete(it.docId) }
    }

    companion object {
        private const val BIN = "application/octet-stream"
        /** The root files that go through [writeVerified] (launcher.json, AGENTS.md). */
        val ROOT_FILES = listOf(SafSource.FILE, AgentDocs.NAME)
        fun rootTemp(name: String) = ".$name.tmp"
        /** One save or sweep at a time per process, so a start-up sweep never deletes a temp a save is using. */
        private val lock = Any()
    }
}
