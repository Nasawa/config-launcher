package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec

/**
 * ARCHITECTURE.md §20.2: keep `.configlauncher/launcher-config.v1.schema.json` (the bundled schema) in a connected folder,
 * so agents can validate offline. Written when a folder is chosen or connected and again after an app update changes the
 * bundled schema. `validate.py` is not shipped; it stays a download from the repo.
 */
object SchemaCopy {
    const val NAME = "launcher-config.v1.schema.json"
    const val ASSET = NAME

    /**
     * Writes [bundled] into [src]'s `.configlauncher/` unless [lastWrittenSha] (what we last wrote to this folder) already
     * matches it. Returns the hash to remember, or null when nothing changed or the write failed (retried next time).
     */
    fun sync(src: ConfigSource, bundled: ByteArray, lastWrittenSha: String?): String? {
        val sha = ConfigCodec.sha256(bundled)
        if (sha == lastWrittenSha) return null
        return try { src.writeSidecar("", NAME, bundled); sha } catch (e: Exception) { null }
    }
}
