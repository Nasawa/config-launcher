package dev.nasawa.configlauncher.store

/**
 * #57: a human/agent-readable path for a SAF tree, e.g. `Internal storage/Launcher`. Only the
 * local storage provider maps to a real path; anything else (cloud) returns null and callers fall
 * back to the tree URI.
 */
object FolderPath {
    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun of(authority: String?, treeDocId: String): String? {
        if (authority != EXTERNAL_STORAGE) return null
        val volume = treeDocId.substringBefore(':', "")
        val rel = treeDocId.substringAfter(':', "").trim('/')
        val root = when {
            volume == "primary" -> "Internal storage"
            volume == "home" -> "Internal storage/Documents"
            volume.isNotEmpty() -> "SD card ($volume)"
            else -> return null
        }
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** Full location of launcher.json for a tree: the readable path when known, else the tree URI. */
    fun launcherJson(authority: String?, treeDocId: String, treeUri: String): String =
        of(authority, treeDocId)?.let { "$it/launcher.json" } ?: treeUri
}
