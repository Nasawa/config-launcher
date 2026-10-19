package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** #75 item 2: a long-press in a gutter maps to a cell, so the empty-space menu works there too. */
class GridHitTest {
    @Test fun cellsAndGutters() {
        // 4 × 5 grid, 1000 × 1500 px: columns are 250 px wide; x = 250 is the gutter between columns 0 and 1.
        assertEquals(0 to 0, GridHit.cell(10f, 10f, 1000f, 1500f, 4, 5))
        assertEquals(1 to 0, GridHit.cell(250f, 10f, 1000f, 1500f, 4, 5))
        assertEquals(0 to 0, GridHit.cell(249.9f, 299.9f, 1000f, 1500f, 4, 5))
        assertEquals(3 to 4, GridHit.cell(1000f, 1500f, 1000f, 1500f, 4, 5))
        assertEquals(0 to 0, GridHit.cell(-5f, -5f, 1000f, 1500f, 4, 5))
    }
}
