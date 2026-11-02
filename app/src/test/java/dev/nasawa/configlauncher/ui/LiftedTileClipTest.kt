package dev.nasawa.configlauncher.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * #221: since #201 the grid cell clipped the lifted tile to its origin cell, so it vanished mid-drag
 * and for most of the #212 spring-back. The lifted tile's cell now skips the clip (and is raised over
 * later cells); every other normal cell still clips, short cells keep the #201 T4 downward 48 dp hit
 * band, and no tile's hit area moves during a drag.
 *
 * "Visible" = the tile content's [SemanticsNode.boundsInRoot], which Compose clips by every ancestor's
 * clip (clipToBounds), so a clipped-away tile reports an empty rect. The compose main clock drives the
 * spring-back (autoAdvance off); the title-bar clock is pinned with [LocalTitleBarNow]; no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-mdpi")
class LiftedTileClipTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = InstalledApp("com.android.settings", "com.android.settings.Settings", "Settings")
    private val grid = Grid(columns = 4, rows = 5)
    private lateinit var engine: ConfigEngine
    private lateinit var drag: DragController
    private val submitted = mutableListOf<Edit>()

    private val items = (0 until 6).map { i ->
        ResolvedItem(AppItem("a$i", settings.packageName, x = i % 4, y = i / 4, label = "App $i"), settings)
    }
    private val lifted get() = items[0]

    @Before fun setUp() {
        engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        drag = DragController({ engine }) { submitted += it }
        drag.currentPageId = "home"
        drag.grid = grid
        drag.reducedMotion = { ReducedMotion.on(ctx) }
    }

    @After fun tearDown() { Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }

    /** A PageGrid in a [w]×[h] dp slot; [h] = 300 makes cells shorter than 48 dp. */
    private fun show(w: Int, h: Int) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    drag.scope = rememberCoroutineScope()
                    Box(Modifier.size(w.dp, h.dp)) {
                        PageGrid(ResolvedPage("home", "Home", items), grid, InstalledApps(ctx), drag, true, {}, {}, { _, _ -> emptyList() }, {})
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    /** Where tile [i]'s content is actually drawn (clipped by its ancestors). */
    private fun visible(i: Int): Rect {
        val label = "App $i"
        val n = rule.onAllNodes(hasText(label) or hasContentDescription(label), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { SemanticsActions.OnClick !in it.config }
            .maxBy { it.boundsInRoot.width * it.boundsInRoot.height }
        return n.boundsInRoot
    }

    /** Every tile's hit area (the OnClick node; it sits outside the lift's graphicsLayer). */
    private fun hitAreas(): List<Rect> = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes()
        .filter { SemanticsActions.OnClick in it.config }
        .map { it.touchBoundsInRoot }
        .sortedWith(compareBy({ it.top }, { it.left }))

    /** Tile 0's origin cell = its hit area (core sits before the 2 dp pad, so it fills the cell). */
    private fun cellRect(): Rect = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes()
        .first { SemanticsActions.OnClick in it.config && (hasText("App 0") or hasContentDescription("App 0")).matches(it) }
        .boundsInRoot

    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }

    private fun lift(by: Offset) {
        rule.runOnIdle {
            drag.start(lifted, cellRect())
            drag.move(by)
        }
        frames(3) // recompose (isMe), then the layer picks up the lift
    }

    private fun assertDrawnAwayFrom(origin: Rect, atLeast: Float, what: String) {
        val v = visible(0)
        assertTrue("$what: lifted tile is clipped away ($v)", v.width > 1f && v.height > 1f)
        assertTrue("$what: lifted tile drawn outside its origin cell $origin, got $v", v.left >= origin.right || v.top >= origin.bottom || v.left - origin.left >= atLeast)
    }

    @Test fun liftedTileDrawsOutsideItsCellDuringTheDrag() {
        show(800, 600)
        val origin = cellRect()
        val rest = visible(0)
        assertTrue("at rest inside its cell", origin.inflate(1f).contains(rest.center))
        lift(Offset(origin.width * 1.5f, origin.height * 1.5f)) // over (1,1)/(2,2), still in the block
        val v = visible(0)
        assertDrawnAwayFrom(origin, origin.width, "mid-drag")
        // Fully drawn (with the 1.15 lift), not just a sliver.
        assertTrue("whole tile: ${v.width} vs ${rest.width}", v.width >= rest.width - 1f)
        assertTrue(submitted.isEmpty())
    }

    @Test fun liftedTileStaysVisibleForTheWholeSpringBack() {
        show(800, 600)
        val origin = cellRect()
        val rest = visible(0)
        // Release to the right of the block (outside it, no dock): a refused drop.
        lift(Offset(drag.gridRect.width - origin.width / 2f + 4f, origin.height * 1.2f))
        assertDrawnAwayFrom(origin, origin.width, "before release")
        rule.runOnIdle { drag.drop() }
        assertTrue(drag.returning)
        var sawOutside = false
        repeat(14) { // ~224 ms of frames
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val v = visible(0)
            assertTrue("frame $it: tile clipped away mid-return ($v)", v.width >= rest.width - 1f && v.height >= rest.height - 1f)
            if (drag.returning && (v.left - origin.left) > origin.width) sawOutside = true
        }
        assertTrue("the return passes outside the origin cell", sawOutside)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        assertNull(drag.dragged); assertFalse(drag.returning)
        assertEquals("back in its cell", rest, visible(0))
        assertTrue("nothing written", submitted.isEmpty())
    }

    @Test fun reducedMotionReturnIsInstantAndVisible() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(800, 600)
        val origin = cellRect()
        val rest = visible(0)
        lift(Offset(drag.gridRect.width - origin.width / 2f + 4f, 0f))
        assertDrawnAwayFrom(origin, origin.width, "before release")
        rule.runOnIdle { drag.drop() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertNull(drag.dragged); assertFalse(drag.returning)
        assertEquals(rest, visible(0))
    }

    @Test fun hitAreasDoNotMoveDuringDragOrReturn() {
        show(800, 600)
        val before = hitAreas()
        assertEquals(items.size, before.size)
        val origin = cellRect()
        lift(Offset(drag.gridRect.width - origin.width / 2f + 4f, origin.height))
        assertEquals("mid-drag", before, hitAreas())
        rule.runOnIdle { drag.drop() }
        rule.mainClock.advanceTimeBy(80)
        rule.waitForIdle()
        assertTrue(drag.returning)
        assertEquals("mid-return", before, hitAreas())
    }

    @Test fun shortCellsKeepTheDownwardHitBandWhileATileIsLifted() {
        show(800, 220) // pitchH < 48: #201 T4 short cells
        val g = drag.gridRect
        val before = hitAreas()
        assertEquals(items.size, before.size)
        before.forEach {
            assertTrue("48 dp hit band ${it.height}", it.height >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
        }
        val firstRow = before.filter { it.top < g.top + g.height / 5f }
        assertTrue(firstRow.isNotEmpty())
        firstRow.forEach { assertTrue("starts at the cell top, never above the block: $it vs $g", it.top >= g.top - 0.5f && it.top <= g.top + 8.5f) }
        rule.runOnIdle {
            drag.start(lifted, Rect(g.left, g.top, g.left + g.width / 4f, g.top + g.height / 5f))
            drag.move(Offset(g.width / 2f, g.height / 2f))
        }
        frames(3)
        assertEquals("hit areas unchanged mid-drag", before, hitAreas())
        assertTrue("lifted short tile still drawn", visible(0).width > 1f)
    }

    @Test fun onlyTheLiftedNormalCellSkipsTheClip() {
        assertTrue("normal cells clip (Pragma #201)", HomeGeometry.clipsCell(60f, lifted = false))
        assertTrue(HomeGeometry.clipsCell(HomeGeometry.MIN_TOUCH_DP, lifted = false))
        assertFalse("#221: the lifted tile's cell", HomeGeometry.clipsCell(60f, lifted = true))
        assertFalse("#201 T4: short cells never clip", HomeGeometry.clipsCell(40f, lifted = false))
        assertFalse(HomeGeometry.clipsCell(40f, lifted = true))
    }

    @Test fun neighboursStayClippedAndInPlaceWhileATileIsLifted() {
        show(800, 600)
        val rest = (1 until items.size).map(::visible)
        val origin = cellRect()
        lift(Offset(origin.width * 1.5f, origin.height * 1.5f))
        assertEquals("other tiles untouched", rest, (1 until items.size).map(::visible))
        rule.runOnIdle { assertTrue(drag.isLifted(items[0])); assertFalse(drag.isLifted(items[1])) }
    }
}
