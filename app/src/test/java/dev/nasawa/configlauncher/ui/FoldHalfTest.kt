package dev.nasawa.configlauncher.ui

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * ux.md §1.5 Foldables / test-plan L9, on the whole app (MainActivity), posture injected only through
 * window-testing's WindowLayoutInfoPublisherRule (#197). Window 840 x 700 dp at xhdpi = 1680 x 1400 px.
 *  - book (vertical hinge, half-open): every node in the start half (left LTR, right RTL); the end half is empty;
 *  - tabletop (horizontal hinge): nothing straddles the hinge; title bar and grid above, dots and dock below;
 *    the §1.5 title-bar pad fallback and the L16 grid pitch measure the top half, not the window;
 *  - flat hinge and no feature: the same layout as today;
 *  - the page is kept by id across posture changes, nothing is written;
 *  - a posture change mid-drag cancels it with nothing written.
 * The fixture's title bar shows only {page}: no clock slots.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FoldHalfTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val publisher = WindowLayoutInfoPublisherRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(publisher).around(compose)

    // The activity's own store. Since #220 (MainActivity's factory) it is the same as `app.store`;
    // HomeWindowChangeTest asserts that.
    private val store get() = androidx.lifecycle.ViewModelProvider(compose.activity)[HomeViewModel::class.java].store

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

    /** Publishes a hinge [size] px thick at the window centre and returns its bounds; null publishes no feature. */
    private fun publish(state: FoldingFeature.State?, orientation: FoldingFeature.Orientation = FoldingFeature.Orientation.VERTICAL, size: Int = 40): Rect? {
        var bounds: Rect? = null
        compose.activityRule.scenario.onActivity { a ->
            val features = if (state == null) emptyList() else listOf(testFold(a, size = size, state = state, orientation = orientation).also { bounds = it.bounds })
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(features))
        }
        compose.waitForIdle()
        return bounds
    }

    private fun nodes(): List<SemanticsNode> {
        compose.waitForIdle()
        val all = compose.onAllNodes(androidx.compose.ui.test.isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        val root = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().first().boundsInRoot
        // Skip the window-sized wrappers above home; every other node is home content.
        return all.filter { it.boundsInRoot != root && it.boundsInRoot.width > 0f }
    }

    private fun label(n: SemanticsNode): String? =
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.firstOrNull()?.text
            ?: n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.firstOrNull()

    /** Labelled nodes by label → bounds, to compare layouts. */
    private fun layout(): Map<String, androidx.compose.ui.geometry.Rect> =
        nodes().mapNotNull { n -> label(n)?.let { it to n.boundsInRoot } }.toMap()

    /** First node whose label starts with [label] (page dots: "Page " matches both locales, ar uses Arabic digits). */
    private fun boundsOf(label: String) = layout().entries.first { it.key.startsWith(label) }.value

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

    // Book: the start half only, and the end half takes nothing (no focus either: no node there).
    private fun book(rtl: Boolean, state: FoldingFeature.State = FoldingFeature.State.HALF_OPENED) {
        val h = publish(state, FoldingFeature.Orientation.VERTICAL)!!
        val ns = nodes()
        assertTrue(ns.isNotEmpty())
        for (n in ns) {
            val b = n.boundsInRoot
            val ok = if (rtl) b.left >= h.right else b.right <= h.left
            assertTrue("${label(n) ?: n.id} $b must sit in the ${if (rtl) "right" else "left"} half (hinge $h)", ok)
        }
        listOf("QA Settings", "com.android.settings, dock", "Page ", "Home, Pages").forEach { boundsOf(it) }
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun bookPutsHomeInTheLeftHalfLtr() = book(rtl = false)

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun bookPutsHomeInTheRightHalfRtl() = book(rtl = true)

    // Archimedes (#219 call 1): a FLAT hinge with size > 0 is separating (a dual screen), so it splits like book.
    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun flatSize40HingeSplitsLikeBook() = book(rtl = false, state = FoldingFeature.State.FLAT)

    // Tabletop: top row, title bar and grid above the hinge; page dots and dock below.
    private fun tabletop() {
        val h = publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)!!
        for (n in nodes()) {
            val b = n.boundsInRoot
            assertTrue("${label(n) ?: n.id} $b crosses the hinge $h", b.bottom <= h.top || b.top >= h.bottom)
        }
        listOf("QA Settings", "Row two", "Home, Pages", "Apps").forEach {
            assertTrue("$it above the hinge", boundsOf(it).bottom <= h.top)
        }
        listOf("Page ", "com.android.settings, dock").forEach {
            assertTrue("$it below the hinge", boundsOf(it).top >= h.bottom)
        }
        assertTrue("no rail on a tabletop", compose.onAllNodes(androidx.compose.ui.test.hasTestTag("dockRail")).fetchSemanticsNodes().isEmpty())
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun tabletopSplitsAtTheHingeLtr() = tabletop()

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun tabletopSplitsAtTheHingeRtl() = tabletop()

    // §1.5 / L16 on a tabletop: the title-bar pad fallback and the grid pitch both measure the top half (above the
    // hinge), not the window. Flat, the 700 dp page column keeps the fixture's padding.vertical = 6; above a
    // centred hinge it is ~330 dp (< 480), so the pad is absorbed, and the 5 rows still fit at ≥ 48 dp each.
    private fun tabletopMeasuresTopHalf() {
        val density = compose.activity.resources.displayMetrics.density
        // Title-bar pad above the {page} target: its top minus the 48 dp top row (no safeDrawing top inset here).
        fun barPadTop() = boundsOf("Home, Pages").top / density - 48f
        fun pitchH() = (boundsOf("Row two").top - boundsOf("QA Settings").top) / density
        val flatPad = barPadTop(); val flatPitch = pitchH()
        assertEquals("flat: the 700 dp page column keeps padding.vertical", 6f, flatPad, 0.5f)
        val h = publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)!!
        val topHalfDp = h.top / density
        assertTrue("the top half is short ($topHalfDp dp)", topHalfDp < 480f)
        // §1.5 ladder: TitleBarRow gets the top half's height as pageColumnHeightDp, so the pad is absorbed.
        assertEquals(0, HomeGeometry.titleBarPaddingV(6, topHalfDp))
        assertEquals("tabletop: the title-bar pad is absorbed (top half < 480 dp)", 0f, barPadTop(), 0.5f)
        // L16: the grid pitch comes from the slot above the hinge: 5 rows fit there, each still ≥ 48 dp.
        val pitch = pitchH()
        assertTrue("tabletop pitch $pitch < flat $flatPitch: measured from the top half", pitch < flatPitch)
        assertTrue("tabletop pitchH $pitch ≥ 48 (rows × 48 fits the top half)", pitch >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
        val gridBottomDp = boundsOf("QA Settings").top / density + 5 * pitch
        assertTrue("5 rows end above the hinge: $gridBottomDp ≤ $topHalfDp", gridBottomDp <= topHalfDp + 0.5f)
        listOf("QA Settings", "Row two").forEach {
            val hit = boundsOf(it).height / density
            assertTrue("$it hit height $hit ≥ 48", hit >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
        }
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun tabletopMeasuresTopHalfLtr() = tabletopMeasuresTopHalf()

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun tabletopMeasuresTopHalfRtl() = tabletopMeasuresTopHalf()

    // Flat (isSeparating false) and no feature: today's layout, unchanged; back from a half-open fold too.
    private fun flatUnchanged() {
        val before = layout()
        assertTrue(before.keys.any { it.startsWith("QA Settings") })
        publish(FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL, size = 0)
        assertEquals("a flat vertical hinge is ignored", before, layout())
        publish(FoldingFeature.State.FLAT, FoldingFeature.Orientation.HORIZONTAL, size = 0)
        assertEquals("a flat horizontal hinge is ignored", before, layout())
        publish(FoldingFeature.State.HALF_OPENED)
        publish(null)
        assertEquals("no feature after a half-open fold is today's layout", before, layout())
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun flatAndNoFeatureUnchangedLtr() = flatUnchanged()

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun flatAndNoFeatureUnchangedRtl() = flatUnchanged()

    // #214: the page by id survives every posture; a window change never writes.
    private fun postureKeepsPage() {
        goto("Second")
        val rev = store.engine.state.value.config.revision
        publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL)
        assertEquals("book keeps the page", "Second", page())
        publish(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)
        assertEquals("tabletop keeps the page", "Second", page())
        publish(FoldingFeature.State.FLAT, size = 0)
        assertEquals("flat keeps the page", "Second", page())
        publish(null)
        assertEquals("no feature keeps the page", "Second", page())
        assertEquals("posture changes write nothing", rev, store.engine.state.value.config.revision)
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun postureKeepsPageLtr() = postureKeepsPage()

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun postureKeepsPageRtl() = postureKeepsPage()

    // A half-open fold mid-drag cancels the drag; lifting afterwards drops nothing.
    // Grace (#230): the drag ends over a valid empty cell in both directions (RTL: column 0 is the right edge, so it
    // moves left), so an uncancelled release would commit; a fold-change cancel()→drop() mutation must fail here.
    private fun postureMidDragWritesNothing(orientation: FoldingFeature.Orientation, rtl: Boolean) {
        val sx = if (rtl) -1f else 1f
        goto("Second")
        val before = store.engine.state.value.config
        // Long-press on the test clock (no real time), then move past slop: the drag starts.
        compose.onAllNodes(hasText("Second tile")).onFirst().performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { moveBy(Offset(sx * 120f, 120f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().performTouchInput { moveBy(Offset(sx * 60f, 60f)) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("the drag started", store.engine.dragging)
        publish(FoldingFeature.State.HALF_OPENED, orientation)
        assertFalse("the posture change cancels the drag", store.engine.dragging)
        // The finger lifts afterwards (the tile may have moved or left): nothing is dropped.
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        val after = store.engine.state.value.config
        assertEquals("an interrupted drag writes nothing", before.revision, after.revision)
        assertEquals(before.pages, after.pages)
        assertEquals(before.dock, after.dock)
        assertEquals("Second", page())
    }

    @Test @Config(qualifiers = "w840dp-h700dp-land-xhdpi")
    fun bookMidDragWritesNothingLtr() = postureMidDragWritesNothing(FoldingFeature.Orientation.VERTICAL, rtl = false)

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w840dp-h700dp-land-xhdpi")
    fun tabletopMidDragWritesNothingRtl() = postureMidDragWritesNothing(FoldingFeature.Orientation.HORIZONTAL, rtl = true)
}
