package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.model.TitleBarModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * #201 Grace blockers: phone-land tiles ≥ 48×48 after the chrome ladder, and item/home sheets
 * show every action at the short landscape height (skipPartiallyExpanded).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneLandLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = InstalledApp("com.android.settings", "com.android.settings.Settings", "Settings")
    private val grid = Grid(columns = 4, rows = 5)

    private fun pageWithApps(n: Int = 5): ResolvedPage {
        val items = (0 until n).map { i ->
            ResolvedItem(
                AppItem("a$i", settings.packageName, x = i % 4, y = i / 4, label = "App $i"),
                settings,
            )
        }
        return ResolvedPage("p0", "Home", items)
    }

    /** Fixture-like title bar with padding.vertical = 6 (the G3 regression trigger). */
    private val fixtureBar: TitleBarModel.Bar = TitleBarModel.forPage(
        (ConfigCodec.decode(
            """{"schemaVersion":1,"grid":{"columns":4,"rows":5},
            "pages":[{"id":"home","title":"Home","items":[]}],
            "elements":[{"id":"t-main","type":"titleBar","anchor":"top",
              "content":{"slots":{"start":"{date:EEE d MMM yyyy}","center":"{page}","end":"{time:HH:mm}"}},
              "style":{"size":"large","color":"light","padding":{"horizontal":16,"vertical":6},
                "backdrop":{"color":"#000000","opacity":60,"radius":12}}}]}""",
        ) as ParseResult.Ok).config,
        "home",
    )!!

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun phoneLandTilesAtLeast48() {
        // Page column after rail (80) and safeDrawing T/B (~24): 720 × 312.
        // Short page column absorbs padding.vertical=6 → bar 48; dots ~20 → grid ≈ 244 → pitchH ≥ 48.
        assertEquals(0, HomeGeometry.titleBarPaddingV(6, 312f))
        val apps = InstalledApps(ctx)
        val drag = DragController({ error("unused") }) {}
        val pages = listOf(pageWithApps(5), ResolvedPage("p1", "Two", emptyList()))
        rule.setContent {
            MaterialTheme {
                // Pin the clock so weekday width can't change the bar height.
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    Column(Modifier.size(720.dp, 312.dp).testTag("pageCol")) {
                        TitleBarRow(fixtureBar, pages, 0, enabled = true, labelBackdrop = false) {}
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("gridSlot")) {
                            PageGrid(pages[0], grid, apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
                        }
                        PageDots(pages.size, 0)
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = ctx.resources.displayMetrics.density
        val gridH = drag.gridRect.height / density
        assertTrue("grid height $gridH dp should fit 5×48", gridH >= 5f * HomeGeometry.MIN_TOUCH_DP - 1f)
        val pitch = HomeGeometry.pitch(720f, gridH, 4, 5, HomeGeometry.WidthClass.Medium, density)
        assertTrue("pitchH ≥ 48: ${pitch.pitchH}", pitch.pitchH >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
        assertTrue("W ≤ 1.25·H: ${pitch.pitchW}/${pitch.pitchH}", pitch.pitchW <= 1.25f * pitch.pitchH + 0.01f)

        var checked = 0
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val b = n.boundsInRoot
            val w = b.width / density
            val h = b.height / density
            if (SemanticsActions.OnClick in n.config && w in 40f..160f && h in 40f..160f) {
                assertTrue("tile width $w ≥ 48", w >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
                assertTrue("tile height $h ≥ 48", h >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
                checked++
            }
            n.children.forEach(::walk)
        }
        walk(rule.onNodeWithTag("gridSlot").fetchSemanticsNode())
        assertTrue("expected several home tiles, got $checked", checked >= 4)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun dock7PhoneLandPitchStillMeetsTouchFloor() {
        val p = HomeGeometry.pitch(720f, 244f, 4, 5, HomeGeometry.WidthClass.Medium)
        assertTrue(p.pitchH >= 48f - 0.01f)
        assertTrue(HomeGeometry.useRail(800f, 360f))
        assertTrue(HomeGeometry.needsRailScroll(7, 300f))
        // Rail slots stay ≥ 48 even when scrolling.
        assertEquals(48f, HomeGeometry.railSlotDp(7, 300f), 0.01f)
    }

    @Test @Config(qualifiers = "w960dp-h600dp-mdpi")
    fun t7LandPitchStillAbout85To92() {
        val pitchH = HomeGeometry.t7LandPitchH()
        assertTrue("t7-land advisory ~85, got $pitchH", pitchH in 84f..92f)
        val live = HomeGeometry.pitch(960f - 80f, 600f - 175f, 4, 5, HomeGeometry.WidthClass.Expanded)
        assertTrue(live.pitchH in 84f..92.5f)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun itemSheetShowsAllActionsAtPhoneLandHeight() {
        val item = ResolvedItem(AppItem("a", settings.packageName, label = "QA Settings"), settings)
        rule.setContent {
            MaterialTheme { ItemSheet(item, onAction = {}, onDismiss = {}) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Edit layout").assertIsDisplayed()
        rule.onNodeWithText("App info").assertIsDisplayed()
        rule.onNodeWithText("Remove from home").assertIsDisplayed()
        rule.onNodeWithText("QA Settings").assertExists()
        rule.onNodeWithText("com.android.settings").assertExists()
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun homeMenuSheetShowsAllActionsAtPhoneLandHeight() {
        rule.setContent {
            MaterialTheme {
                HomeMenuSheet(pageCount = 2, reviewCount = 0, onAction = {}, onDismiss = {})
            }
        }
        rule.waitForIdle()
        for (label in listOf("Edit layout", "Pages", "Reload layout", "Settings", "Wallpaper")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun itemSheetHasNoPartialExpandedStop() {
        var state: SheetState? = null
        rule.setContent {
            MaterialTheme {
                state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet({}, sheetState = state!!) {
                    Column {
                        Text("Edit layout")
                        Text("App info")
                        Text("Remove from home")
                    }
                }
            }
        }
        rule.waitForIdle()
        val s = state!!
        assertFalse(s.hasPartiallyExpandedState)
        assertEquals(SheetValue.Expanded, s.currentValue)
        // All three actions visible (not clipped by a half sheet).
        for (label in listOf("Edit layout", "App info", "Remove from home")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    /**
     * Pragma #201 note 1: a 48 dp icon in a ~48 dp cell leaves no vertical gap. Neighbouring
     * tile hit areas (and their icons, which fill the cell on step 4) must not overlap at
     * densities other than 1.0 — density rounding used to let a 48.dp glyph paint into the next row.
     */
    private fun assertPhoneLandTilesDoNotOverlap() {
        val apps = InstalledApps(ctx)
        val drag = DragController({ error("unused") }) {}
        // Fill 2 rows so we have vertical neighbours; 8 apps is enough.
        val pages = listOf(pageWithApps(8))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    // Short page column forces step 4 (48 icon, labels hidden).
                    Column(Modifier.size(720.dp, 312.dp).testTag("pageCol")) {
                        TitleBarRow(fixtureBar, pages, 0, enabled = true, labelBackdrop = false, pageColumnHeightDp = 312f) {}
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("gridSlot")) {
                            PageGrid(pages[0], grid, apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
                        }
                        PageDots(pages.size, 0)
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = ctx.resources.displayMetrics.density
        val tiles = mutableListOf<Rect>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val b = n.boundsInRoot
            val w = b.width / density
            val h = b.height / density
            if (SemanticsActions.OnClick in n.config && w in 40f..160f && h in 40f..160f) {
                tiles += b
            }
            n.children.forEach(::walk)
        }
        walk(rule.onNodeWithTag("gridSlot").fetchSemanticsNode())
        assertTrue("expected several home tiles, got ${tiles.size} @ density=$density", tiles.size >= 4)
        // Pairwise: interiors must not overlap. Sharing an edge (right==left or bottom==top) is fine.
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                val a = tiles[i]
                val b = tiles[j]
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertFalse(
                    "tiles overlap by ${overlapW}×${overlapH}px @ density=$density: $a vs $b",
                    overlapW > 1f && overlapH > 1f,
                )
            }
        }
        // Icons fill the cell on step 4: pitchH ≈ 48 and each tile is ≥ 48×48.
        for (t in tiles) {
            val w = t.width / density
            val h = t.height / density
            assertTrue("tile $w×$h ≥ 48 @ density=$density", w >= HomeGeometry.MIN_TOUCH_DP - 0.5f && h >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
        }
    }

    @Test @Config(qualifiers = "w800dp-h360dp-hdpi")
    fun phoneLandTilesDoNotOverlapAtDensity1_5() = assertPhoneLandTilesDoNotOverlap()

    @Test @Config(qualifiers = "w800dp-h360dp-420dpi")
    fun phoneLandTilesDoNotOverlapAtDensity2_625() = assertPhoneLandTilesDoNotOverlap()

    @Test @Config(qualifiers = "w800dp-h360dp-560dpi")
    fun phoneLandTilesDoNotOverlapAtDensity3_5() = assertPhoneLandTilesDoNotOverlap()

    /**
     * Pragma #201 note 3: titleBarPaddingV keys off the measured page-column height.
     * A tall screen (800 dp) with a short multi-window page column (312 dp) must absorb
     * padding.vertical the same way phone-land does.
     */
    @Test @Config(qualifiers = "w800dp-h800dp-mdpi")
    fun shortMultiWindowPageColumnAbsorbsTitleBarPadding() {
        // Screen is tall, so Configuration.screenHeightDp alone would keep padding=6.
        assertEquals(6, HomeGeometry.titleBarPaddingV(6, 800f))
        // Measured page column is short → absorb.
        assertEquals(0, HomeGeometry.titleBarPaddingV(6, 312f))
        val apps = InstalledApps(ctx)
        val drag = DragController({ error("unused") }) {}
        val pages = listOf(pageWithApps(5), ResolvedPage("p1", "Two", emptyList()))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    Column(Modifier.size(720.dp, 312.dp).testTag("pageCol")) {
                        TitleBarRow(
                            fixtureBar, pages, 0, enabled = true, labelBackdrop = false,
                            pageColumnHeightDp = 312f,
                        ) {}
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("gridSlot")) {
                            PageGrid(pages[0], grid, apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
                        }
                        PageDots(pages.size, 0)
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = ctx.resources.displayMetrics.density
        val gridH = drag.gridRect.height / density
        assertTrue("grid height $gridH dp should fit 5×48 with absorbed pad", gridH >= 5f * HomeGeometry.MIN_TOUCH_DP - 1f)
        var checked = 0
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val b = n.boundsInRoot
            val w = b.width / density
            val h = b.height / density
            if (SemanticsActions.OnClick in n.config && w in 40f..160f && h in 40f..160f) {
                assertTrue("tile height $h ≥ 48", h >= HomeGeometry.MIN_TOUCH_DP - 0.5f)
                checked++
            }
            n.children.forEach(::walk)
        }
        walk(rule.onNodeWithTag("gridSlot").fetchSemanticsNode())
        assertTrue("expected several home tiles, got $checked", checked >= 4)
    }


    /**
     * Grace #201 T4: 800×312 wm-size pane (split-phone-v1, density 160, status/nav 24 dp).
     * Device dump: title-bar clickable 73×45 while the content node stayed 48 — first-row
     * tiles with pitchH ~39 had their min-touch a11y bounds climb to y=69 and clip the
     * clickable. Reproduce with top inset + a real PageGrid, then assert the *clickable*
     * semantics node's bounds (not the content desc) stay ≥ 48 and tiles do not encroach.
     */
    @Test @Config(qualifiers = "w800dp-h312dp-mdpi")
    fun splitPhoneTitleBarTouchAtLeast48() {
        // Page column after insets is 264 dp — same absorb as a bare 312 column.
        assertEquals(0, HomeGeometry.titleBarPaddingV(6, 264f))
        assertEquals(1f, rule.density.density, 0.01f)
        val apps = InstalledApps(ctx)
        val drag = DragController({ error("unused") }) {}
        // Several first-row apps so a short pitchH would expand tile touch into the bar.
        val pages = listOf(pageWithApps(4), ResolvedPage("p1", "Second", emptyList()))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    // 800×312 window with status/nav 24 dp — matches split-phone-v1 insets.
                    Column(
                        Modifier
                            .size(800.dp, 312.dp)
                            .windowInsetsPadding(WindowInsets(0.dp, 24.dp, 0.dp, 24.dp))
                            .testTag("pageCol"),
                    ) {
                        TitleBarRow(
                            fixtureBar, pages, 0, enabled = true, labelBackdrop = false,
                            pageColumnHeightDp = 264f,
                        ) {}
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("gridSlot")) {
                            PageGrid(pages[0], grid, apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
                        }
                        PageDots(pages.size, 0)
                    }
                }
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val shown = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced && it.boundsInRoot.width > 0 }
        fun said(n: androidx.compose.ui.semantics.SemanticsNode) =
            n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                .orEmpty().joinToString()
        val spoken = shown.firstOrNull { said(it) == "Home, Pages" }
        assertTrue("expected spoken Home, Pages node", spoken != null)
        // Clickable wrapper = OnClick node covering the slot centre (T4 harness rule).
        val pageTap = shown.filter {
            SemanticsActions.OnClick in it.config &&
                it.boundsInRoot.overlaps(spoken!!.boundsInRoot)
        }
        assertTrue("expected a tappable {page} clickable, descs=${shown.map(::said).filter { it.isNotEmpty() }.take(8)}", pageTap.isNotEmpty())
        val clickable = pageTap.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        val boxH = clickable.boundsInRoot.height / density
        val touchH = clickable.touchBoundsInRoot.height / density
        assertTrue(
            "title bar {page} clickable bounds height $boxH dp (touch $touchH) must be ≥ 48 at 800×312",
            boxH >= HomeGeometry.MIN_TOUCH_DP - 0.5f,
        )
        assertTrue(
            "title bar {page} clickable touch height $touchH dp must be ≥ 48",
            touchH >= HomeGeometry.MIN_TOUCH_DP - 0.5f,
        )
        // Tiles must not claim a11y space inside the title bar's clickable band (device clip root cause).
        val barBottom = clickable.boundsInRoot.bottom
        val gridRoot = rule.onNodeWithTag("gridSlot").fetchSemanticsNode()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            if (SemanticsActions.OnClick in n.config) {
                val t = n.touchBoundsInRoot
                val w = t.width / density
                val h = t.height / density
                if (w in 40f..160f && h in 40f..160f && t.top + 0.5f < barBottom && t.bottom > barBottom) {
                    assertTrue(
                        "tile touch $t encroaches into title bar clickable (barBottom=$barBottom); " +
                            "device a11y would clip the {page} clickable under 48 dp",
                        false,
                    )
                }
            }
            n.children.forEach(::walk)
        }
        walk(gridRoot)
    }

}
