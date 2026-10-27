package dev.nasawa.configlauncher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.model.TitleBarModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZonedDateTime

/**
 * #173 L1 and the 320 dp label rule (Pragma's #175, ux.md §6.1 notice row and §12): the collapsed chips share the page
 * label's row instead of a strip of their own, and a grid label is shown whole or hidden, never cut through the letters.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class NoticeRowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dp get() = rule.density.density

    // ---- placement (pure) ----

    @Test fun placeCentresTheLabelAndKeepsTheChipsAtTheEnd() {
        // 320 wide, a 64 chip: chips at 320 − 12 − 64 = 244; a 100 label is centred (110) and ends 8 short of 244.
        val p = NoticeRow.place(320, 64, 100, 12, 8, 16)
        assertEquals(244, p.chipsX)
        assertEquals(244 - 8 - 16, p.labelMax)
        assertEquals(110, p.labelX)
    }

    @Test fun placeShiftsAWideLabelTowardTheStartThenCapsIt() {
        // 200 doesn't fit centred (60..260 runs past 236): it ends at 236 instead. Wider than 220: capped at 220, from 16.
        assertEquals(36, NoticeRow.place(320, 64, 200, 12, 8, 16).labelX)
        val wide = NoticeRow.place(320, 64, 500, 12, 8, 16)
        assertEquals(16, wide.labelX); assertEquals(220, wide.labelMax)
        assertEquals("no room at all", 0, NoticeRow.place(60, 64, 100, 12, 8, 16).labelMax)
    }

    // ---- the row ----

    private val two = listOf(ResolvedPage("home", "Home", emptyList()), ResolvedPage("second", "Second", emptyList()))
    private val chipSaid get() = ctx.resources.getQuantityString(R.plurals.chip_review_a11y, 3, 3)

    @Test fun chipAndPageLabelShareOneRowWith48dpTargets() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    NoticeRow({ PageLabelButton(two, 0, enabled = true, backdrop = false) {} }, Modifier.testTag("row")) { ReviewCountChip(3, {}) }
                    Box(Modifier.fillMaxSize().testTag("grid"))
                }
            }
        }
        val row = rule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val grid = rule.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        val chip = rule.onNodeWithContentDescription(chipSaid).fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithContentDescription(ctx.getString(R.string.page_a11y, "Home", 1, 2)).fetchSemanticsNode().boundsInRoot
        assertEquals("row is 48 dp", 48f, row.height / dp, 0.5f)
        // Chip: a 48 × 48 dp touch area inside the row, 12 dp from the end; never over the grid.
        assertTrue("chip ${chip.width / dp} × ${chip.height / dp} dp", chip.width / dp >= 47.5f && chip.height / dp >= 47.5f)
        assertTrue("chip inside the row", chip.top >= row.top - 0.5f && chip.bottom <= row.bottom + 0.5f)
        assertEquals("12 dp end margin", 12f, (row.right - chip.right) / dp, 0.5f)
        assertTrue("chip above the grid, not over it", chip.bottom <= grid.top + 0.5f)
        // Page label in the same row, centred, 48 dp tall, its touch area ending at least 8 dp short of the chip.
        assertEquals("label centred", row.center.x, label.center.x, 1f)
        assertTrue("label 48 dp tall", label.height / dp >= 47.5f)
        assertTrue("label in the row", label.top >= row.top - 0.5f && label.bottom <= row.bottom + 0.5f)
        assertTrue("8 dp short of the chip: ${(chip.left - label.right) / dp}", (chip.left - label.right) / dp >= 7.5f)
    }

    @Test fun twoChipsAre8dpApartAndALongLabelStops8dpShort() {
        val long = listOf(ResolvedPage("home", "A very long page title that won't fit at 320 dp", emptyList()), two[1])
        rule.setContent {
            MaterialTheme {
                NoticeRow({ PageLabelButton(long, 0, enabled = true, backdrop = false) {} }, Modifier.testTag("row")) {
                    ReviewCountChip(3, {})
                    Box(Modifier.size(48.dp).testTag("warn"))   // stand-in for the B-banner ▲ chip
                }
            }
        }
        val count = rule.onNodeWithContentDescription(chipSaid).fetchSemanticsNode().boundsInRoot
        val warn = rule.onNodeWithTag("warn").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        assertTrue("count chip to the start of ▲", count.right <= warn.left)
        assertEquals("8 dp apart", 8f, (warn.left - count.right) / dp, 0.5f)
        assertEquals("12 dp end margin", 12f, (row.right - warn.right) / dp, 0.5f)
        val label = labelNode().boundsInRoot
        assertEquals("8 dp short of the first chip", 8f, (count.left - label.right) / dp, 0.5f)
        assertTrue("from 16 dp", label.left / dp >= 15.5f)
    }

    private fun labelNode(): SemanticsNode = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes()
        .single { SemanticsActions.OnClick in it.config && it.config.getOrElseNullable(SemanticsProperties.Role) { null } == androidx.compose.ui.semantics.Role.Button
            && it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { d -> d.contains("page 1") } == true }

    // ---- home at 320 dp: grid height and whole-or-hidden labels ----

    private val names = listOf("Calendar", "Messages", "Camera", "Photos", "Settings", "Clock", "Maps", "Weather",
        "Contacts", "Phone", "Gmail", "Drive", "Notes", "Files", "Music", "Podcasts", "Wallet", "Health", "Translate", "Keep")
    private val grid = Grid(4, 5)
    private val items = names.mapIndexed { i, n -> ResolvedItem(AppItem("a$i", "pkg.$i", label = n, x = i % 4, y = i / 4), null) }
    private val dock = (0 until 4).map { ResolvedItem(AppItem("d$it", "dock.$it", label = "Dock $it"), null) }

    /** Grace's A51 title bar (TitleBarRenderTest; cl-qa dev6-recheck/a51-launcher.json). */
    private val a51Bar = TitleBarModel.forPage((ConfigCodec.decode("""{"schemaVersion":1,"grid":{"columns":4,"rows":5},
        "pages":[{"id":"home","title":"Home","items":[]},{"id":"second","title":"Second","items":[]}],
        "elements":[{"id":"t-main","type":"titleBar","anchor":"top",
          "content":{"slots":{"start":"{date:EEE d MMM yyyy}","center":"{page}","end":"{time:HH:mm}"}},
          "style":{"size":"large","color":"light","padding":{"horizontal":16,"vertical":6},"backdrop":{"color":"#000000","opacity":60,"radius":12}}}]}""")
        as ParseResult.Ok).config, "home")!!

    /** HomeScreen's column (top bar, banners, notice row or page label / title bar, grid, dots, dock) without the view model. */
    @Composable
    private fun Home(pages: List<ResolvedPage>, chip: Boolean, b9: Boolean, bar: TitleBarModel.Bar?, drag: DragController, apps: InstalledApps) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(48.dp))
            if (b9) B9Banner(3, {}, {})
            val chips: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = { ReviewCountChip(3, {}) }
            if (bar != null) {
                if (chip) NoticeRow(null, chips = chips)
                // Pin the bar's clock: a live date can change the overflow ladder (and thus bar height) by weekday width.
                CompositionLocalProvider(LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z")) {
                    TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false) {}
                }
            } else if (chip) NoticeRow(if (PageList.showLabel(pages)) { { PageLabelButton(pages, 0, true, false) {} } } else null, chips = chips)
            else PageLabel(pages, 0, true, false) {}
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PageGrid(pages[0], grid, apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
            }
            if (pages.size > 1) PageDots(pages.size, 0)
            Dock(dock, apps, drag, {}, {}, { _, _ -> emptyList() })
        }
    }

    private fun drag() = DragController({ error("unused") }) {}
    private fun withItems(pages: List<ResolvedPage>) = listOf(pages[0].copy(items = items)) + pages.drop(1)

    @Test fun aChipCostsNoGridHeightWhenThePageLabelShows() {
        val apps = InstalledApps(ctx)
        val pages = withItems(two)
        val drag = drag()
        var chip by mutableIntStateOf(0)
        rule.setContent { MaterialTheme { Home(pages, chip == 1, false, null, drag, apps) } }
        rule.waitForIdle(); val without = drag.gridRect
        chip = 1; rule.waitForIdle(); val with = drag.gridRect
        assertEquals("same grid with and without the chip", without.height, with.height, 0.5f)
        assertEquals(without.top, with.top, 0.5f)
        // The chip sits above the grid's top-end cell, not over its icon.
        assertTrue(rule.onNodeWithContentDescription(chipSaid).fetchSemanticsNode().boundsInRoot.bottom <= with.top + 0.5f)
    }

    @Test fun withNoPageLabelTheRowTakes48dpOnlyWhileAChipIsUp() {
        val apps = InstalledApps(ctx)
        val pages = listOf(ResolvedPage("home", null, items))
        val drag = drag()
        var chip by mutableIntStateOf(0)
        rule.setContent { MaterialTheme { Home(pages, chip == 1, false, null, drag, apps) } }
        rule.waitForIdle(); val without = drag.gridRect
        chip = 1; rule.waitForIdle(); val with = drag.gridRect
        assertEquals(48f, (without.height - with.height) / dp, 0.5f)
    }

    /** Every grid label is drawn whole inside its cell, or not at all (and then still spoken). Returns how many showed. */
    private fun assertWholeOrHidden(drag: DragController, full: Float, what: String): Int {
        val nodes = rule.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        val cellH = drag.gridRect.height / grid.rows
        var shown = 0
        names.forEachIndexed { i, n ->
            val text = nodes.filter { it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { t -> t.text == n } == true }
            val top = drag.gridRect.top + cellH * (i / 4)
            text.forEach { t ->
                val r = Rect(t.positionInRoot, androidx.compose.ui.geometry.Size(t.size.width.toFloat(), t.size.height.toFloat()))
                assertTrue("$what: '$n' is ${r.height / dp} dp of a ${full / dp} dp line", r.height >= full - 0.5f)
                assertTrue("$what: '$n' ($r) inside its cell (${top}..${top + cellH})", r.top >= top - 0.5f && r.bottom <= top + cellH + 0.5f)
                shown++
            }
            if (text.isEmpty()) assertTrue("$what: hidden '$n' still spoken",
                nodes.any { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.contains(n) == true })
        }
        return shown
    }

    /**
     * Grace's A51 at 320 dp (#173): the title bar config, with the count chip and (separately: B9 shows only when no other
     * consent notice does) the B9 banner showing, over every height from 711 dp down to 480 dp, plus the page-label
     * layout with the chip. No label is ever partly clipped; at 711 dp all show, and short screens hide some.
     */
    @Test fun a51At320dpLabelsAreWholeOrHiddenWithTheChipOrB9() {
        val apps = InstalledApps(ctx)
        val pages = withItems(two)
        val drag = drag()
        var mode by mutableIntStateOf(0)
        var height by mutableIntStateOf(711)
        val ref = ResolvedItem(AppItem("ref", "pkg.ref", label = "Reference"), null)
        rule.setContent {
            MaterialTheme {
                Column {
                    Box(Modifier.width(80.dp)) { Tile(ref, apps) }   // one full label line, before the home column so it has room
                    Box(Modifier.width(320.dp).requiredHeight(height.dp)) {
                        when (mode) {
                            0 -> Home(pages, chip = true, b9 = false, bar = a51Bar, drag, apps)
                            1 -> Home(pages, chip = false, b9 = true, bar = a51Bar, drag, apps)
                            else -> Home(pages, chip = true, b9 = false, bar = null, drag, apps)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val full = rule.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
            .single { it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { t -> t.text == "Reference" } == true }.size.height.toFloat()
        assertTrue(full / dp >= 12f)
        for (m in 0..2) {
            mode = m
            height = 711; rule.waitForIdle()
            assertTrue("mode $m at 711 dp: chip or B9 is up", m == 1 || rule.onAllNodes(isRoot().not()).fetchSemanticsNodes().any {
                it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.contains(chipSaid) == true })
            assertEquals("mode $m: every label shows at 711 dp", names.size, assertWholeOrHidden(drag, full, "mode $m at 711 dp"))
            var hidden = false
            for (h in 711 downTo 480 step 3) {
                height = h; rule.waitForIdle()
                if (assertWholeOrHidden(drag, full, "mode $m at $h dp") < names.size) hidden = true
            }
            assertTrue("mode $m: short screens hide labels rather than clip them", hidden)
        }
    }
}
