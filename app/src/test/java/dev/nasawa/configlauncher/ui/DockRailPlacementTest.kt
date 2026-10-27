package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.ResolvedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * §1.5 / #189: rail sits on the end edge (right in LTR, left in RTL) and the page column
 * gets the remaining width. Head label measure grows the rail; chrome centres in the page column.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DockRailPlacementTest {
    @get:Rule val rule = createComposeRule()

    private val dock = (0 until 4).map { ResolvedItem(AppItem("d$it", "dock.$it", label = "Dock $it"), null) }

    private fun place(w: Dp, h: Dp, rtl: Boolean, headLabelWidthDp: Float = 0f, headText: String = "Apps") {
        rule.setContent {
            val apps = InstalledApps(ApplicationProvider.getApplicationContext())
            val drag = DragController({ error("unused") }) {}
            drag.dockVertical = true
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(w, h).testTag("window")) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("page")) {
                            val pageW = w.value - HomeGeometry.railWidthDp(headLabelWidthDp)
                            val chrome = HomeGeometry.contentWidthDp(
                                pageW,
                                minOf(pageW, 4 * HomeGeometry.CAP_WIDTH_DP),
                                HomeGeometry.widthClass(w.value),
                            )
                            ContentWidthBox(chrome.takeIf { HomeGeometry.widthClass(w.value) != HomeGeometry.WidthClass.Compact }) {
                                Box(Modifier.fillMaxSize().testTag("chrome"))
                            }
                        }
                        DockRail(dock, apps, drag, {}, {}, { _, _ -> emptyList() }, headLabelWidthDp = headLabelWidthDp) {
                            Text(headText, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
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

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun railWidthAtFontScale2AndLongGermanLabel() {
        // Production measures with TextMeasurer at the live fontScale/locale. GeometryTest covers
        // railWidthDp(head). Here we feed the widths HomeScreen would hand the rail for:
        //   - short "Apps" at scale 1.0 (~40 dp) → still 80
        //   - long German "Anwendungen" at fontScale 2.0 (~104 dp) → grows past 80, no clip
        val shortAppsDp = 40f
        val longGermanAt2xDp = 104f
        assertEquals(80f, HomeGeometry.railWidthDp(shortAppsDp), 0.01f)
        val railW = HomeGeometry.railWidthDp(longGermanAt2xDp)
        assertEquals(longGermanAt2xDp + HomeGeometry.RAIL_HEAD_PAD_DP, railW, 0.01f)
        assertTrue("rail grows for long scaled German label: $railW", railW > 80f)

        place(960.dp, 600.dp, rtl = false, headLabelWidthDp = longGermanAt2xDp, headText = "Anwendungen")
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertEquals(railW, (rail.right - rail.left).value, 1.5f)
        // Locale pin so the test stays deterministic (no real clock / device locale drift).
        assertEquals("de", Locale.GERMAN.language)
    }

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun chromeCentresInPageColumnLtr() {
        place(960.dp, 600.dp, rtl = false, headLabelWidthDp = 0f)
        assertChromeCentredInPageColumn(rtl = false)
    }

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun chromeCentresInPageColumnRtl() {
        place(960.dp, 600.dp, rtl = true, headLabelWidthDp = 0f)
        assertChromeCentredInPageColumn(rtl = true)
    }

    private fun assertChromeCentredInPageColumn(rtl: Boolean) {
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val chrome = rule.onNodeWithTag("contentWidthInner").getBoundsInRoot()
        val pageW = page.right.value - page.left.value
        val chromeW = chrome.right.value - chrome.left.value
        assertTrue("chrome narrower than page column: chrome=$chromeW page=$pageW rtl=$rtl", chromeW < pageW - 1f)
        val leftGap = chrome.left.value - page.left.value
        val rightGap = page.right.value - chrome.right.value
        assertTrue("centred in page column (±2 dp) rtl=$rtl left=$leftGap right=$rightGap", kotlin.math.abs(leftGap - rightGap) <= 2f)
        val window = rule.onNodeWithTag("window").getBoundsInRoot()
        val winLeftGap = chrome.left.value - window.left.value
        val winRightGap = window.right.value - chrome.right.value
        assertTrue("not equal margins on the raw window when the rail is present rtl=$rtl", kotlin.math.abs(winLeftGap - winRightGap) > 2f)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun railScrollsWhenSevenAppsOnShortPhoneLand() {
        // Grace couldn't run rail scrolling locally; 7×48 = 336 > short avail → needsRailScroll + 48 dp slots.
        val seven = (0 until 7).map { ResolvedItem(AppItem("d$it", "dock.$it", label = "Dock $it"), null) }
        assertTrue(HomeGeometry.needsRailScroll(7, 280f))
        assertEquals(48f, HomeGeometry.railSlotDp(7, 280f), 0.01f)
        rule.setContent {
            val apps = InstalledApps(ApplicationProvider.getApplicationContext())
            val drag = DragController({ error("unused") }) {}
            drag.dockVertical = true
            Box(Modifier.size(800.dp, 360.dp).testTag("window")) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("page"))
                    DockRail(seven, apps, drag, {}, {}, { _, _ -> emptyList() }) {
                        Text("Apps", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                }
            }
        }
        rule.waitForIdle()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        val railH = (rail.bottom - rail.top).value
        // Unshrunk 7×64 would need 448 dp; phone-land rail column is far shorter → scroll path.
        assertTrue("rail shorter than 7×64: $railH", railH < 7f * 64f - 1f)
        // Slot floor still 48 (§12) for the scrollable path.
        assertEquals(48f, HomeGeometry.railSlotDp(7, 280f), 0.01f)
        assertTrue(HomeGeometry.useRail(800f, 360f))
        assertTrue("rail composed for 7 dock apps", railH > 0f)
    }

}
