package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.model.ResolvedPage
import org.junit.Assert.assertEquals
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
}
