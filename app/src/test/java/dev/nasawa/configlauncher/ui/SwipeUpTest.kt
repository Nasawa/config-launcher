package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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

    // #191: right after a cold start the down is handled late, so the rest of the swipe reaches home as one late
    // event (often only the lift, far from the down) whose timestamp is past the long-press timeout. No long-press
    // fired (the long-press detectors time from when the down was handled), so it's a swipe. These events go
    // straight to the view, back to back, the way they arrive after a stall: event times only, no clock advance.

    private lateinit var view: android.view.View

    private fun motion(action: Int, downAt: Long, at: Long, p: Offset) =
        android.view.MotionEvent.obtain(downAt, at, action, p.x, p.y, 0).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }

    /** DOWN at [from], then (after a [gapMs] stall in event time) an optional MOVE and the lift at [to]. */
    private fun stalled(from: Offset, to: Offset, gapMs: Long, move: Boolean = false) {
        rule.runOnIdle {
            val t0 = android.os.SystemClock.uptimeMillis()
            listOfNotNull(
                motion(android.view.MotionEvent.ACTION_DOWN, t0, t0, from),
                if (move) motion(android.view.MotionEvent.ACTION_MOVE, t0, t0 + gapMs, to) else null,
                motion(android.view.MotionEvent.ACTION_UP, t0, t0 + gapMs + 1, to),
            ).forEach { e -> view.dispatchTouchEvent(e); e.recycle() }
        }
        rule.waitForIdle()
    }

    private fun stalledOpens(gapMs: Long, move: Boolean = false): Int {
        var opens = 0
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Box(Modifier.size(400.dp).swipeUpToOpen({ true }) { opens++ })
        }
        val c = rule.onRoot().fetchSemanticsNode().boundsInRoot.center
        stalled(c, c - Offset(0f, 300f), gapMs, move)
        return opens
    }

    @Test fun stalledLiftPastTheTimeoutOpens() = assertEquals(1, stalledOpens(600))
    @Test fun stalledLiftBeforeTheTimeoutOpens() = assertEquals(1, stalledOpens(350))
    @Test fun stalledMovePastTheTimeoutOpens() = assertEquals(1, stalledOpens(600, move = true))

    @Test fun stalledShortLiftIsATapNotASwipe() {
        var opens = 0
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Box(Modifier.size(400.dp).swipeUpToOpen({ true }) { opens++ })
        }
        val c = rule.onRoot().fetchSemanticsNode().boundsInRoot.center
        stalled(c, c - Offset(0f, 20f), 600)
        assertEquals(0, opens)
    }

    @Test fun stalledLiftOverATileOpensAndDoesNotLaunch() {
        var opens = 0; var launches = 0; var sheets = 0
        val item = dev.nasawa.configlauncher.model.ResolvedItem(dev.nasawa.configlauncher.model.AppItem("a", "p.a"), dev.nasawa.configlauncher.model.InstalledApp("p.a", "p.a.M", "Alpha"))
        val apps = dev.nasawa.configlauncher.apps.InstalledApps(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val drag = DragController({ error("no drag") }) { }
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            Box(Modifier.size(400.dp).swipeUpToOpen({ true }) { opens++ }) {
                with(drag) { Tile(item, apps, Modifier.size(200.dp).testTag("tile").tile(item, true, onClick = { launches++ }, onLongClick = { sheets++ })) }
            }
        }
        val c = rule.onNodeWithTag("tile").fetchSemanticsNode().boundsInRoot.center
        stalled(c, c - Offset(0f, 300f), 600)
        assertEquals(1, opens); assertEquals(0, launches); assertEquals(0, sheets)
    }

    @Test fun holdOnTheClockThenLiftIsNotASwipe() {
        var opens = 0
        rule.setContent { Box(Modifier.size(400.dp).testTag("home").swipeUpToOpen({ true }) { opens++ }) }
        rule.onNodeWithTag("home").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("home").performTouchInput { moveBy(Offset(0f, -300f)); up() }
        rule.waitForIdle()
        assertEquals(0, opens)
    }
}
