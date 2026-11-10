package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import java.io.File
import java.io.IOException

/**
 * #251: document ids of root save temps that may still exist, per tree, in app-private storage. A save adds its
 * temp (with the SHA-256 of the bytes it's about to hold) right after creating it and removes it once deleted;
 * [SafWrites.sweepRootTemps] deals with what's left on start. That's how a temp the provider hides (MediaStore skips
 * dot names) is still found after a killed save, and how the sweep knows the temp is a complete copy.
 */
interface TempJournal {
    fun add(treeKey: String, docId: String, sha256: String? = null)
    fun remove(treeKey: String, docId: String)
    fun list(treeKey: String): List<String>
    /** The SHA-256 recorded with [docId], or null (none recorded, e.g. a journal from an earlier #251 build). */
    fun sha256(treeKey: String, docId: String): String? = null
    /**
     * #262: one more start whose sweep couldn't settle [docId] (a transient error); returns the count so far.
     * [SafWrites.sweepRootTemps] drops the entry (never the file) after [SafWrites.MAX_STRIKES] in a row.
     */
    fun strike(treeKey: String, docId: String): Int = 0
    /** #262: the sweep settled [docId] this start (kept on purpose as the only copy): its strike count restarts. */
    fun clearStrikes(treeKey: String, docId: String) {}

    companion object {
        val NONE = object : TempJournal {
            override fun add(treeKey: String, docId: String, sha256: String?) {}
            override fun remove(treeKey: String, docId: String) {}
            override fun list(treeKey: String) = emptyList<String>()
        }
    }
}

/**
 * [TempJournal] as `tree<TAB>docId[<TAB>sha256[<TAB>strikes]]` lines in one app-private file, replaced via a temp
 * file and rename. Two- and three-field lines (earlier #251 builds) still read, with no hash / no strikes.
 */
class FileTempJournal(private val file: File) : TempJournal {
    private class E(val tree: String, val id: String, val sha: String?, val strikes: Int = 0)
    private fun read(): List<E> = synchronized(this) {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { l ->
            l.split('\t').takeIf { it.size in 2..4 }?.let { E(it[0], it[1], it.getOrNull(2)?.ifEmpty { null }, it.getOrNull(3)?.toIntOrNull() ?: 0) }
        }
    }
    private fun save(entries: List<E>) {
        file.parentFile?.mkdirs()
        val t = File(file.path + ".tmp")
        t.writeText(entries.joinToString("") { e ->
            "${e.tree}\t${e.id}" + when { e.strikes > 0 -> "\t${e.sha.orEmpty()}\t${e.strikes}"; e.sha != null -> "\t${e.sha}"; else -> "" } + "\n"
        })
        if (!t.renameTo(file)) { file.delete(); t.renameTo(file) }
    }
    override fun add(treeKey: String, docId: String, sha256: String?) = synchronized(this) {
        save(read().filterNot { it.tree == treeKey && it.id == docId } + E(treeKey, docId, sha256))
    }
    private fun setStrikes(treeKey: String, docId: String, f: (Int) -> Int): Int = synchronized(this) {
        var n = 0
        val all = read()
        if (all.none { it.tree == treeKey && it.id == docId }) return 0
        save(all.map { e -> if (e.tree == treeKey && e.id == docId) E(e.tree, e.id, e.sha, f(e.strikes).also { n = it }) else e })
        n
    }
    override fun strike(treeKey: String, docId: String) = setStrikes(treeKey, docId) { it + 1 }
    override fun clearStrikes(treeKey: String, docId: String) {
        if (read().any { it.tree == treeKey && it.id == docId && it.strikes > 0 }) setStrikes(treeKey, docId) { 0 }
    }
    override fun remove(treeKey: String, docId: String) = synchronized(this) { save(read().filterNot { it.tree == treeKey && it.id == docId }) }
    override fun list(treeKey: String) = read().filter { it.tree == treeKey }.map { it.id }
    override fun sha256(treeKey: String, docId: String) = read().firstOrNull { it.tree == treeKey && it.id == docId }?.sha
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
 * - Each root temp's id (and the hash of the bytes it will hold) is written to a [TempJournal] before any bytes go
 *   in, inside the try/finally that deletes the temp, so one left by a killed save is found on the next start
 *   ([sweepRootTemps]) even when the provider's listing hides it. A process killed between the create and the
 *   journal write can orphan one empty hidden file; that's accepted.
 *
 * Write order: the temp is written and verified in full before the target is touched. The target itself can't be
 * replaced atomically (no rename-over on SAF), so a process killed during the target's truncate-write leaves it
 * empty or a prefix of the new bytes. The temp is then the only full copy, and the next start's sweep restores the
 * target from it before deleting it (Grace, #251 NO-GO at 3fc9b0e).
 */
internal class SafWrites(
    private val tree: DocTree,
    private val dirs: SafDirs,
    private val treeKey: String,
    private val journal: TempJournal = TempJournal.NONE,
    private val token: () -> String = { java.util.UUID.randomUUID().toString().take(8) }, // 8 lowercase hex,
) {

    fun writeVerified(rootDocId: String, name: String, mime: String, bytes: ByteArray, what: String) = synchronized(lock) {
        val want = ConfigCodec.sha256(bytes)
        val sidecarTmp = try {
            dirs.ensure(dirs.ensure(rootDocId, ".configlauncher", Document.MIME_TYPE_DIR), "$name.tmp", BIN)
        } catch (e: AccessLostException) { throw e } catch (_: IOException) { null }
        val tmp = sidecarTmp ?: dirs.createScratch(rootDocId, BIN, ".$name.${token()}.tmp")
        var keepTmp = false
        try {
            // Journaled inside the try, so a journal that can't be written still gets the temp deleted (finally).
            if (sidecarTmp == null) try { journal.add(treeKey, tmp, want) } catch (e: Exception) {
                throw IOException("Couldn't save $what: can't record the temporary copy (${e.message})", e)
            }
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
                    val keptName = runCatching { tree.displayName(tmp) }.getOrNull() ?: ".$name.*.tmp"
                    throw IOException("Couldn't save $what: ${e.message}; the new version is kept in $keptName", e)
                }
                throw IOException("Couldn't save $what: ${e.message}" + if (restored) "" else "; restoring the old version also failed", e)
            }
        } finally {
            if (sidecarTmp == null) {
                if (keepTmp) runCatching { journal.remove(treeKey, tmp) }
                else if (runCatching { tree.delete(tmp) }.getOrDefault(false)) runCatching { journal.remove(treeKey, tmp) }
                // A failed delete stays in the journal for the next start's sweep.
            }
        }
        // Saved through `.configlauncher`: an exact-name root temp from a 7803222 build is no longer needed.
        if (sidecarTmp != null) runCatching { sweepLegacy(rootDocId, name) }
    }

    /**
     * Start: deal with root temps left by killed saves, by journaled document id (the listing may hide them).
     * For each journaled id:
     * - not found (null info, no name, or the provider's not-found / not-in-this-tree error from any lookup, read or
     *   delete; #262: MediaStore can return a row for a deleted file, then throw FileNotFoundException on read):
     *   dropped from the journal;
     * - a folder, or not exactly `.launcher.json.<8 hex>.tmp` / `.AGENTS.md.<8 hex>.tmp` (a provider's ` (N)` before
     *   `.tmp` allowed): dropped from the journal and never deleted;
     * - the save was killed while writing the target (the target is empty or a prefix of the temp) and the temp is a
     *   complete copy (its recorded hash matches; without one, launcher.json must parse and AGENTS.md be non-empty):
     *   the target is restored from the temp, then the temp is deleted. If the restore fails, the temp is kept (still
     *   journaled, tried again next start) and a Warnings / status.json line names it ([tempWarnings]);
     * - launcher.json was replaced by something that doesn't parse and isn't ours: it's not overwritten; the temp
     *   is kept with a warning, and deleted on a later start once launcher.json is fixed;
     * - otherwise the save finished or never reached the target: the temp is deleted.
     * - any other error (or a delete the provider refuses): kept for the next start. #262: after [MAX_STRIKES] such
     *   starts in a row the entry is dropped from the journal (the file is left alone), so the journal can't grow forever.
     * Losing access stops the sweep with every entry kept, and costs no strike.
     * Exact legacy names [legacyTemp] are found by listing. Never a folder, never anything else.
     */
    fun sweepRootTemps(rootDocId: String): List<String> = synchronized(lock) {
        val warnings = mutableListOf<String>()
        for (id in journal.list(treeKey)) {
            try { sweepOne(rootDocId, id)?.let { warnings += it; runCatching { journal.clearStrikes(treeKey, id) } } }
            catch (_: AccessLostException) { break } // no access: every entry is kept for a later start
            catch (_: Exception) {
                // Transient: kept for the next start, until it has failed MAX_STRIKES starts in a row.
                runCatching { if (journal.strike(treeKey, id) >= MAX_STRIKES) journal.remove(treeKey, id) }
            }
        }
        ROOT_FILES.forEach { runCatching { sweepLegacy(rootDocId, it) } }
        kept[treeKey] = warnings.toList()
        warnings
    }

    /** #251: Warnings / status.json lines from the last sweep of this tree (temps kept as the only copy). */
    fun tempWarnings(): List<String> = kept[treeKey].orEmpty()

    /** [block], or null when the provider says the document is gone ([notFound]); access loss and other errors throw. */
    private fun <T> orGone(block: () -> T?): T? = try { block() } catch (e: AccessLostException) { throw e } catch (e: Exception) {
        if (notFound(e)) null else throw e
    }

    private fun sweepOne(rootDocId: String, id: String): String? {
        val info = orGone { tree.info(id) }
        val tmpName = info?.takeIf { it.mime != Document.MIME_TYPE_DIR }?.let { orGone { tree.displayName(id) } }
        val target = tmpName?.let(::tempTarget)
        val temp = target?.let { orGone { tree.read(id) } }
        if (temp == null) { journal.remove(treeKey, id); return null } // gone, or not ours: never deleted
        val complete = journal.sha256(treeKey, id)?.let { ConfigCodec.sha256(temp) == it } ?: looksComplete(target, temp)
        val doc = tree.list(rootDocId)[target]?.takeIf { it.mime != Document.MIME_TYPE_DIR }
        val current = doc?.let { tree.read(it.docId) }
        if (complete && doc != null && current != null && !current.contentEquals(temp)) {
            if (current.isEmpty() || temp.startsWith(current)) {
                // Killed mid target write: the temp is the only full copy of the new version.
                val restored = runCatching { tree.write(doc.docId, temp); tree.read(doc.docId)?.contentEquals(temp) == true }.getOrDefault(false)
                if (!restored) return "Couldn't finish saving $target after the app was stopped: the new version is kept in $tmpName. Copy it over $target to keep your last edit."
            } else if (target == SafSource.FILE && ConfigCodec.decode(current) is ParseResult.Invalid) {
                // Someone else's launcher.json since then: not overwritten, and the edit isn't thrown away either.
                return "$target has problems, and the app was stopped while saving it: your last edit on the phone is kept in $tmpName."
            }
        }
        // Gone by now (#262: a not-found from the delete) counts as deleted; a refused delete is retried next start.
        val deleted = try { tree.delete(id) } catch (e: AccessLostException) { throw e } catch (e: Exception) { if (notFound(e)) true else throw e }
        if (!deleted) throw IOException("couldn't delete $tmpName")
        journal.remove(treeKey, id)
        return null
    }

    private fun sweepLegacy(rootDocId: String, name: String) {
        val c = tree.list(rootDocId)[legacyTemp(name)] ?: return
        if ((tree.info(c.docId)?.mime ?: c.mime) != Document.MIME_TYPE_DIR) tree.delete(c.docId)
    }

    companion object {
        private const val BIN = "application/octet-stream"
        /** #262: starts in a row a journaled temp may fail to settle before its entry (never the file) is dropped. */
        const val MAX_STRIKES = 5
        /** The root files that go through [writeVerified] (launcher.json, AGENTS.md). */
        val ROOT_FILES = listOf(SafSource.FILE, AgentDocs.NAME)
        /** The fixed root temp name of 7803222 builds (`.launcher.json.tmp`), swept where a listing shows it. */
        fun legacyTemp(name: String) = ".$name.tmp"
        /** #251: exactly the root temp names [writeVerified] makes (token: 8 lowercase hex), maybe renamed ` (N)`. */
        private val TEMP_NAME = Regex("""\.(launcher\.json|AGENTS\.md)\.[0-9a-f]{8}(?: \(\d+\))?\.tmp""")
        /** The root file a temp named [displayName] belongs to, or null when it isn't one of ours. */
        fun tempTarget(displayName: String): String? = TEMP_NAME.matchEntire(displayName)?.groupValues?.get(1)
        /** Without a recorded hash: launcher.json must load, AGENTS.md must be non-empty. */
        private fun looksComplete(target: String, temp: ByteArray) =
            if (target == SafSource.FILE) ConfigCodec.decode(temp) is ParseResult.Ok else temp.isNotEmpty()
        /** A provider saying the document is gone (deleted, or "not a descendant" of the tree any more). */
        private fun notFound(e: Exception) =
            e is java.io.FileNotFoundException || e is SecurityException || e is IllegalArgumentException
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
        /** Per tree, the last sweep's [tempWarnings] (SafSource instances are short-lived). */
        private val kept = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        /** One save or sweep at a time per process, so a start-up sweep never deletes a temp a save is using. */
        private val lock = Any()
    }
}
