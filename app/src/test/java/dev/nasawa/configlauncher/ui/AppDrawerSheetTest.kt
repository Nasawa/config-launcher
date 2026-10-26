package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #179 (ux.md §2.1): the drawer opens straight to full height, with no partially-expanded (half-height) stop. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class AppDrawerSheetTest {
    @get:Rule val rule = createComposeRule()
    private val heightPx = 640f * ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    /** Shows [sheet] with drawer-like content (a grid that takes the rest of the sheet); returns its state and the grid's top. */
    private fun open(sheet: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) -> Unit): Float {
        rule.setContent { MaterialTheme { sheet { Box(Modifier.fillMaxWidth().weight(1f).testTag("grid")) } } }
        rule.waitForIdle()
        return rule.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot.top
    }

    @Test fun opensFullyExpandedWithNoPartialState() {
        var state: SheetState? = null
        val top = open { content -> AppDrawerSheet({}, rememberAppDrawerState().also { state = it }, content) }
        val s = state!!
        assertFalse("no half-height stop", s.hasPartiallyExpandedState)
        assertEquals(SheetValue.Expanded, s.currentValue)
        assertEquals(SheetValue.Expanded, s.targetValue)
        assertTrue("grid starts in the top fifth of the screen, not halfway down (top $top of $heightPx px)", top < heightPx / 5)
    }

    @Test fun defaultSheetStateWouldStopHalfway() {   // guards the test itself: Grace's #179 repro
        var state: SheetState? = null
        val top = open { content -> ModalBottomSheet({}, sheetState = rememberModalBottomSheetState().also { state = it }, content = content) }
        assertTrue(state!!.hasPartiallyExpandedState)
        assertEquals(SheetValue.PartiallyExpanded, state!!.currentValue)
        assertTrue("half sheet (top $top of $heightPx px)", top >= heightPx / 3)
    }
}
