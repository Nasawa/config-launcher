package dev.nasawa.configlauncher.model

/**
 * §18.2 Undo safety rule: what an automatic merge changed, by ID, so Undo can revert exactly those
 * items onto a file that changed again, or refuse. No partial undos.
 */
object UndoMerge {
    sealed interface Pos {
        data class Cell(val pageId: String, val x: Int, val y: Int) : Pos
        data class Dock(val index: Int) : Pos
    }

    /** One touched item: where/what it was in the pre-merge file (null = the merge added it) and in the merged result. */
    data class Touched(val id: String, val fileSide: Item?, val filePos: Pos?, val merged: Item?, val mergedPos: Pos?)

    private fun index(c: LauncherConfig): Map<String, Pair<Item, Pos>> {
        val out = LinkedHashMap<String, Pair<Item, Pos>>()
        c.pages.forEach { p -> p.items.forEach { out[it.id] = it to Pos.Cell(p.id, it.x ?: 0, it.y ?: 0) } }
        c.dock.orEmpty().forEachIndexed { i, it -> out[it.id] = it to Pos.Dock(i) }
        return out
    }

    /** Items whose position or content differs between the pre-merge [file] and the [merged] result (includes swap partners). */
    fun record(file: LauncherConfig, merged: LauncherConfig): List<Touched> {
        val a = index(file); val b = index(merged)
        return (a.keys + b.keys).mapNotNull { id ->
            val fa = a[id]; val mb = b[id]
            if (fa != null && mb != null && fa.second == mb.second && fa.first == mb.first) null
            else Touched(id, fa?.first, fa?.second, mb?.first, mb?.second)
        }
    }

    /**
     * Revert [touched] onto [now] (the file as it is at Undo time). Returns null when any touched item
     * was removed, moved or edited since the merge, or a file-side cell/page is no longer free.
     */
    fun revert(now: LauncherConfig, touched: List<Touched>): LauncherConfig? {
        val cur = index(now)
        for (t in touched) {
            val c = cur[t.id]
            if (t.merged == null) { if (c != null) return null; continue } // merge removed it; must still be absent
            if (c == null || c.first != t.merged || c.second != t.mergedPos) return null
        }
        val ids = touched.map { it.id }.toSet()
        var pages = now.pages.map { p -> p.copy(items = p.items.filter { it.id !in ids }) }
        val dock = now.dock.orEmpty().filter { it.id !in ids }.toMutableList()
        val g = now.effectiveGrid
        for (t in touched.filter { it.filePos is Pos.Cell }) {
            val pos = t.filePos as Pos.Cell
            val pi = pages.indexOfFirst { it.id == pos.pageId }.takeIf { it >= 0 } ?: return null
            if (pos.x >= g.columns || pos.y >= g.rows) return null
            if (pages[pi].items.any { (it.x ?: 0) == pos.x && (it.y ?: 0) == pos.y }) return null
            pages = pages.toMutableList().also { it[pi] = it[pi].copy(items = it[pi].items + t.fileSide!!) }
        }
        for (t in touched.filter { it.filePos is Pos.Dock }.sortedBy { (it.filePos as Pos.Dock).index }) {
            dock.add((t.filePos as Pos.Dock).index.coerceAtMost(dock.size), t.fileSide!!)
        }
        return now.copy(pages = pages, dock = if (now.dock == null && dock.isEmpty()) null else dock)
    }
}
