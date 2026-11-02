package dev.nasawa.configlauncher.ui

import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as testFold
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File

/**
 * #228 on the whole app (MainActivity): the lifted tile is drawn by the one [DragOverlay] at the HomeScreen root,
 * so it stays whole from pickup until it lands on a dock / rail slot, and through the #218 spring-back. It used
 * to be clipped by the pager viewport and drawn under the dock / rail / fold halves, which come later.
 *
 * Cases: bottom dock (portrait), rail (landscape, LTR), rail on the mirrored edge (RTL), and the book-posture
 * split (vertical half-open hinge, home in the start half, refused drop in the end half). Each: mid-drag over the
 * dock slot, the landing (MoveToDock written, overlay gone), a refused drop springing back from over the dock
 * (every frame whole, nothing written), and reduced motion (instant return).
 *
 * "Whole" = the overlay node's [androidx.compose.ui.semantics.SemanticsNode.boundsInRoot], which Compose clips by
 * every ancestor's clip: a clipped overlay reports a smaller (or empty) rect than the tile at rest. Touch input and
 * the spring-back run on the compose main clock (autoAdvance off while frames are checked). The fixture's title
 * bar shows only {page}: no clock slots, so no LocalTitleBarNow is needed; no real clock anywhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DragOverlayHomeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val publisher = WindowLayoutInfoPublisherRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(publisher).around(compose)

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // The activity's own store (see FoldHalfTest): the VM factory keeps the first test's Application.
    private val store get() = androidx.lifecycle.ViewModelProvider(compose.activity)[HomeViewModel::class.java].store

    @After fun tearDown() { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private enum class Case { DOCK, RAIL, BOOK }

    private fun page(): String {
        compose.waitForIdle()
        val n = compose.onAllNodes(hasContentDescription(", Pages", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        return n.firstNotNullOf { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.firstOrNull() }.substringBefore(", Pages")
    }

    private fun goto(title: String) {
        compose.onNode(hasContentDescription(", Pages", substring = true)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(title, page())
    }

    private var hinge: android.graphics.Rect? = null

    private fun setUp(case: Case) {
        if (case == Case.BOOK) compose.activityRule.scenario.onActivity { a ->
            val f = testFold(a, size = 40, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.VERTICAL)
            hinge = f.bounds
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(f)))
        }
        // Reset the activity's store to the fixture: an earlier test in this JVM may have landed a tile in its dock.
        store.localDir.mkdirs()
        kotlinx.coroutines.runBlocking { store.engine.flush(); File(store.localDir, "launcher.json").writeText(javaClass.getResource("/pager/pages3.json")!!.readText()); store.engine.poll(force = true) }
        compose.waitForIdle()
        assertTrue("fixture dock", store.engine.state.value.config.dock.orEmpty().map { it.id } == listOf("d-settings"))
        val rail = compose.onAllNodes(hasTestTag("dockRail")).fetchSemanticsNodes().isNotEmpty()
        assertEquals("rail layout for $case", case == Case.RAIL, rail)
        goto("Second")
    }

    private fun overlayNode() = compose.onAllNodes(hasTestTag(DRAG_OVERLAY_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().singleOrNull()

    /** Where the overlay is drawn, clipped by every ancestor (and the window). */
    private fun overlay(): Rect? = overlayNode()?.boundsInRoot

    /** The overlay's full, unclipped extent (its layer's translation and scale included). */
    private fun overlayFull(): Rect {
        val c = overlayNode()!!.layoutInfo.coordinates
        val a = c.localToRoot(Offset.Zero)
        val b = c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    private fun window(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    /** The source tile's touch target (it stays in its cell, at alpha 0, during the drag). */
    private fun source(): Rect = compose.onAllNodes(hasText("Second tile") or hasContentDescription("Second tile", substring = true))
        .fetchSemanticsNodes().single { SemanticsActions.OnClick in it.config }.boundsInRoot

    private fun dockSlot(): Rect = compose.onAllNodes(hasContentDescription(", dock", substring = true))
        .fetchSemanticsNodes().first().boundsInRoot

    private fun tileNodes() = compose.onAllNodes(hasText("Second tile") or hasContentDescription("Second tile", substring = true))
        .fetchSemanticsNodes().size

    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    /**
     * The lifted tile is drawn whole: at least its resting size, and nothing but the window edge cuts it (a slot at
     * the screen edge leaves part of the 1.15× tile off-screen). No pager viewport, dock, rail or fold half clips it.
     */
    private fun assertWhole(rest: Rect, why: String) {
        val o = overlay()
        assertNotNull("$why: the lifted tile is drawn", o)
        val full = overlayFull()
        assertTrue("$why: at least its resting size: $full vs rest $rest", full.width >= rest.width - 1f && full.height >= rest.height - 1f)
        val expect = full.intersect(window())
        assertTrue("$why: clipped only by the window: drawn $o, full $full",
            kotlin.math.abs(o!!.left - expect.left) < 1f && kotlin.math.abs(o.top - expect.top) < 1f &&
                kotlin.math.abs(o.right - expect.right) < 1f && kotlin.math.abs(o.bottom - expect.bottom) < 1f)
        assertTrue("$why: mostly on screen", o.width * o.height >= 0.5f * full.width * full.height)
    }

    /** Long-press the source on the test clock, past slop, then step the finger to [to] (root px), checking every step. */
    private fun liftAndMoveTo(to: Offset, rest: Rect) {
        compose.mainClock.autoAdvance = false
        val from = rest.center
        compose.onRoot().performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { moveBy(Offset(20f, 20f)) }
        frames(2)
        compose.onRoot().performTouchInput { moveBy(Offset(20f, 20f)) }
        frames(4)
        assertTrue("the drag started", store.engine.dragging)
        assertWhole(rest, "at pickup")
        assertEquals("exactly one tile semantics node while lifted", 1, tileNodes())
        assertEquals("the source keeps its place (and its gesture)", rest, source())
        step(from + Offset(40f, 40f), to, rest, "toward the dock")
    }

    /** Moves the held finger from [from] to [to] (root px) in 8 steps; the lifted tile is whole at every step. */
    private fun step(from: Offset, to: Offset, rest: Rect, why: String) {
        val d = (to - from) / 8f
        repeat(8) {
            compose.onRoot().performTouchInput { moveBy(d) }
            frames(2)
            assertWhole(rest, "$why, step $it")
        }
    }

    private fun overDock(case: Case) {
        setUp(case)
        val rest = source()
        val dock = dockSlot()
        liftAndMoveTo(dock.center, rest)
        val o = overlay()!!
        assertTrue("mid-drag: the tile is drawn over the dock slot $dock: $o", o.overlaps(dock) && o.contains(dock.center))
        assertWhole(rest, "over the dock")
        assertEquals(1, tileNodes())
        if (case == Case.BOOK) hinge!!.let { h ->
            val rtl = compose.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            assertTrue("book: the dock is in the start half", if (rtl) dock.left >= h.right else dock.right <= h.left)
        }
    }

    /** Lands on the dock: MoveToDock is written, and the overlay is gone once nothing is lifted. */
    private fun landing(case: Case) {
        overDock(case)
        val before = store.engine.state.value.config
        compose.onRoot().performTouchInput { up() }
        frames(2)
        assertFalse(store.engine.dragging)
        assertNull("landed: the overlay goes", overlay())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { store.engine.state.value.config.dock.orEmpty().any { it.id == "a-second" } }
        val after = store.engine.state.value.config
        assertEquals("MoveToDock written", listOf("d-settings", "a-second").toSet(), after.dock.orEmpty().map { it.id }.toSet())
        assertTrue("before: on its page", before.pages.first { it.id == "second" }.items.any { it.id == "a-second" })
        assertTrue("left the page", after.pages.first { it.id == "second" }.items.none { it.id == "a-second" })
        assertNull(overlay())
    }

    /** From over the dock to [refused] (outside grid and dock), release: springs back whole every frame, writes nothing. */
    private fun refusedSpringBack(case: Case, reduced: Boolean = false) {
        if (reduced) Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        setUp(case)
        val before = store.engine.state.value.config
        val rest = source()
        val dock = dockSlot()
        liftAndMoveTo(dock.center, rest)
        assertTrue(overlay()!!.contains(dock.center))
        step(dock.center, refusedPoint(case), rest, "from over the dock to a refused spot")
        compose.onRoot().performTouchInput { up() }
        if (reduced) {
            frames(2)
            assertNull("reduced motion: an instant return", overlay())
        } else {
            var whole = 0
            for (i in 0 until 60) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                overlay() ?: break
                assertWhole(rest, "spring-back frame $i")
                whole++
            }
            assertTrue("the return took frames ($whole), all whole", whole >= 3)
            frames(2)
            assertNull("the return finished", overlay())
        }
        assertFalse(store.engine.dragging)
        assertEquals("back in its cell", rest, source())
        assertEquals(1, tileNodes())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val after = store.engine.state.value.config
        assertEquals("a refused drop writes nothing", before.revision, after.revision)
        assertEquals(before.pages, after.pages)
        assertEquals(before.dock, after.dock)
    }

    // Outside the grid and the dock: the 48 dp top row in the middle of home; on a book, the end half.
    private fun refusedPoint(case: Case): Offset {
        val root = window()
        val density = compose.activity.resources.displayMetrics.density
        if (case == Case.BOOK) {
            val h = hinge!!
            val rtl = compose.activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
            return Offset(if (rtl) h.left / 2f else (h.right + root.right) / 2f, root.height / 2f)
        }
        return Offset(root.center.x, 20f * density)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xhdpi") fun dockMidDragAndLanding() = landing(Case.DOCK)
    @Test @Config(qualifiers = "w411dp-h891dp-port-xhdpi") fun dockRefusedSpringBack() = refusedSpringBack(Case.DOCK)
    @Test @Config(qualifiers = "w411dp-h891dp-port-xhdpi") fun dockReducedMotion() = refusedSpringBack(Case.DOCK, reduced = true)

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi") fun railMidDragAndLanding() = landing(Case.RAIL)
    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi") fun railRefusedSpringBack() = refusedSpringBack(Case.RAIL)
    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi") fun railReducedMotion() = refusedSpringBack(Case.RAIL, reduced = true)

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi") fun rtlRailMidDragAndLanding() = landing(Case.RAIL)
    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi") fun rtlRailRefusedSpringBack() = refusedSpringBack(Case.RAIL)
    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi") fun rtlRailReducedMotion() = refusedSpringBack(Case.RAIL, reduced = true)

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi") fun bookMidDragAndLanding() = landing(Case.BOOK)
    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi") fun bookRefusedSpringBackFromTheEndHalf() = refusedSpringBack(Case.BOOK)
    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi") fun bookRtlRefusedSpringBackFromTheEndHalf() = refusedSpringBack(Case.BOOK)
    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi") fun bookReducedMotion() = refusedSpringBack(Case.BOOK, reduced = true)
}
