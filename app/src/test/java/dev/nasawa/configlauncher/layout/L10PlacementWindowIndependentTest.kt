package dev.nasawa.configlauncher.layout

import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * test-plan §20 L10 (#197): placement is window-independent.
 *
 * Edits.freeCell / AddToHome / MoveToCell take only the file's grid — no window size — so the
 * same file must yield the same free cell on every matrix point. MoveToDialog steppers are
 * Column 1..columns / Row 1..rows from that grid (UI smoke stays in the matrix when a Move-to
 * sheet dump is present).
 */
class L10PlacementWindowIndependentTest {
    private fun app(id: String, x: Int? = null, y: Int? = null) =
        AppItem(id, "p.$id", x = x, y = y)

    /** Matrix fixture grid: 4×5 (scripts/screen-matrix/launcher.json). */
    private val matrixHome = LauncherConfig(
        grid = Grid(4, 5),
        pages = listOf(
            Page(
                "home",
                items = listOf(
                    app("a", 0, 0), app("b", 1, 0), app("c", 2, 0), app("d", 3, 0),
                    app("e", 0, 1),
                ),
            ),
            Page("second", items = listOf(app("s", 0, 0))),
        ),
        dock = listOf(app("d1")),
    )

    @Test fun freeCellIsStableForSameFile() {
        val page = matrixHome.pages[0]
        assertEquals(Edits.freeCell(matrixHome, page), Edits.freeCell(matrixHome, page))
        assertEquals(1 to 1, Edits.freeCell(matrixHome, page))
    }

    @Test fun addToHomeAgreesAcrossCalls() {
        val r1 = Edits.apply(matrixHome, Edit.AddToHome(app("n")))
        val r2 = Edits.apply(matrixHome, Edit.AddToHome(app("n")))
        assertTrue(r1.applied && r2.applied)
        val n1 = r1.config.pages[0].items.first { it.id == "n" }
        val n2 = r2.config.pages[0].items.first { it.id == "n" }
        assertEquals(n1.x to n1.y, n2.x to n2.y)
        assertEquals(1 to 1, n1.x to n1.y)
    }

    @Test fun moveToCellRespectsFileGridOnly() {
        // Column 1–4 / Row 1–5 ≡ indices 0..3 / 0..4 — window size is never a parameter.
        assertEquals(4, matrixHome.grid!!.columns)
        assertEquals(5, matrixHome.grid!!.rows)
        val moved = Edits.apply(matrixHome, Edit.MoveToCell("a", "home", 3, 4))
        assertTrue(moved.applied)
        val cell = moved.config.pages[0].items.first { it.id == "a" }
        assertEquals(3 to 4, cell.x to cell.y)
        assertTrue("5th column refused", !Edits.apply(matrixHome, Edit.MoveToCell("a", "home", 4, 0)).applied)
        assertTrue("6th row refused", !Edits.apply(matrixHome, Edit.MoveToCell("a", "home", 0, 5)).applied)
    }

    @Test fun restoreUsesNextFreeCellWhenOldTaken() {
        val undo = Edits.undoRemove(matrixHome, "e")
        assertNotNull(undo)
        val removed = Edits.apply(matrixHome, Edit.Remove("e")).config
        val taken = Edits.apply(removed, Edit.MoveToCell("a", "home", 0, 1)).config
        val back = Edits.apply(taken, undo!!).config
        val restored = back.pages[0].items.first { it.id == "e" }
        assertEquals("deterministic free cell from file grid only", 1 to 1, restored.x to restored.y)
    }
}
