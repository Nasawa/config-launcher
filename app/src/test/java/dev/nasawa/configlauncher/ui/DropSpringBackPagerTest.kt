package dev.nasawa.configlauncher.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #218, Pragma's conditions on blocking the pager during the #212 spring-back, through real touch
 * input on a tile wired with `Modifier.tile` and a pager gated like HomeScreen's
 * (`userScrollEnabled = drag.dragged == null`):
 *  (a) a swipe during the return is dropped, not replayed after it ends;
 *  (b) a new long-press on the tile interrupts the return and starts a clean drag;
 *  (c) with reduced motion nothing is blocked: a swipe right after a refused drop pages.
 * The compose main clock drives everything (autoAdvance off for the animation); no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DropSpringBackPagerTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val submitted = mutableListOf<Edit>()
    private var cancelled = 0
    private lateinit var engine: ConfigEngine
    private lateinit var drag: DragController
    private lateinit var pager: PagerState
    private var tileShown by mutableStateOf(true)
    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))

    @After fun tearDown() { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }

    private fun setUp(reduced: Boolean) {
        if (reduced) Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        // No grid or dock rects: every drop is refused.
        drag = DragController({ engine }, onCancel = { cancelled++ }) { submitted += it }
        drag.reducedMotion = { ReducedMotion.on(ctx) }
        val apps = InstalledApps(ctx)
        rule.setContent {
            drag.scope = rememberCoroutineScope()
            pager = rememberPagerState { 3 }
            Column(Modifier.size(400.dp, 600.dp)) {
                Box(Modifier.size(400.dp, 150.dp)) { if (tileShown) with(drag) { Tile(item, apps, Modifier.tile(item, true, {}, {})) } }
                HorizontalPager(pager, Modifier.size(400.dp, 400.dp).testTag("pager"), userScrollEnabled = drag.dragged == null) { Text("page $it") }
            }
        }
        rule.waitForIdle()
    }

    /** Long-press the tile, drag it well away, release: a refused drop. Clock stopped afterwards. */
    private fun refusedDrop() {
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(150f, 150f)); moveBy(Offset(150f, 150f))
        }
        rule.waitForIdle()
        assertTrue("dragging", engine.dragging)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Alpha").performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(submitted.isEmpty()); assertEquals(1, cancelled); assertFalse(engine.dragging)
    }

    private fun swipeLeftOnPager() = rule.onNodeWithTag("pager").performTouchInput {
        down(centerRight - Offset(20f, 0f))
        repeat(10) { advanceEventTime(10); moveBy(Offset(-width * 0.07f, 0f)) }
        up()
    }

    @Test fun a_swipeDuringReturnIsDroppedNotReplayed() {
        setUp(reduced = false)
        refusedDrop()
        assertTrue("returning", drag.returning)
        swipeLeftOnPager() // during the ~200 ms return
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNull(drag.dragged); assertFalse(drag.returning)
        assertEquals("no page turn, during or after the return", 0, pager.currentPage)
        assertEquals(0, pager.settledPage)
        assertTrue(submitted.isEmpty())
    }

    @Test fun a_swipeHeldAcrossTheEndOfTheReturnDoesNotPage() {
        setUp(reduced = false)
        refusedDrop()
        val node = rule.onNodeWithTag("pager")
        node.performTouchInput { down(centerRight - Offset(20f, 0f)); repeat(3) { advanceEventTime(10); moveBy(Offset(-40f, 0f)) } }
        rule.mainClock.advanceTimeBy(400) // the return ends with the finger still down
        assertNull(drag.dragged)
        node.performTouchInput { repeat(6) { advanceEventTime(10); moveBy(Offset(-width * 0.1f, 0f)) }; up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("the blocked swipe isn't picked up once the pager unblocks", 0, pager.settledPage)
    }

    @Test fun a_aFreshSwipeAfterTheReturnPages() {
        setUp(reduced = false)
        refusedDrop()
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertNull(drag.dragged)
        swipeLeftOnPager()
        rule.waitForIdle()
        assertEquals("the block lifts with the return", 1, pager.settledPage)
    }

    @Test fun b_longPressDuringReturnStartsACleanDrag() {
        setUp(reduced = false)
        refusedDrop()
        rule.mainClock.advanceTimeBy(80)
        assertTrue("mid-return", drag.returning)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(30f, 0f)); moveBy(Offset(30f, 0f))
        }
        rule.waitForIdle()
        assertSame(item, drag.dragged); assertFalse("the return was snapped to its end", drag.returning)
        assertTrue(engine.dragging)
        assertEquals(1.15f, drag.visualScale, 1e-4f)
        assertTrue("fresh offset, not the old one: ${drag.visualOffset}", drag.visualOffset.x in 1f..200f)
        rule.mainClock.advanceTimeBy(400) // the old animation must not clear the new drag
        assertSame(item, drag.dragged); assertTrue(engine.dragging)
        rule.onNodeWithText("Alpha").performTouchInput { up() } // refused again: springs back again
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertNull(drag.dragged); assertEquals(2, cancelled); assertTrue(submitted.isEmpty())
    }

    @Test fun c_reducedMotionBlocksNothing() {
        setUp(reduced = true)
        refusedDrop()
        assertNull("instant return", drag.dragged); assertFalse(drag.returning)
        assertEquals(1f, drag.visualScale, 0f)
        rule.mainClock.autoAdvance = true
        swipeLeftOnPager() // right after the release
        rule.waitForIdle()
        assertEquals("pages immediately", 1, pager.settledPage)
    }

    /** Merge of #214 into #218: a pointer cancel (Step.CANCEL) mid-drag springs back; it never drops. */
    @Test fun pointerCancelMidDragSpringsBack() {
        setUp(reduced = false)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(150f, 150f)); moveBy(Offset(150f, 150f))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Alpha").performTouchInput { cancel() }
        rule.mainClock.advanceTimeByFrame()
        assertTrue("springing back", drag.returning)
        assertFalse(engine.dragging); assertEquals(1, cancelled); assertTrue(submitted.isEmpty())
        rule.mainClock.advanceTimeBy(400)
        assertNull(drag.dragged); assertEquals(1f, drag.visualScale, 0f)
    }

    /** Merge of #214 into #218: the gesture's `finally` (tile left composition, recreate) clears at once, even with a live scope. */
    @Test fun gestureEndingMidDragClearsInstantlyAndWritesNothing() {
        setUp(reduced = false)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Alpha").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(150f, 150f)); moveBy(Offset(150f, 150f))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { tileShown = false }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame() // the tile is gone
        assertEquals(0, rule.onAllNodesWithText("Alpha").fetchSemanticsNodes().size)
        assertNull("no spring-back from the finally path", drag.dragged); assertFalse(drag.returning)
        assertFalse(engine.dragging); assertEquals(1, cancelled); assertTrue(submitted.isEmpty())
    }
}
