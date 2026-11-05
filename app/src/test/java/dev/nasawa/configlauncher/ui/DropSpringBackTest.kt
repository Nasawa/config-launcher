package dev.nasawa.configlauncher.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Grid
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #212 (ux.md §1.5/§4.2, L11): a refused drop springs back to its cell over about 200 ms (offset
 * and the 1.15 lift easing together, no overshoot), is instant with reduced motion, and writes
 * nothing. Driven by the compose test clock with autoAdvance off; no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DropSpringBackTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var engine: ConfigEngine
    private val submitted = mutableListOf<Edit>()
    private var cancelled = 0
    private lateinit var drag: DragController
    private var shown by mutableStateOf(true)
    private val item = ResolvedItem(AppItem("a", "p.a", x = 0, y = 0), null)
    private val other = ResolvedItem(AppItem("b", "p.b", x = 1, y = 0), null)

    @Before fun setUp() {
        engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        drag = DragController({ engine }, onCancel = { cancelled++ }) { submitted += it }
        drag.gridRect = Rect(0f, 0f, 400f, 500f) // the placed block
        drag.currentPageId = "home"
        drag.grid = Grid(4, 5)
        drag.reducedMotion = { ReducedMotion.on(ctx) }
        rule.mainClock.autoAdvance = false
        rule.setContent { if (shown) { drag.scope = rememberCoroutineScope(); Box {} } }
        rule.mainClock.advanceTimeByFrame()
    }

    @After fun tearDown() { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }

    /** Lift the tile at (0,0) and release it in the right-hand margin, outside the block. */
    private fun refusedDrop() {
        drag.start(item, Rect(0f, 0f, 100f, 100f))
        drag.move(Offset(450f, 0f)) // centre at x=500: outside gridRect, no dock
        assertEquals(Offset(450f, 0f), drag.visualOffset)
        assertEquals(1.15f, drag.visualScale, 1e-4f)
        rule.runOnIdle { drag.drop() }
    }

    @Test fun refusedDropSpringsBackOverAbout200ms() {
        refusedDrop()
        assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
        assertFalse("dragging drops at once (§17 Q6)", engine.dragging)
        assertTrue(drag.returning)

        rule.mainClock.advanceTimeBy(100)
        val mid = drag.visualOffset.x
        assertTrue("still partway at ~100 ms: $mid", mid > 1f && mid < 450f)
        val k = drag.visualScale
        assertTrue("scale easing with it: $k", k > 1f && k < 1.15f)
        // Together: both are the same fraction of the way back.
        assertEquals(mid / 450f, (k - 1f) / 0.15f, 1e-3f)
        assertSame(item, drag.dragged) // the pager and new drags still wait

        rule.mainClock.advanceTimeBy(120)
        rule.waitForIdle()
        assertEquals(Offset.Zero, drag.visualOffset)
        assertEquals(1f, drag.visualScale, 0f)
        assertNull(drag.dragged); assertFalse(drag.returning)
        assertTrue("nothing written", submitted.isEmpty()); assertEquals(1, cancelled)
    }

    @Test fun neverOvershootsTheCell() {
        refusedDrop()
        repeat(15) {
            rule.mainClock.advanceTimeByFrame()
            assertTrue(drag.visualOffset.x >= 0f); assertTrue(drag.visualScale >= 1f)
        }
    }

    @Test fun reducedMotionReturnsOnTheNextFrame() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        refusedDrop()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(Offset.Zero, drag.visualOffset)
        assertEquals(1f, drag.visualScale, 0f)
        assertNull(drag.dragged); assertFalse(drag.returning)
        assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
    }

    @Test fun successfulDropIsUnchanged() {
        drag.start(item, Rect(0f, 0f, 100f, 100f))
        drag.move(Offset(250f, 250f)) // into the block
        rule.runOnIdle { drag.drop() }
        assertEquals(listOf<Edit>(Edit.MoveToCell("a", "home", 3, 3)), submitted)
        assertEquals(0, cancelled)
        assertNull("no spring-back on success", drag.dragged)
        assertFalse(drag.returning); assertFalse(engine.dragging)
        assertEquals(Offset.Zero, drag.visualOffset); assertEquals(1f, drag.visualScale, 0f)
    }

    // Grace (#230): cancelNow on a live drag over a valid cell clears at once and writes nothing (a cancelNow→drop() mutation fails).
    @Test fun cancelNowOverAValidCellWritesNothing() {
        drag.start(item, Rect(0f, 0f, 100f, 100f))
        drag.move(Offset(250f, 250f)) // over (3,3): a release here would write MoveToCell
        rule.runOnIdle { drag.cancelNow() }
        assertTrue("nothing written", submitted.isEmpty()); assertEquals(1, cancelled)
        assertNull(drag.dragged); assertFalse(drag.returning); assertFalse(engine.dragging)
        assertEquals(Offset.Zero, drag.visualOffset); assertEquals(1f, drag.visualScale, 0f)
    }

    @Test fun newDragDuringSpringBackStartsCleanly() {
        refusedDrop()
        rule.mainClock.advanceTimeBy(80)
        assertTrue(drag.returning)
        drag.move(Offset(30f, 30f)) // stray input mid-animation is ignored
        rule.runOnIdle { drag.start(other, Rect(100f, 0f, 200f, 100f)) }
        assertSame(other, drag.dragged); assertFalse(drag.returning)
        assertEquals(Offset.Zero, drag.visualOffset); assertEquals(1.15f, drag.visualScale, 1e-4f)
        assertTrue(engine.dragging)
        rule.mainClock.advanceTimeBy(300) // the old animation must not clear the new drag
        rule.waitForIdle()
        assertSame(other, drag.dragged); assertTrue(engine.dragging)
        drag.move(Offset(150f, 250f)) // into the block
        rule.runOnIdle { drag.drop() }
        assertEquals(1, submitted.size); assertEquals(1, cancelled)
    }

    @Test fun leavingCompositionMidSpringBackLeavesNothingLiftedAndWritesNothing() {
        refusedDrop()
        rule.mainClock.advanceTimeBy(80)
        assertTrue(drag.returning)
        shown = false // rotate / recreate: the composition (and its scope) goes away
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertNull(drag.dragged); assertFalse(drag.returning); assertFalse(engine.dragging)
        assertEquals(Offset.Zero, drag.visualOffset); assertEquals(1f, drag.visualScale, 0f)
        assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
    }

    @Test fun refusedDropWithoutScopeIsInstantLikeBefore() {
        val d = DragController({ engine }, onCancel = { cancelled++ }) { submitted += it }
        d.start(item, Rect(0f, 0f, 100f, 100f)); d.move(Offset(450f, 0f)); d.drop()
        assertNull(d.dragged); assertTrue(submitted.isEmpty()); assertEquals(1, cancelled)
    }
}
