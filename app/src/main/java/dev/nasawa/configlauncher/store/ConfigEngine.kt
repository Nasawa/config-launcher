package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Limits
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException

enum class Origin { FILE, LAST_GOOD, DEFAULT }

/** Typed problems so the UI shows the right banner (docs/ux.md §6.1, ARCHITECTURE.md §17 AQ-8). */
sealed interface Issue {
    val details: List<String>
    /** B1: unreadable (syntax, encoding, duplicate key, too deep, over the byte cap). */
    data class ParseError(override val details: List<String>) : Issue
    /** B2: parsed but failed validation. */
    data class Invalid(override val details: List<String>) : Issue
    /** B3: folder grant missing or the tree itself is unreachable. Action: Reconnect only. */
    data class AccessLost(val message: String) : Issue { override val details get() = listOf(message) }
    /** B4: folder fine, launcher.json missing. Actions: Create from current layout, Reconnect. Never auto-created. */
    data object Missing : Issue { override val details get() = emptyList<String>() }
    /** B5: our write failed (or would exceed the cap); the edit stays in memory. [title] is the banner text; [message] the technical detail. */
    data class SaveFailed(val message: String, val title: Msg = Msg(dev.nasawa.configlauncher.R.string.b5_save_failed)) : Issue {
        override val details get() = listOf(message)
        companion object {
            fun of(e: Exception, title: Int = dev.nasawa.configlauncher.R.string.b5_save_failed) = SaveFailed(e.reason(),
                if (e is TooLargeException) Msg(dev.nasawa.configlauncher.R.string.b5_too_large, e.limitMb) else Msg(title))
        }
    }
    /** B7: schemaVersion newer than supported. */
    data class TooNew(val message: String) : Issue { override val details get() = listOf(message) }
    /** Transient read failure (provider unavailable); shown as B1-style "can't read". */
    data class ReadFailed(val message: String) : Issue { override val details get() = listOf(message) }
}

/** Thrown by sources when the grant/tree is gone (B3) rather than the file (B4). */
class AccessLostException(message: String) : IOException(message)

data class LoadState(
    val config: LauncherConfig,
    val origin: Origin,
    val sourceName: String,
    /** Hash of the file version [config] was derived from (the write-back base hash). */
    val baseHash: String? = null,
    val issue: Issue? = null,
    /** Local edits not yet written (optimistic; already shown). */
    val pendingEdits: Int = 0,
    /** launcher.json changed under pending edits (banner B6: Use file / Keep mine). */
    val conflict: Boolean = false,
    /** One-shot snackbar text, cleared by [ConfigEngine.consumeNotice]. */
    val notice: Msg? = null,
    /** Whether the source has a folder grant that "Reconnect" can re-pick (false for app storage). */
    val reconnectable: Boolean = true,
    /** §18.2: an automatic merge just happened; the snackbar offers Undo ([ConfigEngine.undoMerge]). */
    val mergeUndo: Boolean = false,
    /** ux.md §9.1 merge snackbar (formatted by the UI from strings.xml); cleared by [ConfigEngine.consumeMerge]. */
    val merge: MergeNotice? = null,
) {
    /** Problem lines for the detail view (kept for callers/tests that only need text). */
    val errors: List<String> get() = when (issue) { null, Issue.Missing -> emptyList(); else -> issue.details }
}

/**
 * Inbound binding (ARCHITECTURE.md §4): poll → stamp change (or every 5th poll / resume: hash anyway,
 * §17 Q5) → capped read → hash guard → 300 ms stability re-check → parse/migrate/validate →
 * atomic swap into [state] → last-good. Pure Kotlin; Android wiring lives in ConfigStore.
 */
private val CANCELLED = Msg(dev.nasawa.configlauncher.R.string.undo_cancelled)

class ConfigEngine(
    @Volatile var source: ConfigSource,
    private val lastGood: LastGoodStore,
    private val stabilityMs: Long = 300,
    private val scope: CoroutineScope? = null,
    private val debounceMs: Long = 750,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableListOf<Edit>()
    private var flushJob: Job? = null
    /** Hash of the file bytes that last failed to parse/validate (for the OQ-15 broken-copy path). */
    private var invalidHash: String? = null
    /** Bytes of the file that last failed, kept only so the detail view can locate errors on demand. */
    @Volatile private var invalidBytes: ByteArray? = null
    /** §18.2 `Ask before merging` (default off): when true, conflicts show the B6 prompt instead of auto-merging. */
    @Volatile var askBeforeMerge = false
    /** §18.2 Undo record: pre-merge file bytes, the hash the merge wrote, and the items it touched. */
    private var undoBytes: ByteArray? = null
    private var mergedHash: String? = null
    private var undoTouched: List<dev.nasawa.configlauncher.model.UndoMerge.Touched> = emptyList()

    /** While true (drag in progress) inbound reloads are held until drop (OQ-24). */
    @Volatile var dragging = false
    private val mutex = Mutex()
    private var lastStamp: Stamp? = null
    private var pollCount = 0
    /** Hashes we wrote ourselves (last 4); seeing one of them again is an echo, not an edit (§5). */
    private val selfWrites = ArrayDeque<String>()

    /** Key of the last status.json written; identical results are not rewritten (no churn, no loop). */
    private var lastStatusKey: String? = null
    /** Last load result reported in status.json, so backup/conflict changes can re-emit it. */
    private var statusResult: Triple<String, List<String>, String>? = null
    /** §17 Q4: last backup-copy failure (null once a later save succeeds). */
    private var backupError: String? = null
    /** OQ-21: sync/merge leftovers next to launcher.json. */
    private var conflictFiles: List<String> = emptyList()

    private val _state = MutableStateFlow(initial().copy(reconnectable = source.reconnectable))
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private fun initial(): LoadState {
        val bytes = runCatching { lastGood.load() }.getOrNull()
        val parsed = bytes?.let { ConfigCodec.decode(it) as? ParseResult.Ok }
        return if (parsed != null) LoadState(parsed.config, Origin.LAST_GOOD, source.displayName, ConfigCodec.sha256(bytes))
        else LoadState(DefaultLayout.config, Origin.DEFAULT, source.displayName)
    }

    /** What a candidate source holds, for the §18.2 "move layout" dialog. */
    sealed interface Inspection {
        data object Empty : Inspection
        data class Valid(val pages: Int, val items: Int) : Inspection
        data class Invalid(val errors: List<String>) : Inspection
    }

    fun inspect(src: ConfigSource): Inspection {
        val bytes = src.read(Limits.MAX_BYTES) ?: return Inspection.Empty
        return when (val r = ConfigCodec.decode(bytes)) {
            is ParseResult.Ok -> Inspection.Valid(r.config.pages.size, r.config.pages.sumOf { it.items.size } + r.config.dock.orEmpty().size)
            is ParseResult.Invalid -> Inspection.Invalid(r.errors)
        }
    }

    /**
     * §18.2 move the layout to [target]. [useMine]: our current layout is written there (an existing
     * target file is copied to `.configlauncher/backup/` first). Otherwise the target's file is used
     * and our current layout is kept as a history entry. An empty target always gets our layout.
     */
    suspend fun moveTo(target: ConfigSource, useMine: Boolean): Boolean = mutex.withLock {
        val existing = try { target.read(Limits.MAX_BYTES) } catch (e: Exception) {
            _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_read_failed, e.reason())) }; return false
        }
        val mine = existing == null || useMine
        val adopted = if (mine) null else when (val r = ConfigCodec.decode(existing!!)) {
            is ParseResult.Ok -> r.config
            // Grace review: never adopt an invalid folder file. Nothing changes; the user can replace it or fix it.
            is ParseResult.Invalid -> { _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_invalid)) }; return false }
        }
        if (existing != null && useMine) {
            // Folder: agent-visible backup first; if that fails, don't overwrite. App-only (§18.5): private history.
            try { target.writeSidecar("backup", StatusJson.brokenFileName(clock()), existing) }
            catch (_: UnsupportedOperationException) { runCatching { lastGood.snapshot(existing) } }
            catch (e: Exception) { _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.folder_backup_failed, e.reason())) }; return false }
        }
        val oldSource = source; val oldState = _state.value; val oldStamp = lastStamp
        if (adopted != null) runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        source = target; lastStamp = null; lastStatusKey = null; statusResult = null; conflictFiles = emptyList()
        try {
            if (adopted != null) {
                val hash = ConfigCodec.sha256(existing!!)
                lastStamp = target.stamp()
                _state.value = LoadState(adopted, Origin.FILE, target.displayName, hash, reconnectable = target.reconnectable)
                saveLastGood(existing)
            } else writeLocked(oldState.config)
            true
        } catch (e: Exception) {
            // Roll back: the old source stays in charge and nothing is reported as moved.
            source = oldSource; lastStamp = oldStamp
            _state.value = oldState.copy(notice = Msg(dev.nasawa.configlauncher.R.string.move_failed, e.reason()))
            false
        }
    }

    suspend fun switchSource(newSource: ConfigSource) {
        mutex.withLock {
            source = newSource
            lastStamp = null
            lastStatusKey = null; statusResult = null; conflictFiles = emptyList()
            _state.update { it.copy(sourceName = newSource.displayName, issue = null, reconnectable = newSource.reconnectable) }
        }
        poll(force = true)
    }

    /**
     * Check the source once. [force] (resume, ContentObserver, manual reload) always reads and hashes.
     * Returns true when a new model was published.
     */
    suspend fun poll(force: Boolean = false): Boolean = mutex.withLock { pollLocked(force) }

    private suspend fun pollLocked(force: Boolean): Boolean {
        if (dragging || pending.isNotEmpty() || _state.value.conflict) return false
        val src = source
        val hashAnyway = force || (++pollCount % 5 == 0)
        val stamp = try { src.stamp() } catch (e: Exception) { return fail(classify(e, src)) }
        if (stamp == null) {
            lastStamp = null
            return fail(Issue.Missing)
        }
        if (hashAnyway) refreshConflicts(src)
        if (!hashAnyway && stamp == lastStamp) return false
        val bytes = try { src.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(classify(e, src)) }
            ?: return fail(Issue.Missing)
        val hash = ConfigCodec.sha256(bytes)
        if (hash == _state.value.baseHash && _state.value.origin == Origin.FILE || hash in selfWrites) {
            lastStamp = stamp
            if (_state.value.issue != null) _state.update { it.copy(issue = null) }
            return false
        }
        if (stabilityMs > 0) {
            delay(stabilityMs)
            val again = try { src.stamp() } catch (_: Exception) { null }
            if (again != stamp) return false // still being written; next poll retries
        }
        lastStamp = stamp
        return when (val r = ConfigCodec.decode(bytes)) {
            is ParseResult.Invalid -> {
                invalidHash = hash; invalidBytes = bytes
                val issue = classifyParse(r.errors)
                writeStatus(StatusJson.resultOf(issue), r.errors, hash)
                fail(issue)
            }
            is ParseResult.Ok -> {
                invalidHash = null; invalidBytes = null
                writeStatus("ok", emptyList(), hash)
                // An external reload doesn't expire Undo (§18.2); it takes the "changed" path.
                _state.value = LoadState(r.config, Origin.FILE, src.displayName, hash, reconnectable = src.reconnectable,
                    mergeUndo = undoBytes != null, merge = _state.value.merge.takeIf { undoBytes != null })
                saveLastGood(bytes)
                true
            }
        }
    }

    /**
     * Outbound binding (§5): apply [edit] to the shown model now; write after [debounceMs] of quiet.
     * Edits made while B6 is up join the queue (§17 Q8). Returns false for a no-op edit.
     */
    suspend fun edit(edit: Edit): Boolean {
        mutex.withLock {
            val r = Edits.apply(_state.value.config, edit)
            if (!r.applied) return false
            pending += edit
            _state.update { it.copy(config = r.config, pendingEdits = pending.size) }
        }
        scheduleFlush()
        return true
    }

    private fun scheduleFlush() {
        val s = scope ?: return
        flushJob?.cancel()
        flushJob = s.launch { delay(debounceMs); while (dragging) delay(100); flush() }
    }

    /** B5 `Retry`: write the edits kept in memory again (same path as a debounced flush, incl. the B6 hash check). */
    suspend fun retrySave(): Boolean { flushJob?.cancel(); return flush() }

    /**
     * Write pending edits. The B6 hash check runs first (§17 Q7): if the file changed since we
     * loaded it, enter the conflict state. Only if the file is still the same broken bytes is the
     * broken copy snapshotted before overwriting (OQ-15).
     */
    suspend fun flush(): Boolean = mutex.withLock {
        if (pending.isEmpty() || _state.value.conflict) return false
        val current = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        val curHash = current?.let { ConfigCodec.sha256(it) }
        val base = _state.value.baseHash
        val stillBroken = curHash != null && curHash == invalidHash
        if (current != null && curHash != base && curHash !in selfWrites && !stillBroken) {
            // §18.2: the file wins except the items targeted by pending ops; prompt only if asked to.
            // A merge whose write failed still returns true from autoMergeLocked (no B6), but reports B5.
            if (!askBeforeMerge && autoMergeLocked(current)) return _state.value.issue !is Issue.SaveFailed
            _state.update { it.copy(conflict = true) }
            return false
        }
        var brokenName: String? = null
        if (stillBroken) current?.let { b ->
            runCatching { lastGood.snapshot(b) }
            // OQ-15: agent-visible copy in the folder too. Non-blocking: a failure keeps the private copy only.
            val name = StatusJson.brokenFileName(clock())
            if (runCatching { source.writeSidecar("broken", name, b) }.isSuccess) brokenName = name
        }
        val cfg = _state.value.config.let { it.copy(revision = (it.revision ?: 0) + 1) }
        try { writeLocked(cfg) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        pending.clear(); invalidHash = null; clearUndo()
        _state.update { it.copy(pendingEdits = 0, issue = null, notice = when {
            brokenName != null -> Msg(dev.nasawa.configlauncher.R.string.broken_saved_folder)
            stillBroken -> Msg(dev.nasawa.configlauncher.R.string.broken_saved_history)
            else -> it.notice
        }) }
        true
    }

    /**
     * Replay pending ops by ID onto the file's version and write it. Both pre-merge versions go to
     * history. Returns false (caller falls back to B6) if the file or the merged result is invalid.
     */
    private fun autoMergeLocked(fileBytes: ByteArray): Boolean {
        val fileCfg = (ConfigCodec.decode(fileBytes) as? ParseResult.Ok)?.config ?: return false
        val ops = pending.toList()
        val rep = Edits.replay(fileCfg, ops)
        val merged = rep.config
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        if (ConfigCodec.decode(ConfigCodec.encodeBytes(out)) !is ParseResult.Ok) return false
        runCatching { lastGood.snapshot(fileBytes) }
        runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        val written = try { writeLocked(out) } catch (e: Exception) { fail(Issue.SaveFailed.of(e)); return true }
        pending.clear()
        // §9.1: all-skipped merges have no Undo (nothing of ours was written).
        val undo = rep.kept > 0
        if (undo) { undoBytes = fileBytes; mergedHash = written; undoTouched = dev.nasawa.configlauncher.model.UndoMerge.record(fileCfg, out) }
        else clearUndo()
        _state.update {
            it.copy(pendingEdits = 0, issue = null, mergeUndo = undo,
                merge = MergeNotice(rep.kept, rep.skipped.size, rep.onlyFolder, undo))
        }
        return true
    }

    /**
     * Snackbar Undo after an automatic merge (§18.2 Undo safety rule). Re-reads and hashes the file:
     * unchanged → write the pre-merge version through [writeLocked]; changed → revert only the
     * touched items by ID onto the new file, or cancel with no write. Both versions go to history first.
     */
    suspend fun undoMerge(): Boolean = mutex.withLock {
        val before = undoBytes ?: return false
        val touched = undoTouched
        clearUndo()
        val cancel = { msg: Msg -> _state.update { it.copy(mergeUndo = false, merge = null, notice = msg) }; false }
        val now = try { source.read(Limits.MAX_BYTES) } catch (_: Exception) { null } ?: return cancel(CANCELLED)
        val target: LauncherConfig = if (ConfigCodec.sha256(now) == mergedHash) {
            (ConfigCodec.decode(before) as? ParseResult.Ok)?.config ?: return cancel(CANCELLED)
        } else {
            val nowCfg = (ConfigCodec.decode(now) as? ParseResult.Ok)?.config ?: return cancel(CANCELLED)
            val r = dev.nasawa.configlauncher.model.UndoMerge.revert(nowCfg, touched) ?: return cancel(CANCELLED)
            if (ConfigCodec.decode(ConfigCodec.encodeBytes(r)) !is ParseResult.Ok) return cancel(CANCELLED)
            r.copy(revision = (nowCfg.revision ?: 0) + 1)
        }
        runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
        runCatching { lastGood.snapshot(before) }
        if (ConfigCodec.sha256(now) != mergedHash) runCatching { lastGood.snapshot(now) }
        try { writeLocked(target); _state.update { it.copy(mergeUndo = false, merge = null) }; true }
        catch (e: Exception) { fail(Issue.SaveFailed.of(e, dev.nasawa.configlauncher.R.string.b5_undo_failed)) }
    }

    private fun clearUndo() { undoBytes = null; mergedHash = null; undoTouched = emptyList() }

    fun dismissMergeUndo() { clearUndo(); _state.update { it.copy(mergeUndo = false, merge = null) } }

    /** B6 "Use file": drop local edits (snapshotted to history) and load the file. */
    suspend fun resolveUseFile() {
        mutex.withLock {
            runCatching { lastGood.snapshot(ConfigCodec.encodeBytes(_state.value.config)) }
            pending.clear(); lastStamp = null
            _state.update { it.copy(conflict = false, pendingEdits = 0, baseHash = null) }
            pollLocked(force = true)
        }
    }

    /** B6 "Keep mine": replay local ops by ID onto the file's version (snapshotted first) and write. */
    suspend fun resolveKeepMine(): Boolean = mutex.withLock {
        val bytes = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        val fileCfg = bytes?.let { (ConfigCodec.decode(it) as? ParseResult.Ok)?.config }
        val ops = pending.toList()
        val rep = if (fileCfg == null) dev.nasawa.configlauncher.model.Replay(_state.value.config, ops.size, emptyList()) else Edits.replay(fileCfg, ops)
        val merged = rep.config
        bytes?.let { runCatching { lastGood.snapshot(it) } }
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        try { writeLocked(out) } catch (e: Exception) { return fail(Issue.SaveFailed.of(e)) }
        pending.clear()
        _state.update {
            it.copy(conflict = false, pendingEdits = 0, issue = null,
                // §9.1: Keep mine uses the same copy, without Undo; all kept shows nothing extra.
                merge = MergeNotice(rep.kept, rep.skipped.size, rep.onlyFolder, undo = false).takeUnless { n -> n.silent })
        }
        true
    }

    /** B4 action "Create from current layout": the only way launcher.json is ever created. */
    suspend fun createFromCurrentLayout(): Boolean = mutex.withLock {
        try {
            if (source.stamp() != null) return pollLocked(force = true) // appeared meanwhile: never clobber
            writeLocked(_state.value.config)
            true
        } catch (e: Exception) { fail(Issue.SaveFailed.of(e, dev.nasawa.configlauncher.R.string.b5_create_failed)) }
    }

    /**
     * Serialize and write [config] canonically, recording its hash as a self-write. Caller holds the mutex.
     * Refuses output over the cap (§17 Q16) so the launcher never writes a file it can't read.
     */
    internal fun writeLocked(config: LauncherConfig): String {
        val bytes = ConfigCodec.encodeBytes(config)
        if (bytes.size > Limits.MAX_BYTES) throw TooLargeException(Limits.MAX_BYTES / 1024 / 1024)
        val hash = ConfigCodec.sha256(bytes)
        selfWrites.addLast(hash); while (selfWrites.size > 4) selfWrites.removeFirst()
        source.write(bytes)
        lastStamp = source.stamp()
        writeStatus("ok", emptyList(), hash)
        _state.value = LoadState(config, Origin.FILE, source.displayName, hash, reconnectable = source.reconnectable)
        saveLastGood(bytes)
        return hash
    }

    /** §17 Q4: a failed backup copy is non-blocking: one snackbar, retried on the next load/write. */
    private fun saveLastGood(bytes: ByteArray) {
        try {
            lastGood.save(bytes)
            if (backupError != null) { backupError = null; emitStatus() }
        } catch (e: Exception) {
            backupError = "Couldn't update the backup copy: ${e.message ?: e.javaClass.simpleName}"
            emitStatus()
            _state.update { it.copy(notice = Msg(dev.nasawa.configlauncher.R.string.backup_failed)) }
        }
    }

    /** Re-list sync/merge leftovers (cheap: one directory listing, only on hash-anyway polls). */
    private fun refreshConflicts(src: ConfigSource) {
        val found = runCatching { SyncConflicts.find(src.siblingNames()) }.getOrNull() ?: return
        if (found != conflictFiles) { conflictFiles = found; emitStatus() }
    }

    /** §16/§17: `.configlauncher/status.json` so an agent can see why an edit was rejected. Best effort. */
    private fun writeStatus(result: String, errors: List<String>, hash: String) {
        statusResult = Triple(result, errors, hash)
        emitStatus()
    }

    private var lastApps: Pair<ConfigSource, List<kotlinx.serialization.json.JsonObject>>? = null

    /**
     * #54: write `.configlauncher/apps.json` when the app list (or the source) changed. Never touches
     * launcher.json, so no reload loop. App-only mode has no folder sidecars: skipped.
     */
    suspend fun writeApps(apps: List<dev.nasawa.configlauncher.model.InstalledApp>, includeWork: Boolean = false): Boolean = mutex.withLock {
        val entries = AppsJson.entries(apps, includeWork)
        val src = source
        if (lastApps?.first === src && lastApps?.second == entries) return false
        return try { src.writeSidecar("", AppsJson.FILE, AppsJson.encode(entries, clock())); lastApps = src to entries; true }
        catch (_: Exception) { false }
    }

    private fun emitStatus() {
        val (result, errors, hash) = statusResult ?: return
        val key = "$result|$hash|${errors.hashCode()}|$backupError|$conflictFiles"
        if (key == lastStatusKey) return
        try {
            source.writeSidecar("", StatusJson.FILE, StatusJson.encode(result, errors, hash, clock(), backupError, conflictFiles))
            lastStatusKey = key
        } catch (_: Exception) { /* folder may be read-only or gone; the banner already says so */ }
    }

    /**
     * Line/column for each of [errors] in the last failed file (Q-S3). Call off the main thread and
     * only when the error detail view opens; returns all nulls if no failed file is held.
     */
    fun locateErrors(errors: List<String>): List<dev.nasawa.configlauncher.config.TextPos?> {
        val b = invalidBytes ?: return errors.map { null }
        val text = String(b, Charsets.UTF_8).removePrefix("\uFEFF")
        return dev.nasawa.configlauncher.config.ErrorLocator.locate(text, errors)
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeMerge() = _state.update { it.copy(merge = null) }

    private fun classify(e: Exception, src: ConfigSource): Issue = when (e) {
        is AccessLostException -> Issue.AccessLost(e.message ?: "folder not accessible")
        is SecurityException -> Issue.AccessLost("Can't reach ${src.displayName}: ${e.message}")
        is FileNotFoundException -> Issue.ReadFailed(e.message ?: "not found")
        else -> if (e.message?.contains("larger than") == true) Issue.ParseError(listOf(e.message!!))
            else Issue.ReadFailed("Can't read ${src.displayName}: ${e.message}")
    }

    private fun classifyParse(errors: List<String>): Issue = when {
        errors.any { it.contains("newer than this launcher") } -> Issue.TooNew(errors.first())
        errors.size == 1 && (errors[0].startsWith("not valid") || errors[0].startsWith("file is larger")) -> Issue.ParseError(errors)
        else -> Issue.Invalid(errors)
    }

    private fun fail(issue: Issue): Boolean { _state.update { it.copy(issue = issue) }; return false }
}
