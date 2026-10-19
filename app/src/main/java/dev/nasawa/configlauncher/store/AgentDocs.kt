package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec

/**
 * ARCHITECTURE.md §20.1: put the bundled `AGENTS.md` (templates/config-folder/AGENTS.md) next to launcher.json,
 * so agent tools editing the folder find the rules. Never overwrites a file the user changed, and never brings back
 * one the user deleted, except through the explicit *Add again* action ([force]).
 */
object AgentDocs {
    const val NAME = "AGENTS.md"
    const val ASSET = "config-folder/AGENTS.md"
    private const val MAX_BYTES = 256 * 1024

    /**
     * SHA-256 of every AGENTS.md version ever shipped. A file whose hash is here is untouched by the user and may be
     * updated. When the template changes, ADD its new hash (keep the old ones); AgentDocsTest fails until you do.
     */
    val SHIPPED_SHA256 = setOf(
        "dc97f433e35682619150d2690ec1ed72262433f0b7af5f0eb20730143ef46929",   // #76 / PR #84
    )

    enum class Outcome { WROTE, UPDATED, UP_TO_DATE, USER_CHANGED, USER_DELETED, OFF, FAILED }

    /** Pure decision. [existing] null = no file; [writtenBefore] = we wrote it into this folder once already. */
    fun decide(bundled: ByteArray, existing: ByteArray?, writtenBefore: Boolean, enabled: Boolean, force: Boolean): Outcome {
        if (force) return if (existing == null) Outcome.WROTE else Outcome.UPDATED.takeIf { !existing.contentEquals(bundled) } ?: Outcome.UP_TO_DATE
        if (!enabled) return Outcome.OFF
        if (existing == null) return if (writtenBefore) Outcome.USER_DELETED else Outcome.WROTE
        if (existing.contentEquals(bundled)) return Outcome.UP_TO_DATE
        return if (ConfigCodec.sha256(existing) in SHIPPED_SHA256) Outcome.UPDATED else Outcome.USER_CHANGED
    }

    /** Apply [decide] to [src]. When [remember] is true for the outcome, the caller records "written before" for the folder. */
    fun sync(src: ConfigSource, bundled: ByteArray, writtenBefore: Boolean, enabled: Boolean, force: Boolean = false): Outcome = try {
        if (!enabled && !force) Outcome.OFF
        else {
            val o = decide(bundled, src.readRoot(NAME, MAX_BYTES), writtenBefore, enabled, force)
            if (o == Outcome.WROTE || o == Outcome.UPDATED) src.writeRoot(NAME, bundled)
            o
        }
    } catch (e: Exception) { Outcome.FAILED }

    /** The folder has (or had) an AGENTS.md from here on: if it disappears later, the user deleted it. */
    fun remember(o: Outcome) = o == Outcome.WROTE || o == Outcome.UPDATED || o == Outcome.UP_TO_DATE || o == Outcome.USER_CHANGED

    // ---- ux.md §7.1 menu action: `Add agent instructions` / `Update agent instructions` ----

    enum class MenuAction { ADD, UPDATE }
    fun menuAction(existing: ByteArray?) = if (existing == null) MenuAction.ADD else MenuAction.UPDATE

    enum class Update { ADDED, UPDATED, NEEDS_CONFIRM, FAILED }

    /** Backup name under `.configlauncher/backup/`, e.g. `AGENTS.20261018-235300.md`. */
    fun backupName(nowMillis: Long): String =
        "AGENTS." + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(nowMillis)) + ".md"

    /**
     * The menu action. No file → write. An untouched file (same bytes or a shipped hash) → replace silently (UPDATED,
     * caller shows `Agent instructions updated.`). An edited file → NEEDS_CONFIRM unless [confirmed]; confirmed copies
     * it to `.configlauncher/backup/<backupName>` first, and writes nothing if that backup fails.
     */
    fun update(src: ConfigSource, bundled: ByteArray, confirmed: Boolean, nowMillis: Long = System.currentTimeMillis()): Update = try {
        val existing = src.readRoot(NAME, MAX_BYTES)
        when {
            existing == null -> { src.writeRoot(NAME, bundled); Update.ADDED }
            existing.contentEquals(bundled) || ConfigCodec.sha256(existing) in SHIPPED_SHA256 -> {
                if (!existing.contentEquals(bundled)) src.writeRoot(NAME, bundled); Update.UPDATED
            }
            !confirmed -> Update.NEEDS_CONFIRM
            else -> { src.writeSidecar("backup", backupName(nowMillis), existing); src.writeRoot(NAME, bundled); Update.UPDATED }
        }
    } catch (e: Exception) { Update.FAILED }

    fun readExisting(src: ConfigSource): ByteArray? = runCatching { src.readRoot(NAME, MAX_BYTES) }.getOrNull()
}
