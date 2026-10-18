package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.Limits
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.delay
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
) {
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
            is ParseResult.Invalid -> fail(classifyParse(r.errors))
            is ParseResult.Ok -> {
                _state.value = LoadState(r.config, Origin.FILE, src.displayName, hash)
                saveLastGood(bytes)
                true
            }
        }
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
