package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec

/**
 * ARCHITECTURE.md §20.2: keep `.configlauncher/launcher-config.v1.schema.json` (the bundled schema) in a connected folder,
 * so agents can validate offline. Checked when a folder is chosen or connected and on every start (so an app update
 * brings the new schema). Hash-guarded like [AgentDocs]: an existing file is replaced only if it is a version we shipped;
 * a file the user edited is never touched. Writes only that one file. `validate.py` is not shipped.
 */
object SchemaCopy {
    const val NAME = "launcher-config.v1.schema.json"
    const val ASSET = NAME
    private const val MAX_BYTES = 1024 * 1024

    /**
     * SHA-256 of every schema copy ever shipped. When schema/launcher-config.v1.schema.json changes, ADD its new hash
     * (keep the old ones); SchemaCopyTest fails until you do.
     */
    val SHIPPED_SHA256 = setOf(
        "8ed3e407309d41e58c9529c2e0b6bce70f9e35deaedd11f7835c990651eb0ab8",   // first shipped with #88
        "40a72b35f54f47c11e7ff6ae57466257ab2093b28f229e72578249581f3969eb",   // #79 elements container
    )

    enum class Outcome { WROTE, UPDATED, UP_TO_DATE, USER_CHANGED, FAILED }

    fun decide(bundled: ByteArray, existing: ByteArray?): Outcome = when {
        existing == null -> Outcome.WROTE
        existing.contentEquals(bundled) -> Outcome.UP_TO_DATE
        ConfigCodec.sha256(existing) in SHIPPED_SHA256 -> Outcome.UPDATED
        else -> Outcome.USER_CHANGED
    }

    fun sync(src: ConfigSource, bundled: ByteArray): Outcome = try {
        decide(bundled, src.readSidecar("", NAME, MAX_BYTES)).also {
            if (it == Outcome.WROTE || it == Outcome.UPDATED) src.writeSidecar("", NAME, bundled)
        }
    } catch (e: Exception) { Outcome.FAILED }
}
