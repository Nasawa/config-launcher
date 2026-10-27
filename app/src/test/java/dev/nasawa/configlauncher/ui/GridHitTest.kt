package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #75 item 2 + §1.5 / #187: gutters map to a cell; side margins of a centred block do not. */
class GridHitTest {
    @Test fun cellsAndGutters() {
        // 4 × 5 grid, 1000 × 1500 px: columns are 250 px wide; x = 250 is the gutter between columns 0 and 1.
        assertEquals(0 to 0, GridHit.cell(10f, 10f, 1000f, 1500f, 4, 5))
        assertEquals(1 to 0, GridHit.cell(250f, 10f, 1000f, 1500f, 4, 5))
        assertEquals(0 to 0, GridHit.cell(249.9f, 299.9f, 1000f, 1500f, 4, 5))
        assertEquals(3 to 4, GridHit.cell(1000f, 1500f, 1000f, 1500f, 4, 5))
        assertEquals(0 to 0, GridHit.cell(-5f, -5f, 1000f, 1500f, 4, 5))
    }

    @Test fun centredBlockMarginsAreNotCells() {
        // 960-wide area, 576-wide block centred at x = 192 (Medium/Expanded §1.5).
        val left = 192f
        val top = 0f
        val blockW = 576f
        val blockH = 400f
        assertNull(GridHit.cellInBlock(10f, 10f, left, top, blockW, blockH, 4, 5))
        assertNull(GridHit.cellInBlock(191f, 10f, left, top, blockW, blockH, 4, 5))
        assertNull(GridHit.cellInBlock(192f + 576f, 10f, left, top, blockW, blockH, 4, 5))
        assertEquals(0 to 0, GridHit.cellInBlock(192f + 10f, 10f, left, top, blockW, blockH, 4, 5))
        assertEquals(1 to 0, GridHit.cellInBlock(192f + 144f, 10f, left, top, blockW, blockH, 4, 5))
        assertEquals(3 to 4, GridHit.cellInBlock(192f + 575f, 399f, left, top, blockW, blockH, 4, 5))
    }
}
