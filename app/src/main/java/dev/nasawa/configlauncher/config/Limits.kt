package dev.nasawa.configlauncher.config

/** Single source for file limits (ARCHITECTURE.md §15 OQ-9, §17 Q16/Q1). */
object Limits {
    /** Read cap and writer cap: the launcher never writes a file it would refuse to read. */
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_DEPTH = 8
    const val MAX_ITEMS = 2000
    const val MAX_STRING = 256
    const val MAX_TAGS = 16
}
