package dev.nasawa.configlauncher.store

/**
 * OQ-21: files next to launcher.json that look like sync or merge leftovers. Git is the main sync
 * tool, so git mergetool/apply leftovers are covered as well as cloud-provider conflict names.
 * Matching is case-insensitive on the file name only; the loader never reads these.
 */
object SyncConflicts {
    private val patterns = listOf(
        // Syncthing: launcher.sync-conflict-20261018-101500-ABCDEFG.json
        Regex("""launcher\.sync-conflict-.*\.json"""),
        // Dropbox / Nextcloud / ownCloud / Drive / OneDrive duplicates:
        // "launcher (Chris's conflicted copy 2026-10-18).json", "launcher (1).json", "launcher (conflict 2026...).json"
        Regex("""launcher \(.*\)\.json"""),
        // iCloud / macOS Finder style: "launcher 2.json"
        Regex("""launcher \d+\.json"""),
        // Resilio / generic: launcher.conflict.json, launcher_conflict-....json, launcher-conflict....json
        Regex("""launcher[._ -]conflict.*\.json"""),
        // OneDrive machine-name copies: launcher-DESKTOP-AB12CD.json, launcher-LAPTOP-AB12CD.json
        Regex("""launcher-(desktop|laptop)-[a-z0-9]+\.json"""),
        // git mergetool: launcher_BACKUP_1234.json, _BASE_, _LOCAL_, _REMOTE_
        Regex("""launcher_(backup|base|local|remote)_\d+\.json"""),
        // git mergetool / git apply / patch leftovers
        Regex("""launcher\.json\.(orig|rej)"""),
        Regex("""launcher\.(orig|rej)\.json"""),
    )

    fun isConflict(name: String): Boolean = name.lowercase().let { n -> patterns.any { it.matches(n) } }

    fun find(names: Collection<String>): List<String> = names.filter(::isConflict).sorted()
}
