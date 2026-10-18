package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
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

enum class Origin { FILE, LAST_GOOD, DEFAULT }

data class LoadState(
    val config: LauncherConfig,
    val origin: Origin,
    val sourceName: String,
    /** Hash of the file version [config] was derived from (the write-back base hash). */
    val baseHash: String? = null,
    /** Validation/IO problems with the current file; the model shown is the last good one (banner B2). */
    val errors: List<String> = emptyList(),
    /** Local edits not yet written (optimistic; shown already). */
    val pendingEdits: Int = 0,
    /** Set when launcher.json changed under pending edits (banner B6: Use file / Keep mine). */
    val conflict: Boolean = false,
    /** One-shot user message (e.g. "1 change skipped..."), cleared by [ConfigEngine.consumeNotice]. */
    val notice: String? = null,
)

/**
 * Inbound binding (ARCHITECTURE.md §4): poll → stamp change → read → hash guard →
 * 300 ms stability re-check → parse/migrate/validate → atomic swap into [state] → last-good.
 * Pure Kotlin; Android wiring (FileObserver, SAF polling, lifecycle) lives in ConfigStore.
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
    /** While true (drag in progress) inbound reloads are held until drop (OQ-24). */
    @Volatile var dragging = false
    private val mutex = Mutex()
    private var lastStamp: Stamp? = null
    /** True once launcher.json has existed on the current source. */
    private var seenFile = false
    /** Hashes we wrote ourselves (last 4); seeing one of them again is an echo, not an edit (§5). */
    private val selfWrites = ArrayDeque<String>()

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private fun initial(): LoadState {
        val bytes = lastGood.load()
        val parsed = bytes?.let { ConfigCodec.decode(it) as? ParseResult.Ok }
        return if (parsed != null) LoadState(parsed.config, Origin.LAST_GOOD, source.displayName, ConfigCodec.sha256(bytes))
        else LoadState(DefaultLayout.config, Origin.DEFAULT, source.displayName)
    }

    suspend fun switchSource(newSource: ConfigSource) = mutex.withLock {
        source = newSource
        lastStamp = null
        seenFile = false
        _state.update { it.copy(sourceName = newSource.displayName, errors = emptyList()) }
    }.also { poll(force = true) }

    /** Check the source once. Returns true when a new model was published. */
    suspend fun poll(force: Boolean = false): Boolean = mutex.withLock { pollLocked(force) }

    private suspend fun pollLocked(force: Boolean): Boolean {
        if (dragging || pending.isNotEmpty() || _state.value.conflict) return false
        val src = source
        val stamp = try { src.stamp() } catch (e: Exception) { return fail("Can't read ${src.displayName}: ${e.message}") }
        if (stamp == null) {
            lastStamp = null
            // A file we have seen vanish is usually an external replace (adb push, sync tools, git):
            // never recreate it then, or we would clobber the incoming version.
            if (seenFile) return fail("launcher.json is missing; showing the last working layout")
            if (stabilityMs > 0) { delay(stabilityMs); if (runCatching { src.stamp() }.getOrNull() != null) return false }
            // No launcher.json on this source yet: create it from the current layout so there is something to edit.
            return try { writeLocked(_state.value.config); false } catch (e: Exception) { fail("Couldn't create launcher.json: ${e.message}") }
        }
        seenFile = true
        if (!force && stamp == lastStamp) return false
        var bytes = try { src.read(ConfigCodec.MAX_BYTES) } catch (e: Exception) { return fail(e.message ?: "read failed") } ?: return false
        var hash = ConfigCodec.sha256(bytes)
        if (hash == _state.value.baseHash && _state.value.origin == Origin.FILE || hash in selfWrites) {
            lastStamp = stamp
            if (_state.value.errors.isNotEmpty()) _state.update { it.copy(errors = emptyList()) }
            return false
        }
        if (stabilityMs > 0) {
            delay(stabilityMs)
            val again = try { src.stamp() } catch (_: Exception) { null }
            if (again != stamp) return false // still being written; next poll retries
        }
        lastStamp = stamp
        return when (val r = ConfigCodec.decode(bytes)) {
            is ParseResult.Invalid -> fail(r.errors)
            is ParseResult.Ok -> {
                _state.value = LoadState(r.config, Origin.FILE, src.displayName, hash)
                runCatching { lastGood.save(bytes) }
                true
            }
        }
    }

    /**
     * Outbound binding (§5): apply [edit] to the shown model immediately, then write after
     * [debounceMs] of quiet. Returns false if the edit was a no-op / not applicable.
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

    /** Write pending edits if the file still has the base hash; otherwise enter the conflict state. */
    suspend fun flush(): Boolean = mutex.withLock {
        if (pending.isEmpty() || _state.value.conflict) return false
        val current = try { source.read(ConfigCodec.MAX_BYTES) } catch (e: Exception) { return fail("Couldn't save layout: ${e.message}") }
        val base = _state.value.baseHash
        if (current != null && ConfigCodec.sha256(current) != base && ConfigCodec.sha256(current) !in selfWrites) {
            _state.update { it.copy(conflict = true) }
            return false
        }
        val cfg = _state.value.config.let { it.copy(revision = (it.revision ?: 0) + 1) }
        try { writeLocked(cfg) } catch (e: Exception) { return fail("Couldn't save layout: ${e.message}") }
        pending.clear()
        _state.update { it.copy(pendingEdits = 0, errors = emptyList()) }
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
        val bytes = try { source.read(ConfigCodec.MAX_BYTES) } catch (e: Exception) { return fail(e.message ?: "read failed") }
        val fileCfg = bytes?.let { (ConfigCodec.decode(it) as? ParseResult.Ok)?.config }
        // An invalid or missing file has nothing to merge into: my model wins (the broken bytes are kept in history).
        val (merged, skipped) = if (fileCfg == null) _state.value.config to 0 else Edits.replay(fileCfg, pending.toList())
        bytes?.let { runCatching { lastGood.snapshot(it) } }
        val out = merged.copy(revision = maxOf(merged.revision ?: 0, _state.value.config.revision ?: 0) + 1)
        try { writeLocked(out) } catch (e: Exception) { return fail("Couldn't save layout: ${e.message}") }
        pending.clear()
        _state.update {
            it.copy(conflict = false, pendingEdits = 0, errors = emptyList(),
                notice = if (skipped > 0) "$skipped change(s) skipped because the item was removed or moved in the file" else null)
        }
        true
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Serialize and write [config] canonically, recording its hash as a self-write. Caller holds the mutex. */
    internal fun writeLocked(config: LauncherConfig): String {
        val bytes = ConfigCodec.encodeBytes(config)
        val hash = ConfigCodec.sha256(bytes)
        selfWrites.addLast(hash); while (selfWrites.size > 4) selfWrites.removeFirst()
        source.write(bytes)
        seenFile = true
        lastStamp = source.stamp()
        _state.value = LoadState(config, Origin.FILE, source.displayName, hash)
        runCatching { lastGood.save(bytes) }
        return hash
    }

    private fun fail(msg: String) = fail(listOf(msg))
    private fun fail(errors: List<String>): Boolean { _state.update { it.copy(errors = errors) }; return false }
}
