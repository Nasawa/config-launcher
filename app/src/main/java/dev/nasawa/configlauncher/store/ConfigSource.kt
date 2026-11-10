package dev.nasawa.configlauncher.store

import java.io.IOException

/** Cheap change detector: (document id, mtime, size). Equal stamps mean "no need to read". */
data class Stamp(val docId: String, val lastModified: Long, val size: Long)

/** Where launcher.json lives. Implementations: [LocalFileSource] (app storage), SafSource (picked folder). */
interface ConfigSource {
    val displayName: String
    /** False for app storage: there is no folder grant to reconnect, so B4 hides "Reconnect". */
    val reconnectable: Boolean get() = true
    /** #57: full location of launcher.json for people and agents; null for app storage. */
    val location: String? get() = null
    /** null when launcher.json does not exist. */
    fun stamp(): Stamp?
    /** null when launcher.json does not exist. Must enforce [maxBytes]. */
    fun read(maxBytes: Int): ByteArray?
    /** Replace launcher.json with [bytes] safely (implementation-specific, see ARCHITECTURE.md §5). Throws on failure. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /**
     * Write a sidecar file at `.configlauncher/<subdir>/<name>` ([subdir] may be empty), creating
     * directories as needed. Never touches launcher.json, so it never changes [stamp] (no reload loop).
     */
    /** Names of the files next to launcher.json (for OQ-21 conflict detection). */
    fun siblingNames(): List<String> = emptyList()

    @Throws(IOException::class)
    fun writeSidecar(subdir: String, name: String, bytes: ByteArray)

    /** #139: cheap change check for a sidecar; null when absent or unknown (the caller then reads). */
    fun sidecarStamp(subdir: String, name: String): Stamp? = null

    /** `.configlauncher/<subdir>/<name>` ([subdir] may be empty); null when absent. */
    fun readSidecar(subdir: String, name: String, maxBytes: Int): ByteArray? = throw UnsupportedOperationException("no folder")

    /** §20.1: a file next to launcher.json (e.g. AGENTS.md); null when absent. Only folder sources have one. */
    fun readRoot(name: String, maxBytes: Int): ByteArray? = throw UnsupportedOperationException("no folder")

    /** #244/#251: names this folder won't create again because the provider hides the existing one ([SafDirs]). */
    fun suppressedNames(): List<String> = emptyList()

    /** #251: Warnings / status.json lines for save temps the start sweep kept as the only copy ([SafWrites]). */
    fun tempWarnings(): List<String> = emptyList()

    /** #251: names suppressed since the last call, each returned once (for the one-shot notice). */
    fun takeNewlySuppressed(): List<String> = emptyList()

    /** #251: the user picked this folder again: forget its suppressions so creates are tried again. */
    fun clearSuppression() {}

    /** §20.1: write a file next to launcher.json through the same temp + verify path as launcher.json. */
    @Throws(IOException::class)
    fun writeRoot(name: String, bytes: ByteArray): Unit = throw UnsupportedOperationException("no folder")
}

internal fun java.io.InputStream.readCapped(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
    }
    return out.toByteArray()
}
