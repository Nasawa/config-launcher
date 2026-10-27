package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.ResolvedItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §1.5 / #189: rail sits on the end edge (right in LTR, left in RTL) and the page column
 * gets the remaining width.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DockRailPlacementTest {
    @get:Rule val rule = createComposeRule()

    private val dock = (0 until 4).map { ResolvedItem(AppItem("d$it", "dock.$it", label = "Dock $it"), null) }

    private fun place(w: Dp, h: Dp, rtl: Boolean) {
        rule.setContent {
            val apps = InstalledApps(ApplicationProvider.getApplicationContext())
            val drag = DragController({ error("unused") }) {}
            drag.dockVertical = true
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(w, h)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("page"))
                        DockRail(dock, apps, drag, {}, {}, { _, _ -> emptyList() }) {
                            Box(Modifier.size(48.dp))
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun t7LandRailOnEndEdgeLtr() {
        assertTrue(HomeGeometry.useRail(960f, 600f))
        place(960.dp, 600.dp, rtl = false)
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertTrue(
            "page left of rail in LTR: page.right=${page.right} rail.left=${rail.left}",
            page.right.value <= rail.left.value + 1f,
        )
        assertTrue(rail.left.value > page.left.value)
        assertTrue("rail ~80dp wide: ${rail.right - rail.left}", (rail.right - rail.left).value in 79f..120f)
    }

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun t7LandRailOnEndEdgeRtl() {
        place(960.dp, 600.dp, rtl = true)
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertTrue(
            "rail left of page in RTL: rail.right=${rail.right} page.left=${page.left}",
            rail.right.value <= page.left.value + 1f,
        )
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun phoneLandscapeUsesRail() {
        assertTrue(HomeGeometry.useRail(800f, 360f))
        place(800.dp, 360.dp, rtl = false)
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertTrue(
            "page left of rail: page.right=${page.right} rail.left=${rail.left}",
            page.right.value <= rail.left.value + 1f,
        )
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun phonePortraitKeepsBottomDockGeometry() {
        assertTrue(!HomeGeometry.useRail(320f, 640f))
        assertTrue(!HomeGeometry.useRail(411f, 731f))
        assertTrue(!HomeGeometry.useRail(600f, 960f))
        assertTrue(!HomeGeometry.useRail(840f, 1200f))
    }
}
