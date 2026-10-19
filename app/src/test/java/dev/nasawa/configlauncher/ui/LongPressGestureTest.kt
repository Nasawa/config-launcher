package dev.nasawa.configlauncher.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #53 / test plan LP-1..LP-3: a stationary long-press on a tile must not launch, and the tile must
 * expose a long-click action (the item sheet). Matches on semantics, not on a particular fix API.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongPressGestureTest {
    @get:Rule val rule = createComposeRule()

    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))

    private fun show(onClick: () -> Unit) {
        val apps = InstalledApps(ApplicationProvider.getApplicationContext())
        rule.setContent { Tile(item, apps, onClick = onClick) }
    }

    @Test fun tapLaunches() {
        var launches = 0
        show { launches++ }
        rule.onNodeWithText("Alpha").performClick()
        assertEquals(1, launches)
    }

    @Test fun longPressDoesNotLaunch() {
        var launches = 0
        show { launches++ }
        rule.onNodeWithText("Alpha", useUnmergedTree = false).performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("a stationary long-press must open the item sheet, not launch (#53)", 0, launches)
    }

    @Test fun tileExposesLongClickAction() {
        show { }
        // Also what TalkBack uses for "double-tap and hold" (AccessibilityAction ACTION_LONG_CLICK).
        rule.onNodeWithText("Alpha").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }
}
