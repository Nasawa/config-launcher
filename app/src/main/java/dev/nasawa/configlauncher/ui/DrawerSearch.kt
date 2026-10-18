package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.model.InstalledApp
import java.text.Normalizer

/** docs/ux.md §2 drawer search: case- and accent-insensitive; label start, then word start, then anywhere. */
object DrawerSearch {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    fun filter(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return apps
        return apps.mapNotNull { a ->
            val l = normalize(Labels.clean(a.label))
            val rank = when {
                l.startsWith(q) -> 0
                l.split(' ', '-', '_', '.', '(').any { it.startsWith(q) } -> 1
                l.contains(q) -> 2
                else -> return@mapNotNull null
            }
            rank to a
        }.sortedBy { it.first }.map { it.second } // stable: keeps drawer order within a rank
    }
}
