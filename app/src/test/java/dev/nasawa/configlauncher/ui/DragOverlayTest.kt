package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #228 in pixels: the source tile sits in a clipping box (as in the pager viewport) and a "dock" is drawn after it,
 * as HomeScreen's dock / rail / fold halves are. While lifted, the source draws nothing (alpha 0) and the root
 * [DragOverlay] paints the tile over the dock, unclipped; after the spring-back the source draws again and the
 * overlay is gone. Density 1 (1 dp = 1 px); the compose main clock drives the return; no real clock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragOverlayTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val tile = Color(0xFFFF0000)
    private val dock = Color(0xFF0000FF)
    private val bg = Color(0xFFFFFFFF)
    private val item = ResolvedItem(AppItem(id = "a", packageName = "p.a"), InstalledApp("p.a", "p.a.Main", "Alpha"))
    private lateinit var drag: DragController
    private var cancelled = 0

    private fun show() {
        val engine = ConfigEngine(LocalFileSource(tmp.newFolder("src")), LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        // No grid or dock rects: every drop is refused and springs back.
        drag = DragController({ engine }, onCancel = { cancelled++ }) { error("nothing is written: $it") }
        drag.reducedMotion = { false }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                drag.scope = rememberCoroutineScope()
                Box(Modifier.size(300.dp).background(bg).testTag("root")) {
                    Box(Modifier.size(100.dp).clipToBounds()) { // the pager viewport
                        with(drag) {
                            Box(Modifier.size(60.dp).tile(item, true, {}, {}, visual = { Box(Modifier.fillMaxSize().background(tile)) }).background(tile))
                        }
                    }
                    Box(Modifier.offset(150.dp, 150.dp).size(100.dp).background(dock)) // drawn after the grid
                    DragOverlay(drag)
                }
            }
        }
        frames(2)
    }

    private fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
    private fun px(x: Int, y: Int) = rule.onNodeWithTag("root").captureToImage().toPixelMap()[x, y]

    @Test fun liftedTileIsPaintedOverTheDockAndTheSourceIsHidden() {
        show()
        assertEquals("at rest the source draws", tile, px(30, 30))
        assertEquals(dock, px(200, 200))
        rule.runOnIdle { drag.start(item, Rect(0f, 0f, 60f, 60f)); drag.move(Offset(170f, 170f)) }
        frames(30)
        assertEquals("over the dock, the lifted tile is on top", tile, px(200, 200))
        assertEquals("outside the pager viewport too (no clip)", tile, px(200, 172))
        assertEquals("the source is at alpha 0", bg, px(30, 30))
        rule.runOnIdle { drag.drop() } // refused: the #218 spring-back
        frames(3)
        assertTrue("springing back", drag.returning)
        frames(60)
        assertEquals("back: the source draws again", tile, px(30, 30))
        assertEquals("the overlay is gone", dock, px(200, 200))
        assertEquals(1, cancelled)
    }
}
