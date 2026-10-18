package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.delay
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
) {
    private val mutex = Mutex()
    private var lastStamp: Stamp? = null
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
        _state.update { it.copy(sourceName = newSource.displayName, errors = emptyList()) }
    }.also { poll(force = true) }

    /** Check the source once. Returns true when a new model was published. */
    suspend fun poll(force: Boolean = false): Boolean = mutex.withLock { pollLocked(force) }

    private suspend fun pollLocked(force: Boolean): Boolean {
        val src = source
        val stamp = try { src.stamp() } catch (e: Exception) { return fail("Can't read ${src.displayName}: ${e.message}") }
        if (stamp == null) {
            lastStamp = null
            // No launcher.json yet: create it from the current layout so there is something to edit.
            return try { writeLocked(_state.value.config); false } catch (e: Exception) { fail("Couldn't create launcher.json: ${e.message}") }
        }
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

    /** Serialize and write [config] canonically, recording its hash as a self-write. Caller holds the mutex. */
    internal fun writeLocked(config: LauncherConfig): String {
        val bytes = ConfigCodec.encodeBytes(config)
        val hash = ConfigCodec.sha256(bytes)
        selfWrites.addLast(hash); while (selfWrites.size > 4) selfWrites.removeFirst()
        source.write(bytes)
        lastStamp = source.stamp()
        _state.value = LoadState(config, Origin.FILE, source.displayName, hash)
        runCatching { lastGood.save(bytes) }
        return hash
    }

    private fun fail(msg: String) = fail(listOf(msg))
    private fun fail(errors: List<String>): Boolean { _state.update { it.copy(errors = errors) }; return false }
}
