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

    @Test fun generatedIdsUsePrefixes() {   // §20.4
        val pat = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        val f = Edits.newId(base, Edits.IdKind.FOLDER, "Games & Fun!")
        val s = Edits.newId(base, Edits.IdKind.SHORTCUT, "Chrome: New tab")
        val r = Edits.newId(base, Edits.IdKind.RULE, "")
        assertTrue(f, f.startsWith("f-games-fun-") && pat.matches(f))
        assertTrue(s, s.startsWith("s-chrome-new-tab-") && pat.matches(s))
        assertTrue(r, Regex("^r-[a-z0-9]+$").matches(r))
        assertTrue(pat.matches(Edits.newId(base, Edits.IdKind.FOLDER, "x".repeat(200))))
    }

    @Test fun generatedIdAvoidsPageAndRuleIds() {
        val rule = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("r-1")) }
        val c = base.copy(pages = base.pages + Page("f-0"), drawer = Drawer(null, null, listOf(rule)))
        val seq = { start: Int -> var n = start; { n++ } }
        assertEquals("f-1", Edits.newId(c, Edits.IdKind.FOLDER, "", seq(0)))   // f-0 is a page
        assertEquals("r-2", Edits.newId(c, Edits.IdKind.RULE, "", seq(1)))     // r-1 is a rule
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

    // #53 item sheet: Remove from home.
    @Test fun removeFromPageDockAndFolder() {
        val c = base.copy(pages = listOf(base.pages[0].copy(items = listOf(app("a", 0, 0), FolderItem("f", "F", listOf(app("m"), app("n")), 1, 0)))) + base.pages[1])
        assertFalse(Edit.Remove("a").on(c).config.allItems().any { it.id == "a" })
        val r = Edit.Remove("m").on(c)
        assertTrue(r.applied)
        assertEquals(listOf("n"), (r.config.allItems().first { it.id == "f" } as FolderItem).items.map { it.id })
        assertFalse("a folder with zero apps is deleted (§3)", Edit.Remove("n").on(r.config).config.allItems().any { it.id == "f" })
        assertFalse(Edit.Remove("d1").on(c).config.dock!!.any { it.id == "d1" })
        assertFalse("unknown id is skipped on replay", Edit.Remove("zzz").on(c).applied)
    }

    // #53 drawer actions.
    @Test fun addToHomeUsesFirstFreeCellFromPageOne() {
        val r = Edit.AddToHome(app("n")).on()
        assertEquals(Triple("home", 2, 0), r.config.cell("n"))
    }

    @Test fun addToHomeSpillsToLaterPageThenNewPage() {
        val full = base.copy(pages = listOf(Page("home", items = (0 until 6).map { app("h$it", it % 3, it / 3) })))
        val r = Edit.AddToHome(app("n")).on(full)
        assertTrue(r.applied)
        assertEquals(2, r.config.pages.size)
        assertEquals("§14 R9: lowest unused N", Triple("page-1", 0, 0), r.config.cell("n"))
        val atCap = full.copy(pages = (1..Edits.MAX_PAGES).map { Page("p$it", items = (0 until 6).map { j -> app("p${it}x$j", j % 3, j / 3) }) })
        assertFalse(Edit.AddToHome(app("n")).on(atCap).applied)
    }

    @Test fun hideAppendsOnce() {
        val r = Edit.Hide("p.x").on()
        assertEquals(listOf("p.x"), r.config.drawer!!.hidden)
        assertFalse(Edit.Hide("p.x").on(r.config).applied)
    }

    // ux.md §14 rulings.
    private fun folder(x: Int, y: Int) = FolderItem("f", label = "Work", x = x, y = y, items = listOf(app("f1"), app("f2")))

    @Test fun removeFolderThenRestorePutsItBackWithItsItems() {
        val c = base.copy(pages = listOf(base.pages[0].copy(items = base.pages[0].items + folder(2, 1)), base.pages[1]))
        val undo = Edits.undoRemove(c, "f")!!
        val removed = Edit.Remove("f").on(c).config
        val r = undo.on(removed)
        assertTrue(r.applied)
        assertEquals(c, r.config)
        assertFalse("already back: Undo is skipped, not duplicated", undo.on(r.config).applied)
    }

    @Test fun restoreRecreatesADeletedPageAtItsIndex() {
        val c = base.copy(pages = listOf(base.pages[0], Page("two", items = listOf(folder(1, 1)))))
        val undo = Edits.undoRemove(c, "f")!!
        val removed = Edit.Remove("f").on(c).config
        val gone = Edit.DropPages(Edits.emptyPageIds(removed)).on(removed).config
        assertEquals(listOf("home"), gone.pages.map { it.id })
        val back = undo.on(gone).config
        assertEquals(listOf("home", "two"), back.pages.map { it.id })
        assertEquals(Triple("two", 1, 1), back.cell("f"))
    }

    @Test fun restoreIntoATakenCellUsesTheNextFreeOne() {
        val c = base.copy(pages = listOf(base.pages[0].copy(items = base.pages[0].items + folder(2, 0))))
        val undo = Edits.undoRemove(c, "f")!!
        val taken = Edit.MoveToCell("a", "home", 2, 0).on(Edit.Remove("f").on(c).config).config
        assertEquals("next free cell in reading order (Edits.freeCell)", Triple("home", 0, 1), undo.on(taken).config.cell("f"))
    }

    @Test fun restoreDockItemAtItsIndex() {
        val undo = Edits.undoRemove(base, "d1")!!
        assertEquals(listOf("d1", "d2"), undo.on(Edit.Remove("d1").on().config).config.dock!!.map { it.id })
    }

    @Test fun dropPagesKeepsTheLastOne() {
        val r = Edit.DropPages(Edits.emptyPageIds(base)).on()
        assertTrue(r.applied); assertEquals(listOf("home"), r.config.pages.map { it.id })
        assertFalse("nothing to drop", Edit.DropPages(Edits.emptyPageIds(r.config)).on(r.config).applied)
        val allEmpty = base.copy(pages = listOf(Page("x"), Page("y")))
        assertEquals(listOf("x"), Edit.DropPages(listOf("x", "y")).on(allEmpty).config.pages.map { it.id })
    }

    // Grace's #70 blocker: an empty page an agent adds while DropPages is pending must survive the replay.
    @Test fun dropPagesReplayKeepsAnAgentsNewEmptyPage() {
        val ours = Edit.DropPages(Edits.emptyPageIds(base))            // ["two"], recorded at edit-mode exit
        val file = base.copy(pages = base.pages + Page("agent-empty", title = "Agent"))
        val r = Edits.replay(file, listOf(ours))
        assertEquals(listOf("home", "agent-empty"), r.config.pages.map { it.id })
    }

    @Test fun dropPagesSkipsAPageThatGotItemsMeanwhile() {
        val ours = Edit.DropPages(listOf("two"))
        val file = base.copy(pages = listOf(base.pages[0], Page("two", items = listOf(app("z", 0, 0)))))
        assertFalse(ours.on(file).applied)
        assertEquals(listOf("home", "two"), Edits.replay(file, listOf(ours)).config.pages.map { it.id })
    }

    // Grace: R9 reuses the lowest unused page-N; a page that took the dropped page's ID must not get the folder.
    @Test fun restoreDoesNotUseADifferentPageThatReusedTheId() {
        val c = base.copy(pages = listOf(base.pages[0], Page("page-1", items = listOf(folder(1, 1)))))
        val undo = Edits.undoRemove(c, "f")!!
        val removed = Edit.Remove("f").on(c).config
        val dropped = Edit.DropPages(Edits.emptyPageIds(removed)).on(removed).config
        val full = dropped.copy(pages = listOf(dropped.pages[0].copy(items = (0 until 6).map { app("h$it", it % 3, it / 3) })))
        val reused = Edit.AddToHome(app("n")).on(full).config
        assertEquals(listOf("home", "page-1"), reused.pages.map { it.id })   // the new page took the old ID
        val back = undo.on(reused).config
        assertEquals(3, back.pages.size)
        val folderPage = back.pages.first { p -> p.items.any { it.id == "f" } }
        assertEquals("recreated at its old index", 1, back.pages.indexOf(folderPage))
        assertTrue("not on the reused page", folderPage.items.none { it.id == "n" })
        assertEquals(Triple(folderPage.id, 1, 1), back.cell("f"))
    }

    @Test fun restoreFailsOnlyWhenAllPagesAreFull() {
        val c = base.copy(pages = listOf(base.pages[0].copy(items = base.pages[0].items + folder(2, 1))))
        val undo = Edits.undoRemove(c, "f")!!
        val atCap = base.copy(pages = (1..Edits.MAX_PAGES).map { Page("p$it", items = (0 until 6).map { j -> app("p${it}x$j", j % 3, j / 3) }) })
        assertFalse(undo.on(atCap).applied)
        val oneFree = atCap.copy(pages = atCap.pages.dropLast(1) + atCap.pages.last().let { it.copy(items = it.items.drop(1)) })
        assertTrue("falls back to any free cell", undo.on(oneFree).applied)
    }

    @Test fun unhideUndoesHide() {
        val hidden = Edit.Hide("p.x").on().config
        val r = Edit.Unhide("p.x").on(hidden)
        assertTrue(r.applied); assertEquals(emptyList<String>(), r.config.drawer!!.hidden)
        assertFalse(Edit.Unhide("p.x").on(r.config).applied)
        assertFalse(Edit.Unhide("p.x").on().applied)
    }
}
