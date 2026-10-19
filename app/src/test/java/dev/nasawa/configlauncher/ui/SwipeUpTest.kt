package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #77: swipe up on home opens the drawer; sideways and downward swipes don't. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SwipeUpTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rule() {
        assertTrue(SwipeUp.opens(Offset(10f, -200f), 100f))
        assertFalse("not far enough", SwipeUp.opens(Offset(0f, -50f), 100f))
        assertTrue("|dy| > 1.5 × |dx|", SwipeUp.opens(Offset(120f, -200f), 100f))
        assertFalse("|dy| = 1.5 × |dx| is not enough", SwipeUp.opens(Offset(200f, -300f), 100f))
        assertFalse("down", SwipeUp.opens(Offset(0f, 200f), 100f))
    }

    @Test fun axisLock() {
        assertNull(SwipeUp.axis(Offset(5f, -5f), 16f))
        assertEquals(SwipeUp.Axis.HORIZONTAL, SwipeUp.axis(Offset(20f, -10f), 16f))
        assertEquals(SwipeUp.Axis.UP, SwipeUp.axis(Offset(5f, -20f), 16f))
        assertEquals(SwipeUp.Axis.DOWN, SwipeUp.axis(Offset(5f, 20f), 16f))
    }

    @Test fun fling() {
        assertTrue(SwipeUp.flings(Offset(0f, -20f), -1500f, 16f, 1000f))
        assertFalse("too short", SwipeUp.flings(Offset(0f, -10f), -1500f, 16f, 1000f))
        assertFalse("too slow", SwipeUp.flings(Offset(0f, -40f), -800f, 16f, 1000f))
    }

    private fun opened(swipe: androidx.compose.ui.test.TouchInjectionScope.() -> Unit, enabled: Boolean = true): Int {
        var opens = 0
        rule.setContent { Box(Modifier.size(400.dp).testTag("home").swipeUpToOpen({ enabled }) { opens++ }) }
        rule.onNodeWithTag("home").performTouchInput(swipe)
        rule.waitForIdle()
        return opens
    }

    @Test fun swipeUpOpens() = assertEquals(1, opened({ swipeUp() }))
    @Test fun swipeLeftDoesNot() = assertEquals(0, opened({ swipeLeft() }))
    @Test fun swipeDownDoesNot() = assertEquals(0, opened({ swipeDown() }))
    @Test fun disabledWhileDragging() = assertEquals(0, opened({ swipeUp() }, enabled = false))

    @Test fun swipeUpOverATileOpensAndDoesNotLaunch() {
        var opens = 0; var launches = 0; var sheets = 0
        val item = dev.nasawa.configlauncher.model.ResolvedItem(dev.nasawa.configlauncher.model.AppItem("a", "p.a"), dev.nasawa.configlauncher.model.InstalledApp("p.a", "p.a.M", "Alpha"))
        val apps = dev.nasawa.configlauncher.apps.InstalledApps(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val drag = DragController({ error("no drag") }) { }
        rule.setContent {
            Box(Modifier.size(400.dp).swipeUpToOpen({ true }) { opens++ }) {
                with(drag) { Tile(item, apps, Modifier.size(200.dp).testTag("tile").tile(item, true, onClick = { launches++ }, onLongClick = { sheets++ })) }
            }
        }
        rule.onNodeWithTag("tile").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(1, opens); assertEquals(0, launches); assertEquals(0, sheets)
    }

    @Test fun bottomGestureStripIsIgnored() {
        var opens = 0
        rule.setContent { Box(Modifier.size(400.dp).testTag("home").swipeUpToOpen({ true }, { 100_000f }) { opens++ }) }
        rule.onNodeWithTag("home").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(0, opens)
    }

    @Test fun holdThenSwipeIsALongPressNotASwipe() {
        var opens = 0
        rule.setContent { Box(Modifier.size(400.dp).testTag("home").swipeUpToOpen({ true }) { opens++ }) }
        rule.onNodeWithTag("home").performTouchInput { down(center); advanceEventTime(1000); moveBy(Offset(0f, -300f)); up() }
        rule.waitForIdle()
        assertEquals(0, opens)
    }
}
