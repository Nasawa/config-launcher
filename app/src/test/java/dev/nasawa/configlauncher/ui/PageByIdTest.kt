package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #188 / ux.md §1.5 "what's kept": the fallback rule, and a config reload (the page list changing while home is
 * on screen) keeping the page by `id`, in LTR and RTL. Real activity recreation is [PageByIdRecreateTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PageByIdTest {
    @get:Rule val rule = createComposeRule()

    // ---- the pure rule ----

    @Test fun targetFindsTheIdWhereverItMoved() {
        assertEquals(0, PageById.target(listOf("second", "home", "third"), "second", 1))
        assertEquals(2, PageById.target(listOf("home", "third", "second"), "second", 1))
    }

    @Test fun targetClampsTheOldIndexWhenTheIdIsGone() {
        assertEquals(1, PageById.target(listOf("home", "third"), "second", 1)) // the page that took its place
        assertEquals(1, PageById.target(listOf("home", "second"), "third", 2)) // was last: the new last page
        assertEquals(0, PageById.target(listOf("home"), "gone", 5))
    }

    @Test fun targetWithNothingSavedOrNoPages() {
        assertEquals(0, PageById.target(listOf("home", "second"), null, 0))
        assertEquals(0, PageById.target(emptyList(), "home", 3))
    }

    // ---- the composable, on a config reload ----

    private var ids by mutableStateOf(listOf<String>())
    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope

    private fun home(dir: LayoutDirection, keyed: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides dir) {
                pager = rememberPagerById(ids)
                scope = rememberCoroutineScope()
                HorizontalPager(pager, Modifier.size(400.dp, 600.dp), key = if (keyed) { i -> ids.getOrNull(i) ?: i } else null) { i ->
                    Text("page-" + ids.getOrNull(i), Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun goTo(i: Int) {
        rule.runOnIdle { scope.launch { pager.scrollToPage(i) } }
        rule.waitForIdle()
    }

    private fun shows(id: String, index: Int) {
        rule.onNodeWithText("page-$id").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(index, pager.currentPage)
            assertEquals(index, pager.settledPage) // dots and label read settledPage
        }
    }

    @Test fun firstLaunchStartsOnPageOne() {
        ids = listOf("home", "second")
        home(LayoutDirection.Ltr)
        shows("home", 0)
    }

    @Test fun reloadKeepsPageIdWhenPagesReorderAndShrink() {
        ids = listOf("home", "second", "third")
        home(LayoutDirection.Ltr)
        goTo(2); shows("third", 2)
        // [third, home]: the count drops to 2, so an index-only pager would clamp to 1 (`home`).
        rule.runOnIdle { ids = listOf("third", "home") }
        rule.waitForIdle()
        shows("third", 0)
        rule.runOnIdle { ids = listOf("home", "new", "third") }
        rule.waitForIdle()
        shows("third", 2)
    }

    @Test fun reloadFallsBackWhenPageIdRemoved() {
        ids = listOf("home", "second", "third")
        home(LayoutDirection.Rtl)
        goTo(1); shows("second", 1)
        rule.runOnIdle { ids = listOf("home", "third") }
        rule.waitForIdle()
        shows("third", 1)
        rule.runOnIdle { ids = listOf("home") }
        rule.waitForIdle()
        shows("home", 0)
    }

    /** Doesn't lean on HorizontalPager's key matching: without keys a reorder still keeps the id. */
    @Test fun reloadKeepsPageIdWithoutPagerKeys() {
        ids = listOf("home", "second", "third")
        home(LayoutDirection.Ltr, keyed = false)
        goTo(1); shows("second", 1)
        rule.runOnIdle { ids = listOf("second", "third", "home") }
        rule.waitForIdle()
        shows("second", 0)
    }
}
