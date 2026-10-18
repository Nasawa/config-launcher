package dev.nasawa.configlauncher.model

/**
 * Local edits are stored as ID-based operations (ARCHITECTURE.md §6) so "Keep mine" can replay them
 * onto an externally edited file instead of overwriting it.
 */
sealed interface Edit {
    /** Move a top-level item (page or dock) to cell ([x],[y]) on [pageId]. An occupant swaps into the old cell when possible. */
    data class MoveToCell(val id: String, val pageId: String, val x: Int, val y: Int) : Edit
    /** Move a top-level item into the dock at [index]. */
    data class MoveToDock(val id: String, val index: Int) : Edit
    /** Add a new item (e.g. from Unsorted). [pageId] null = dock at [index]; else first free cell from ([x],[y]). */
    data class Add(val item: Item, val pageId: String?, val x: Int = 0, val y: Int = 0, val index: Int = Int.MAX_VALUE) : Edit
}

data class ApplyResult(val config: LauncherConfig, val applied: Boolean)

object Edits {
    /** UI cap for drags into the dock (Q-S6: 5 for the MVP); the file itself may hold up to 7. */
    const val MAX_DOCK = 5

    fun apply(c: LauncherConfig, e: Edit): ApplyResult = when (e) {
        is Edit.MoveToCell -> moveToCell(c, e)
        is Edit.MoveToDock -> moveToDock(c, e)
        is Edit.Add -> add(c, e)
    }

    /** Replay ops in order; returns the result and how many ops were skipped (target gone, no room, duplicate). */
    fun replay(c: LauncherConfig, ops: List<Edit>): Pair<LauncherConfig, Int> {
        var cur = c; var skipped = 0
        for (op in ops) { val r = apply(cur, op); if (r.applied) cur = r.config else skipped++ }
        return cur to skipped
    }

    private sealed interface Loc { data class OnPage(val page: Int, val index: Int) : Loc; data class InDock(val index: Int) : Loc }

    private fun find(c: LauncherConfig, id: String): Loc? {
        c.pages.forEachIndexed { p, page -> page.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return Loc.OnPage(p, it) } }
        c.dock.orEmpty().indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return Loc.InDock(it) }
        return null
    }

    private fun remove(c: LauncherConfig, loc: Loc): Pair<LauncherConfig, Item> = when (loc) {
        is Loc.OnPage -> {
            val page = c.pages[loc.page]
            val item = page.items[loc.index]
            c.copy(pages = c.pages.toMutableList().also { it[loc.page] = page.copy(items = page.items - item) }) to item
        }
        is Loc.InDock -> {
            val item = c.dock!![loc.index]
            c.copy(dock = c.dock.filterIndexed { i, _ -> i != loc.index }) to item
        }
    }

    fun freeCell(c: LauncherConfig, page: Page, fromX: Int = 0, fromY: Int = 0): Pair<Int, Int>? {
        val g = c.effectiveGrid
        val used = page.items.map { it.x to it.y }.toSet()
        val cells = (0 until g.rows).flatMap { y -> (0 until g.columns).map { x -> x to y } }
        val start = cells.indexOf(fromX to fromY).coerceAtLeast(0)
        return (cells.drop(start) + cells.take(start)).firstOrNull { it !in used }
    }

    private fun moveToCell(c: LauncherConfig, e: Edit.MoveToCell): ApplyResult {
        val g = c.effectiveGrid
        if (e.x !in 0 until g.columns || e.y !in 0 until g.rows) return ApplyResult(c, false)
        val loc = find(c, e.id) ?: return ApplyResult(c, false)
        val pIdx = c.pages.indexOfFirst { it.id == e.pageId }.takeIf { it >= 0 } ?: return ApplyResult(c, false)
        val (without, item) = remove(c, loc)
        val page = without.pages[pIdx]
        val occupant = page.items.firstOrNull { it.x == e.x && it.y == e.y }
        var items = page.items
        var dock = without.dock
        if (occupant != null) {
            items = items - occupant
            when {
                // Same page: swap into the dragged item's old cell.
                loc is Loc.OnPage && loc.page == pIdx -> items = items + occupant.at(item.x, item.y)
                // From the dock: the occupant takes the dock slot.
                loc is Loc.InDock ->
                    dock = dock.orEmpty().toMutableList().also { it.add(loc.index.coerceAtMost(it.size), occupant.at(null, null)) }
                else -> {
                    val free = freeCell(c, page.copy(items = items + item.at(e.x, e.y)), e.x, e.y) ?: return ApplyResult(c, false)
                    items = items + occupant.at(free.first, free.second)
                }
            }
        }
        items = items + item.at(e.x, e.y)
        val pages = without.pages.toMutableList().also { it[pIdx] = page.copy(items = items.sortedWith(compareBy({ it.y }, { it.x }))) }
        val out = without.copy(pages = pages, dock = dock)
        return ApplyResult(out, out != c)
    }

    private fun moveToDock(c: LauncherConfig, e: Edit.MoveToDock): ApplyResult {
        val loc = find(c, e.id) ?: return ApplyResult(c, false)
        if (loc is Loc.OnPage && c.dock.orEmpty().size >= MAX_DOCK) return ApplyResult(c, false)
        val (without, item) = remove(c, loc)
        val dock = without.dock.orEmpty().toMutableList()
        dock.add(e.index.coerceIn(0, dock.size), item.at(null, null))
        val out = without.copy(dock = dock)
        return ApplyResult(out, out != c)
    }

    private fun add(c: LauncherConfig, e: Edit.Add): ApplyResult {
        val item = e.item
        if (c.allItems().any { it.id == item.id }) return ApplyResult(c, false)
        fun same(i: Item) = i is AppItem && item is AppItem && i.packageName == item.packageName &&
            (i.activity ?: item.activity) == (item.activity ?: i.activity) && (i.profile ?: Profile.PERSONAL) == (item.profile ?: Profile.PERSONAL)
        if (e.pageId == null) {
            val dock = c.dock.orEmpty()
            if (dock.size >= MAX_DOCK || dock.any(::same)) return ApplyResult(c, false)
            return ApplyResult(c.copy(dock = dock.toMutableList().also { it.add(e.index.coerceIn(0, it.size), item.at(null, null)) }), true)
        }
        val pIdx = c.pages.indexOfFirst { it.id == e.pageId }.takeIf { it >= 0 } ?: return ApplyResult(c, false)
        val page = c.pages[pIdx]
        if (page.items.any(::same)) return ApplyResult(c, false)
        val (x, y) = freeCell(c, page, e.x, e.y) ?: return ApplyResult(c, false)
        val pages = c.pages.toMutableList().also { it[pIdx] = page.copy(items = (page.items + item.at(x, y)).sortedWith(compareBy({ it.y }, { it.x }))) }
        return ApplyResult(c.copy(pages = pages), true)
    }

    /** New stable ID like "a-chrome-3f2k" (schema pattern ^[a-z0-9][a-z0-9_-]{0,63}$). */
    fun newId(c: LauncherConfig, packageName: String, random: () -> Int = { (0..Int.MAX_VALUE).random() }): String {
        val base = "a-" + packageName.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9_-]"), "").take(40)
        val taken = c.allItems().map { it.id }.toSet()
        while (true) {
            val id = base + "-" + Integer.toString(random() and 0xfffff, 36)
            if (id !in taken) return id
        }
    }
}
