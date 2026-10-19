package dev.nasawa.configlauncher.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /** Remove an item from home (page, dock or a folder). The app stays installed and in the drawer (ux.md §1.1, §4.2). */
    data class Remove(val id: String) : Edit
    /** Drawer `Add to home` (ux.md §2): first free cell, page 1 onward, else a new page. */
    data class AddToHome(val item: Item) : Edit
    /** Drawer `Hide` (ux.md §2): append the package to `drawer.hidden`. */
    data class Hide(val packageName: String) : Edit
    /** `Unhide` (ux.md §14 R8): drop the package from `drawer.hidden`. Also the Hide snackbar's Undo. */
    data class Unhide(val packageName: String) : Edit
    /**
     * Undo of `Remove from home` (ux.md §14 R3): put [item] (a folder keeps its items) back where it was.
     * [dockIndex] set = dock; else page [pageId] at its own x/y. [siblings] are the IDs of the other items that were on
     * that page, so a *different* page that later reused the ID (R9 `page-N`) isn't mistaken for it: then the page is
     * recreated at [pageIndex] under a free ID. A taken cell falls back to the next free cell on that page, then the
     * first free cell on home, then a new page; it fails only when all 20 pages are full.
     */
    data class Restore(val item: Item, val pageId: String?, val pageIndex: Int, val dockIndex: Int?, val siblings: Set<String> = emptySet()) : Edit
    /**
     * Leaving edit mode (ux.md §4.2, §14 R5): delete the pages in [ids] (the ones that were empty when edit mode
     * ended), only if they still exist and are still empty, and never the last page. A page an agent added
     * meanwhile isn't listed, so a replay onto its file never deletes it.
     */
    data class DropPages(val ids: List<String>) : Edit
    /**
     * #79 (title-bar.md §8): add a new root element, e.g. the title bar preset when the user turns it on. Skipped when
     * the ID is taken, or when the type allows one per file and the file already has one (shown or skipped).
     */
    data class AddElement(val element: JsonObject) : Edit
    /**
     * #79 `SetElement(id, fields)`: set the value at [path] inside element [id] (`["enabled"]`,
     * `["content", "slots", "end"]`, …); null removes the key, and objects left empty are removed too (defaults are
     * omitted). Skipped when the file no longer has that element. Other keys, including ones the phone can't edit,
     * stay as they are.
     */
    data class SetElement(val id: String, val path: List<String>, val value: JsonElement?) : Edit
}

data class ApplyResult(val config: LauncherConfig, val applied: Boolean, val folder: FolderSkip? = null)

/** A folder that wasn't recreated on replay (§17 Q9, ux.md §9.1). [placed] = the one member placed on its own. */
data class FolderSkip(val label: String, val placed: String?)

/** One skipped op; [folder] is set when the op was a folder that wasn't recreated. */
data class Skip(val edit: Edit, val folder: FolderSkip? = null)

data class Replay(val config: LauncherConfig, val kept: Int, val skipped: List<Skip>) {
    /** ux.md §9.1: the folder message is shown only when a skipped folder is the only change. */
    val onlyFolder: FolderSkip? get() = if (kept == 0 && skipped.size == 1) skipped[0].folder else null
}

object Edits {
    /** UI cap for drags into the dock (Q-S6: 5 for the MVP); the file itself may hold up to 7. */
    const val MAX_DOCK = 5

    fun apply(c: LauncherConfig, e: Edit): ApplyResult = when (e) {
        is Edit.MoveToCell -> moveToCell(c, e)
        is Edit.MoveToDock -> moveToDock(c, e)
        is Edit.Add -> add(c, e)
        is Edit.Remove -> remove(c, e)
        is Edit.AddToHome -> addToHome(c, e)
        is Edit.Hide -> hide(c, e)
        is Edit.Unhide -> unhide(c, e)
        is Edit.Restore -> restore(c, e)
        is Edit.DropPages -> dropPages(c, e)
        is Edit.AddElement -> addElement(c, e)
        is Edit.SetElement -> setElement(c, e)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun addElement(c: LauncherConfig, e: Edit.AddElement): ApplyResult {
        val id = e.element.str("id") ?: return ApplyResult(c, false)
        val type = e.element.str("type")
        val els = c.elements.orEmpty()
        if (els.any { it.str("id") == id } || c.allItems().any { it.id == id } || c.pages.any { it.id == id } ||
            c.drawer?.rules.orEmpty().any { (it as? JsonObject)?.str("id") == id }) return ApplyResult(c, false)
        if (dev.nasawa.configlauncher.config.Elements.types[type]?.single == true && els.any { it.str("type") == type }) return ApplyResult(c, false)
        return ApplyResult(c.copy(elements = els + e.element), true)
    }

    private fun setElement(c: LauncherConfig, e: Edit.SetElement): ApplyResult {
        val els = c.elements ?: return ApplyResult(c, false)
        val i = els.indexOfFirst { it.str("id") == e.id }
        if (i < 0 || e.path.isEmpty()) return ApplyResult(c, false)
        val updated = set(els[i], e.path, e.value) ?: return ApplyResult(c, false)
        if (updated == els[i]) return ApplyResult(c, false)
        return ApplyResult(c.copy(elements = els.toMutableList().also { it[i] = updated }), true)
    }

    /** [o] with [path] set to [v] (null = removed, then empty parents dropped); null if a parent isn't an object. */
    private fun set(o: JsonObject, path: List<String>, v: JsonElement?): JsonObject? {
        val k = path[0]
        val next: JsonElement? = if (path.size == 1) v else {
            val child = o[k] ?: if (v == null) return o else JsonObject(emptyMap())
            set(child as? JsonObject ?: return null, path.drop(1), v)?.takeIf { it.isNotEmpty() }
        }
        val m = LinkedHashMap(o)
        if (next == null) m.remove(k) else m[k] = next
        return JsonObject(m)
    }

    /** The [Edit.Restore] that undoes removing top-level item [id] from [c], or null if it isn't on a page or in the dock. */
    fun undoRemove(c: LauncherConfig, id: String): Edit.Restore? = when (val loc = find(c, id)) {
        is Loc.OnPage -> c.pages[loc.page].let { p ->
            Edit.Restore(p.items[loc.index], p.id, loc.page, null, p.items.map { it.id }.filter { it != id }.toSet())
        }
        is Loc.InDock -> Edit.Restore(c.dock!![loc.index], null, 0, loc.index)
        null -> null
    }

    private fun unhide(c: LauncherConfig, e: Edit.Unhide): ApplyResult {
        val d = c.drawer ?: return ApplyResult(c, false)
        val hidden = d.hidden.orEmpty()
        if (e.packageName !in hidden) return ApplyResult(c, false)
        return ApplyResult(c.copy(drawer = d.copy(hidden = hidden - e.packageName)), true)
    }

    private fun restore(c: LauncherConfig, e: Edit.Restore): ApplyResult {
        if (c.allItems().any { it.id == e.item.id }) return ApplyResult(c, false)   // already back
        if (e.dockIndex != null) add(c, Edit.Add(e.item, null, index = e.dockIndex)).takeIf { it.applied }?.let { return it }
        val page = c.pages.firstOrNull { it.id == e.pageId }
        // Same page: it still holds one of the folder's old neighbours, or it was alone there and the page is still empty.
        val same = page != null && (page.items.any { it.id in e.siblings } || e.siblings.isEmpty() && page.items.isEmpty())
        if (same) add(c, Edit.Add(e.item, page!!.id, e.item.x ?: 0, e.item.y ?: 0)).takeIf { it.applied }?.let { return it }
        if (page == null || !same) {
            if (c.pages.size < MAX_PAGES) {
                val taken = c.pages.map { it.id }.toSet()
                val id = e.pageId?.takeIf { it !in taken } ?: generateSequence(1) { it + 1 }.map { "page-$it" }.first { it !in taken }
                val base = c.copy(pages = c.pages.toMutableList().also { it.add(e.pageIndex.coerceIn(0, it.size), Page(id)) })
                add(base, Edit.Add(e.item, id, e.item.x ?: 0, e.item.y ?: 0)).takeIf { it.applied }?.let { return it }
            }
        }
        val r = addToHome(c, Edit.AddToHome(e.item))
        return if (r.applied) r else ApplyResult(c, false)
    }

    private fun dropPages(c: LauncherConfig, e: Edit.DropPages): ApplyResult {
        val keep = dev.nasawa.configlauncher.config.Elements.pagesWithElements(c)
        val drop = c.pages.filter { it.id in e.ids && it.items.isEmpty() && it.id !in keep }.map { it.id }.toSet()
        var kept = c.pages.filter { it.id !in drop }
        if (kept.isEmpty()) kept = c.pages.take(1)
        return ApplyResult(c.copy(pages = kept), kept.size != c.pages.size)
    }

    /** IDs of the pages that are empty now: what [Edit.DropPages] gets when edit mode ends. */
    fun emptyPageIds(c: LauncherConfig): List<String> {
        val keep = dev.nasawa.configlauncher.config.Elements.pagesWithElements(c)   // #79: element cells aren't empty
        return c.pages.filter { it.items.isEmpty() && it.id !in keep }.map { it.id }
    }

    const val MAX_PAGES = 20   // schema pages.maxItems

    private fun addToHome(c: LauncherConfig, e: Edit.AddToHome): ApplyResult {
        for (p in c.pages) {
            if (freeCell(c, p) == null) continue
            add(c, Edit.Add(e.item, p.id)).takeIf { it.applied }?.let { return it }   // skips pages that already have it
        }
        if (c.pages.size >= MAX_PAGES) return ApplyResult(c, false)
        val taken = c.pages.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "page-$it" }.first { it !in taken }   // §14 R9: lowest unused N
        return add(c.copy(pages = c.pages + Page(id)), Edit.Add(e.item, id)).let { if (it.applied) it else ApplyResult(c, false) }
    }

    private fun hide(c: LauncherConfig, e: Edit.Hide): ApplyResult {
        val d = c.drawer ?: Drawer()
        val hidden = d.hidden.orEmpty()
        if (e.packageName in hidden) return ApplyResult(c, false)
        return ApplyResult(c.copy(drawer = d.copy(hidden = hidden + e.packageName)), true)
    }

    /** §3: a folder with one app left stays a folder; a folder with zero apps is deleted. */
    private fun remove(c: LauncherConfig, e: Edit.Remove): ApplyResult {
        fun strip(items: List<Item>): List<Item> = items.mapNotNull { i ->
            when {
                i.id == e.id -> null
                i is FolderItem -> i.copy(items = strip(i.items)).takeIf { it.items.isNotEmpty() || i.items.isEmpty() }
                else -> i
            }
        }
        val out = c.copy(pages = c.pages.map { it.copy(items = strip(it.items)) }, dock = c.dock?.let(::strip))
        return ApplyResult(out, out != c)
    }

    /** Replay ops in order; skipped ops (target gone, no room, duplicate, folder not recreated) are listed. */
    fun replay(c: LauncherConfig, ops: List<Edit>): Replay {
        var cur = c; val skipped = mutableListOf<Skip>()
        for (op in ops) {
            val r = apply(cur, op)
            cur = r.config   // a skipped folder may still have placed its last member
            if (!r.applied) skipped += Skip(op, r.folder)
        }
        return Replay(cur, ops.size - skipped.size, skipped)
    }

    private fun Item.displayName(): String = label ?: when (this) {
        is AppItem -> packageName
        is ShortcutItem -> shortcutId
        is FolderItem -> id
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
        // #79 Stage 1: cells reserved by grid-placed elements (even skipped ones) are never free.
        val used = page.items.map { it.x to it.y }.toSet() + dev.nasawa.configlauncher.config.Elements.reserved(c, page.id)
        val cells = (0 until g.rows).flatMap { y -> (0 until g.columns).map { x -> x to y } }
        val start = cells.indexOf(fromX to fromY).coerceAtLeast(0)
        return (cells.drop(start) + cells.take(start)).firstOrNull { it !in used }
    }

    private fun moveToCell(c: LauncherConfig, e: Edit.MoveToCell): ApplyResult {
        val g = c.effectiveGrid
        if (e.x !in 0 until g.columns || e.y !in 0 until g.rows) return ApplyResult(c, false)
        if ((e.x to e.y) in dev.nasawa.configlauncher.config.Elements.reserved(c, e.pageId)) return ApplyResult(c, false)   // #79 Stage 1
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
        if (item is FolderItem) {
            // §17 Q9: members the file already has elsewhere were moved there; the folder is built from the rest.
            val inFile = c.allItems().map { it.id }.toSet()
            val left = item.items.filter { it.id !in inFile }
            if (left.size < item.items.size && left.size < 2) {
                val name = item.displayName()
                val one = left.singleOrNull() ?: return ApplyResult(c, false, FolderSkip(name, null))
                val placed = add(c, e.copy(item = one))
                return ApplyResult(placed.config, false, FolderSkip(name, if (placed.applied) one.displayName() else null))
            }
            if (left.size < item.items.size) return add(c, e.copy(item = item.copy(items = left)))
        }
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

    /** Generated-ID prefixes (README "ID prefixes", ARCHITECTURE.md §20.4). Pages use readable "page-N" (§14 R9). */
    enum class IdKind(val prefix: String) { APP("a-"), SHORTCUT("s-"), FOLDER("f-"), RULE("r-") }

    /** New stable app ID like "a-chrome-3f2k" (schema pattern ^[a-z0-9][a-z0-9_-]{0,63}$). */
    fun newId(c: LauncherConfig, packageName: String, random: () -> Int = { (0..Int.MAX_VALUE).random() }): String =
        newId(c, IdKind.APP, packageName.substringAfterLast('.'), random)

    /**
     * New ID "<prefix><hint>-<random>", e.g. "f-games-3f2k" or "s-chrome-new-tab-1a2b". [hint] is reduced to the ID
     * alphabet; an empty hint gives "f-3f2k". Unique across the whole file: items, pages and drawer rules.
     */
    fun newId(c: LauncherConfig, kind: IdKind, hint: String, random: () -> Int = { (0..Int.MAX_VALUE).random() }): String {
        val slug = hint.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_').take(40)
        val base = kind.prefix + (if (slug.isEmpty()) "" else "$slug-")
        val taken = usedIds(c)
        while (true) {
            val id = base + Integer.toString(random() and 0xfffff, 36)
            if (id !in taken) return id
        }
    }

    private fun usedIds(c: LauncherConfig): Set<String> =
        c.allItems().map { it.id }.toSet() + c.pages.map { it.id } +
            c.drawer?.rules.orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
}
