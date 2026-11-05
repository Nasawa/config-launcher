package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Archimedes (#219 nit): a hinge at either window edge is no split, in book as in tabletop. */
class FoldSplitTest {
    private fun book(left: Int, right: Int, rtl: Boolean) = FoldSplit.of(1000, 800, left, 0, right, 800, vertical = true, separating = true, rtl = rtl)

    @Test fun hingeAtTheEndEdgeIsNoSplit() {
        assertNull("LTR, hinge flush with the right edge", book(960, 1000, rtl = false))
        assertNull("RTL, hinge flush with the left edge", book(0, 40, rtl = true))
    }

    @Test fun hingeAtTheStartEdgeIsNoSplit() {
        assertNull(book(0, 40, rtl = false))
        assertNull(book(960, 1000, rtl = true))
    }

    @Test fun centredHingeSplits() {
        assertEquals(FoldSplit.Book(480, 480), book(480, 520, rtl = false))
        assertEquals(FoldSplit.Book(480, 480), book(480, 520, rtl = true))
    }
}
