package dev.nasawa.configlauncher.model

import org.junit.Assert.*
import org.junit.Test

class EditsTest {
    private fun app(id: String, x: Int? = null, y: Int? = null) = AppItem(id, "p.$id", x = x, y = y)
    private val base = LauncherConfig(
        grid = Grid(3, 2),
        pages = listOf(Page("home", items = listOf(app("a", 0, 0), app("b", 1, 0))), Page("two", items = emptyList())),
        dock = listOf(app("d1"), app("d2")),
    )
    private fun Edit.on(c: LauncherConfig = base) = Edits.apply(c, this)
    private fun LauncherConfig.cell(id: String) = pages.flatMap { p -> p.items.map { p.id to it } }.first { it.second.id == id }.let { Triple(it.first, it.second.x, it.second.y) }

    @Test fun moveToEmptyCell() {
        val r = Edit.MoveToCell("a", "home", 2, 1).on()
        assertTrue(r.applied)
        assertEquals(Triple("home", 2, 1), r.config.cell("a"))
    }

    @Test fun moveOntoOccupiedSwaps() {
        val c = Edit.MoveToCell("a", "home", 1, 0).on().config
        assertEquals(Triple("home", 1, 0), c.cell("a"))
        assertEquals(Triple("home", 0, 0), c.cell("b"))
    }

    @Test fun moveToOtherPage() = assertEquals(Triple("two", 0, 1), Edit.MoveToCell("b", "two", 0, 1).on().config.cell("b"))

    @Test fun dockToPageClearsAndSetsXY() {
        val c = Edit.MoveToCell("d1", "home", 2, 0).on().config
        assertEquals(listOf("d2"), c.dock!!.map { it.id })
        assertEquals(Triple("home", 2, 0), c.cell("d1"))
    }

    @Test fun dockToOccupiedCellSwapsIntoDock() {
        val c = Edit.MoveToCell("d2", "home", 0, 0).on().config
        assertEquals(listOf("d1", "a"), c.dock!!.map { it.id })
        assertNull(c.dock!![1].x)
    }

    @Test fun pageToDockReorder() {
        val c = Edit.MoveToDock("a", 1).on().config
        assertEquals(listOf("d1", "a", "d2"), c.dock!!.map { it.id })
        assertNull(c.dock!![1].x)
        assertEquals(listOf("b"), c.pages[0].items.map { it.id })
        assertEquals(listOf("d2", "d1"), Edit.MoveToDock("d2", 0).on().config.dock!!.map { it.id })
    }

    @Test fun unknownIdOrOutOfGridIsSkipped() {
        assertFalse(Edit.MoveToCell("zzz", "home", 0, 0).on().applied)
        assertFalse(Edit.MoveToCell("a", "home", 3, 0).on().applied)
        assertFalse(Edit.MoveToCell("a", "nope", 0, 0).on().applied)
    }

    @Test fun addToFirstFreeCellAndDedup() {
        val r = Edit.Add(app("n"), "home").on()
        assertEquals(Triple("home", 2, 0), r.config.cell("n"))
        assertFalse(Edit.Add(AppItem("n2", "p.a"), "home").on().applied)
        assertEquals(listOf("d1", "d2", "n"), Edit.Add(app("n"), null).on().config.dock!!.map { it.id })
    }

    @Test fun replayCountsSkipped() {
        val file = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("b", 1, 0)))) + base.pages[1])
        val r = Edits.replay(file, listOf(Edit.MoveToCell("a", "home", 2, 1), Edit.MoveToDock("b", 0)))
        assertEquals(1, r.skipped.size); assertEquals(1, r.kept)
        assertNull(r.skipped[0].folder); assertNull(r.onlyFolder)
        assertEquals("b", r.config.dock!!.first().id)
    }

    @Test fun newIdMatchesSchemaPattern() {
        val id = Edits.newId(base, "com.Google.Android.Chrome!")
        assertTrue(id, Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(id))
        assertTrue(id.startsWith("a-chrome-"))
    }

    // §17 Q9 / ux.md §9.1: folder info from replay.
    private fun folder(vararg ids: String) = FolderItem("f", "Work", ids.map { app(it) })

    @Test fun folderWithOneMemberLeftPlacesItAlone() {
        // Members b and c are already elsewhere in the file (moved there), so only n is left.
        val file = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("b", 1, 0), app("c", 2, 0)))) + base.pages[1])
        val r = Edits.replay(file, listOf(Edit.Add(folder("n", "b", "c"), "home")))
        assertEquals(0, r.kept)
        assertEquals("unlabelled app falls back to its package", FolderSkip("Work", "p.n"), r.onlyFolder)
        assertTrue(r.config.allItems().any { it.id == "n" })
        assertFalse(r.config.allItems().any { it.id == "f" })
    }

    @Test fun folderWithNoMembersLeft() {
        val file = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("b", 1, 0), app("c", 2, 0)))) + base.pages[1])
        val r = Edits.replay(file, listOf(Edit.Add(folder("b", "c"), "home")))
        assertEquals(FolderSkip("Work", null), r.onlyFolder)
    }

    @Test fun folderIsFoldedIntoCountsWhenNotTheOnlyChange() {
        val file = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("b", 1, 0), app("c", 2, 0)))) + base.pages[1])
        val r = Edits.replay(file, listOf(Edit.Add(folder("b", "c"), "home"), Edit.MoveToDock("b", 0)))
        assertEquals(1, r.kept); assertEquals(1, r.skipped.size)
        assertNotNull(r.skipped[0].folder)
        assertNull("folder message only when it's the only change", r.onlyFolder)
    }

    @Test fun folderWithTwoSurvivorsIsBuiltFromThem() {
        val file = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("b", 1, 0)))) + base.pages[1])
        val r = Edits.replay(file, listOf(Edit.Add(folder("n", "m", "b"), "home")))
        assertEquals(1, r.kept)
        assertEquals(listOf("n", "m"), (r.config.allItems().first { it.id == "f" } as FolderItem).items.map { it.id })
    }
}
