package dev.nasawa.configlauncher.config

/** Detects leftover git merge conflict markers in launcher.json. */
object GitMarkers {
    /** Line (1-based) of the first git conflict marker block in [text], or null. */
    fun firstLine(text: String): Int? {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.startsWith("<<<<<<<") }
        if (start < 0) return null
        val hasMid = lines.drop(start + 1).any { it.startsWith("=======") }
        val hasEnd = lines.drop(start + 1).any { it.startsWith(">>>>>>>") }
        return if (hasMid && hasEnd) start + 1 else null
    }
}
