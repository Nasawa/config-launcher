package dev.nasawa.configlauncher.model

import org.junit.Assert.*
import org.junit.Test

class UndoMergeTest {
    private fun app(id: String, x: Int, y: Int) = AppItem(id, "p.$id", x = x, y = y)
    private val file = LauncherConfig(pages = listOf(Page("home", items = listOf(app("a", 0, 0), app("b", 1, 0)))), dock = listOf(AppItem("c", "p.c")))

    @Test fun swapPartnersAreTouched() {
        val merged = Edits.apply(file, Edit.MoveToCell("a", "home", 1, 0)).config
        val t = UndoMerge.record(file, merged)
        assertEquals(setOf("a", "b"), t.map { it.id }.toSet())
        assertEquals(file.pages[0].items.toSet(), UndoMerge.revert(merged, t)!!.pages[0].items.toSet())
    }

    @Test fun addedItemIsRemovedAndDockOrderRestored() {
        val merged = Edits.apply(file, Edit.MoveToDock("a", 0)).config
        val r = UndoMerge.revert(merged, UndoMerge.record(file, merged))!!
        assertEquals(listOf("c"), r.dock!!.map { it.id })
        assertEquals(0 to 0, r.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
    }

    @Test fun refusesWhenTargetCellTaken() {
        val merged = Edits.apply(file, Edit.MoveToDock("a", 0)).config
        val t = UndoMerge.record(file, merged)
        val now = merged.copy(pages = listOf(merged.pages[0].copy(items = merged.pages[0].items + app("z", 0, 0))))
        assertNull(UndoMerge.revert(now, t))
    }
}
