package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.model.ResolvedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #78: page list rows. */
class PageListTest {
    @Test fun rowsInOrderWithCurrentMarked() {
        val pages = listOf(ResolvedPage("home", "Home", emptyList()), ResolvedPage("p2", null, emptyList()), ResolvedPage("p3", "  ", emptyList()))
        assertEquals(
            listOf(PageRow(1, "Home", false), PageRow(2, null, true), PageRow(3, null, false)),
            PageList.rows(pages, current = 1),
        )
    }

    private fun page(t: String?) = ResolvedPage("x", t, emptyList())

    @Test fun labelVisibilityAndTapTarget() {
        assertEquals(false, PageList.showLabel(listOf(page(null))))
        assertEquals(true, PageList.showLabel(listOf(page("Home"))))
        assertEquals(false, PageList.tappable(listOf(page("Home"))))
        assertEquals(true, PageList.showLabel(listOf(page(null), page(null))))
        assertEquals(true, PageList.tappable(listOf(page(null), page(null))))
    }

    @Test fun focusOnlyOnceTheSheetIsFullyOpen() {   // #99
        assertTrue(PageList.focusWhen(currentExpanded = true, targetExpanded = true))
        assertFalse("still opening", PageList.focusWhen(currentExpanded = false, targetExpanded = true))
        assertFalse("closing", PageList.focusWhen(currentExpanded = true, targetExpanded = false))
        assertFalse(PageList.focusWhen(currentExpanded = false, targetExpanded = false))
    }
}
