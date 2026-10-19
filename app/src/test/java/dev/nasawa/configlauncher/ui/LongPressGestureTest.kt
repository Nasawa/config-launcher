package dev.nasawa.configlauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 * #53 / test plan LP-1..LP-3, LP-12, LP-13 (Grace, #71; moved to the `Modifier.tile` API that owns the gesture):
 * a stationary long-press must not launch, and the labelled tile node must expose the long-click action
 * and the ux.md §12 custom actions (one merged TalkBack node).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongPressGestureTest {
    @get:Rule val rule = createComposeRule()

    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))
    private var launches = 0
    private var sheets = 0
    private var removes = 0

    private fun show(canDrag: Boolean = true) {
        val apps = InstalledApps(ApplicationProvider.getApplicationContext())
        val drag = DragController({ error("no drag in these tests") }) { }
        val actions = listOf(CustomAccessibilityAction("Remove from home") { removes++; true })
        rule.setContent {
            with(drag) { Tile(item, apps, Modifier.tile(item, canDrag, onClick = { launches++ }, onLongClick = { sheets++ }, actions = actions)) }
        }
    }

    @Test fun tapLaunches() {
        show()
        rule.onNodeWithText("Alpha").performClick()
        assertEquals(1, launches)
    }

    @Test fun longPressDoesNotLaunch() {
        show()
        rule.onNodeWithText("Alpha").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("a stationary long-press must open the item sheet, not launch (#53)", 0, launches)
        assertEquals(1, sheets)
    }

    @Test fun drawerTileLongPressDoesNotLaunch() {
        show(canDrag = false)
        rule.onNodeWithText("Alpha").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(0, launches); assertEquals(1, sheets)
    }

    @Test fun tileExposesLongClickAction() {
        show()
        // Also what TalkBack uses for "double-tap and hold" (AccessibilityAction ACTION_LONG_CLICK), on the labelled node.
        rule.onNodeWithText("Alpha").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        rule.onNodeWithText("Alpha").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, sheets)
    }

    @Test fun tileExposesCustomActions() {
        show()
        val node = rule.onNodeWithText("Alpha").fetchSemanticsNode()
        val custom = node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }.orEmpty()
        assertEquals(listOf("Remove from home"), custom.map { it.label })
        custom.first().action()
        assertEquals(1, removes)
    }
}
