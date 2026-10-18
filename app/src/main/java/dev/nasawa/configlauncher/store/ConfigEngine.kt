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
    /** B5: our write failed (or would exceed the cap); the edit stays in memory. */
    data class SaveFailed(val message: String) : Issue { override val details get() = listOf(message) }
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
    val notice: String? = null,
) {
    /** Problem lines for the detail view (kept for callers/tests that only need text). */
    val errors: List<String> get() = when (issue) { null, Issue.Missing -> emptyList(); else -> issue.details }
}

/**
 * Inbound binding (ARCHITECTURE.md §4): poll → stamp change (or every 5th poll / resume: hash anyway,
 * §17 Q5) → capped read → hash guard → 300 ms stability re-check → parse/migrate/validate →
 * atomic swap into [state] → last-good. Pure Kotlin; Android wiring lives in ConfigStore.
 */
class ConfigEngine(
    @Volatile var source: ConfigSource,
    private val lastGood: LastGoodStore,
    private val stabilityMs: Long = 300,
    private val scope: CoroutineScope? = null,
    private val debounceMs: Long = 750,
) {
    private val pending = mutableListOf<Edit>()
    private var flushJob: Job? = null
    /** Hash of the file bytes that last failed to parse/validate (for the OQ-15 broken-copy path). */
    private var invalidHash: String? = null
    /** Bytes of the file that last failed, kept only so the detail view can locate errors on demand. */
    @Volatile private var invalidBytes: ByteArray? = null
    /** While true (drag in progress) inbound reloads are held until drop (OQ-24). */
    @Volatile var dragging = false
    private val mutex = Mutex()
    private var lastStamp: Stamp? = null
    private var pollCount = 0
    /** Hashes we wrote ourselves (last 4); seeing one of them again is an echo, not an edit (§5). */
    private val selfWrites = ArrayDeque<String>()

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private fun initial(): LoadState {
        val bytes = runCatching { lastGood.load() }.getOrNull()
        val parsed = bytes?.let { ConfigCodec.decode(it) as? ParseResult.Ok }
        return if (parsed != null) LoadState(parsed.config, Origin.LAST_GOOD, source.displayName, ConfigCodec.sha256(bytes))
        else LoadState(DefaultLayout.config, Origin.DEFAULT, source.displayName)
    }

    suspend fun switchSource(newSource: ConfigSource) {
        mutex.withLock {
            source = newSource
            lastStamp = null
            _state.update { it.copy(sourceName = newSource.displayName, issue = null) }
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
            is ParseResult.Invalid -> { invalidHash = hash; invalidBytes = bytes; fail(classifyParse(r.errors)) }
            is ParseResult.Ok -> {
                invalidHash = null; invalidBytes = null
                _state.value = LoadState(r.config, Origin.FILE, src.displayName, hash)
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

    /**
     * Write pending edits. The B6 hash check runs first (§17 Q7): if the file changed since we
     * loaded it, enter the conflict state. Only if the file is still the same broken bytes is the
     * broken copy snapshotted before overwriting (OQ-15).
     */
    suspend fun flush(): Boolean = mutex.withLock {
        if (pending.isEmpty() || _state.value.conflict) return false
        val current = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed(e.message ?: "read failed")) }
        val curHash = current?.let { ConfigCodec.sha256(it) }
        val base = _state.value.baseHash
        val stillBroken = curHash != null && curHash == invalidHash
        if (current != null && curHash != base && curHash !in selfWrites && !stillBroken) {
            _state.update { it.copy(conflict = true) }
            return false
        }
        if (stillBroken) current?.let { b -> runCatching { lastGood.snapshot(b) } }
        val cfg = _state.value.config.let { it.copy(revision = (it.revision ?: 0) + 1) }
        try { writeLocked(cfg) } catch (e: Exception) { return fail(Issue.SaveFailed("Couldn't save your change: ${e.message}")) }
        pending.clear(); invalidHash = null
        _state.update { it.copy(pendingEdits = 0, issue = null, notice = if (stillBroken) "Your broken launcher.json was saved to history." else it.notice) }
        true
    }

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
        val bytes = try { source.read(Limits.MAX_BYTES) } catch (e: Exception) { return fail(Issue.SaveFailed(e.message ?: "read failed")) }
        val fileCfg = bytes?.let { (ConfigCodec.decode(it) as? ParseResult.Ok)?.config }
        val (merged, skipped) = if (fileCfg == null) _state.value.config to 0 else Edits.replay(fileCfg, pending.toList())
        bytes?.let { runCatching { lastGood.snapshot(it) } }
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        try { writeLocked(out) } catch (e: Exception) { return fail(Issue.SaveFailed("Couldn't save your change: ${e.message}")) }
        pending.clear()
        _state.update {
            it.copy(conflict = false, pendingEdits = 0, issue = null,
                notice = if (skipped > 0) "$skipped change(s) skipped because the item was removed or moved in the file" else null)
        }
        true
    }

    /** B4 action "Create from current layout": the only way launcher.json is ever created. */
    suspend fun createFromCurrentLayout(): Boolean = mutex.withLock {
        try {
            if (source.stamp() != null) return pollLocked(force = true) // appeared meanwhile: never clobber
            writeLocked(_state.value.config)
            true
        } catch (e: Exception) { fail(Issue.SaveFailed("Couldn't create launcher.json: ${e.message}")) }
    }

    /**
     * Serialize and write [config] canonically, recording its hash as a self-write. Caller holds the mutex.
     * Refuses output over the cap (§17 Q16) so the launcher never writes a file it can't read.
     */
    internal fun writeLocked(config: LauncherConfig): String {
        val bytes = ConfigCodec.encodeBytes(config)
        if (bytes.size > Limits.MAX_BYTES) throw IOException("launcher.json would be over ${Limits.MAX_BYTES / 1024 / 1024} MB")
        val hash = ConfigCodec.sha256(bytes)
        selfWrites.addLast(hash); while (selfWrites.size > 4) selfWrites.removeFirst()
        source.write(bytes)
        lastStamp = source.stamp()
        _state.value = LoadState(config, Origin.FILE, source.displayName, hash)
        saveLastGood(bytes)
        return hash
    }

    /** §17 Q4: a failed backup copy is non-blocking: one snackbar, retried on the next load/write. */
    private fun saveLastGood(bytes: ByteArray) {
        try { lastGood.save(bytes) } catch (e: Exception) {
            _state.update { it.copy(notice = "Couldn't update the backup copy (phone storage full).") }
        }
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
