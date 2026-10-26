package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.TitleBarModel
import dev.nasawa.configlauncher.model.TitleBarModel.Slot
import dev.nasawa.configlauncher.ui.TitleBarLayout.Rung
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * #149: the ux.md §15 overflow ladder. Widths are in dp (1 px/dp here). Text is measured as characters × a per-character
 * advance, scaled by the size, which is how Grace's 2.0 px/dp Roboto numbers come out (181 dp for `Mon 19 Oct 2026`).
 */
class TitleBarLadderTest {
    private val day = ZonedDateTime.of(2026, 10, 19, 12, 34, 0, 0, ZoneOffset.UTC)
    private val gap = TitleBarLayout.GAP_DP
    private val pageMin = TitleBarLayout.PAGE_MIN_DP
    private val touch = TitleBarLayout.TOUCH_DP
    private val arrow = 16 // ▾ (12 dp) + its 4 dp gap

    /** `{date}` as the bar shows it at [level] (null pattern: the locale medium date, then short). */
    private fun date(p: String?, level: Int): String =
        if (p == null) DateTimeFormatter.ofLocalizedDate(if (level > 0) FormatStyle.SHORT else FormatStyle.MEDIUM).withLocale(Locale.US).format(day)
        else DateTimeFormatter.ofPattern(TitleBarLayout.shortDate(p, level), Locale.US).format(day)

    /**
     * Bar as Grace's repro: date start (fixed), `{page}` button center, clock end (fixed). [perChar] is the advance at
     * the bar's size ([sizeSp]); step 3 measures at [smallerSp]. [pageText] is the page button's text width with its ▾.
     */
    private fun measure(pattern: String?, perChar: Double, clock: Int, pageText: Int, sizeSp: Double = 24.0, smallerSp: Double = 18.0) = { r: Rung ->
        val f = if (r.smaller) smallerSp / sizeSp else 1.0
        listOf((date(pattern, r.dateLevel).length * perChar * f).roundToInt(), if (r.arrowOnly) arrow else (pageText * f).roundToInt(), (clock * f).roundToInt())
    }

    private fun ladder(w: Int, pattern: String?, measure: (Rung) -> List<Int>, named: Boolean = true) =
        TitleBarLayout.ladder(w, gap, setOf(0, 2), setOf(0), 1, pageMin, touch,
            TitleBarLayout.rungs(true, TitleBarLayout.dateLevels(listOf(pattern)), named), measure = measure)

    /** The clock (end slot) gets at least the [clock] width it was measured at on the chosen rung: never truncated. */
    private fun assertClockWhole(fit: TitleBarLayout.Fit, clock: Int) {
        assertEquals("clock measured at", clock, fit.want[2])
        assertTrue("the clock never truncates: ${fit.widths.third} < $clock", fit.widths.third >= clock)
    }

    /** #157: the slots and the two 8 dp gaps fill the bar exactly. */
    private fun assertGaps(fit: TitleBarLayout.Fit, w: Int) {
        val (a, b) = TitleBarLayout.gaps(fit.want[0], fit.want[1], fit.want[2], gap)
        assertEquals(8 to 8, a to b)
        assertEquals(w, fit.widths.first + fit.widths.second + fit.widths.third + a + b)
    }

    @Test fun gracesRobotoRepro() {
        // Grace (emulator, stock Roboto, 320 dp at 2.0 px/dp): date 181, page 31.5, clock 59.5 in a 272 dp bar.
        val pattern = "EEE d MMM yyyy"
        assertEquals("Mon 19 Oct 2026", date(pattern, 0))
        val m = measure(pattern, 181.0 / 15, clock = 60, pageText = 60)
        assertEquals(listOf(181, 60, 60), m(Rung(0)))
        // As written it doesn't fit: 181 + 32 + 60 + 2 × 8 = 289 > 272, which is what sent widths() to the proportional cut.
        assertFalse(TitleBarLayout.fits(272, m(Rung(0)), setOf(0, 2), 1, pageMin, gap))
        val fit = ladder(272, pattern, m)
        // Step 1 can't help a centre button (its 48 dp touch area keeps 32 dp between the gaps); step 2 drops the weekday.
        assertEquals(2, fit.step); assertEquals(3, fit.rung.dateLevel); assertFalse(fit.cut)
        assertEquals("19 Oct 2026", date(pattern, fit.rung.dateLevel))
        assertEquals(133, fit.widths.first)
        assertClockWhole(fit, 60)
        assertTrue("the page keeps its 48 dp touch area", fit.touch.first + fit.widths.second + fit.touch.second >= touch)
        assertGaps(fit, 272)
    }

    @Test fun oneUiLikeWiderFont() {
        // The same bar with a font about 1.15× wider (One UI on Sprite's A51): date ~208, clock ~69 in 272 dp.
        val pattern = "EEE d MMM yyyy"
        val m = measure(pattern, 181.0 / 15 * 1.15, clock = 69, pageText = 69)
        assertEquals(208, m(Rung(0))[0])
        val fit = ladder(272, pattern, m)
        assertEquals(2, fit.step); assertClockWhole(fit, 69); assertGaps(fit, 272)
        // ux.md's A51 numbers with plain {date}: ~200 dp date, ~75 dp clock in ~285 dp; medium → short date.
        val plain = measure(null, 200.0 / 12, clock = 75, pageText = 60)
        assertEquals("Oct 19, 2026", date(null, 0)); assertEquals("10/19/26", date(null, 1))
        val a51 = ladder(285, null, plain)
        assertEquals(2, a51.step); assertEquals(1, a51.rung.dateLevel); assertClockWhole(a51, 75); assertGaps(a51, 285)
    }

    @Test fun widerFontWidthsThroughTitleBarLayoutWidths() {
        // Grok Bot's request: feed wider measured widths straight to widths(). Grace's Roboto widths × 1.0 … 1.3.
        for (scale in listOf(1.0, 1.15, 1.3)) {
            val date = (133 * scale).roundToInt(); val clock = (60 * scale).roundToInt()
            val w = 272
            val fits = date + pageMin + clock + 2 * gap <= w
            val (s, c, e) = TitleBarLayout.widths(w, date, 60, clock, fixed = setOf(0, 2), page = 1, pageMin = pageMin, gap = gap)
            if (fits) { assertTrue("×$scale date", s >= date); assertTrue("×$scale clock", e >= clock); assertTrue(c >= pageMin) }
            else assertTrue("×$scale: widths() alone cuts everything", s < date && e < clock)
            // The ladder never cuts the clock, at any scale.
            val pattern = "EEE d MMM yyyy"
            val fit = ladder(w, pattern, measure(pattern, 181.0 / 15 * scale, clock = clock, pageText = clock))
            assertTrue("×$scale: the clock never truncates", fit.widths.third >= fit.want[2])
        }
    }

    @Test fun step1HidesThePageText() {
        // One page: no ▾ and no action, so the {page} text is hidden (0 wide, no gap) and nothing else changes.
        val m = { r: Rung -> listOf(181, if (r.arrowOnly) 0 else 50, 60) }
        val rungs = TitleBarLayout.rungs(true, TitleBarLayout.dateLevels(listOf("EEE d MMM yyyy")), smaller = true)
        val fit = TitleBarLayout.ladder(250, gap, setOf(0, 2), setOf(0), null, pageMin, touch, rungs, measure = m)
        assertEquals(1, fit.step); assertEquals(Triple(181, 0, 61), fit.widths)
        assertNull("step 1 alone writes no warning", TitleBarLayout.fitWarning("p", 250, fit.step, null))
        // A page button at the bar's edge: its touch area can't reach out there, so that side keeps 16 dp (half of 32),
        // the side by the clock (not tappable) needs only 12 (half of 24): 28 dp.
        val side = TitleBarLayout.ladder(1000, gap, setOf(2), emptySet(), 0, pageMin, touch, listOf(Rung(1, arrowOnly = true))) { listOf(arrow, 0, 60) }
        assertEquals(28, side.want[0])
    }

    private val arrowMin = TitleBarLayout.ARROW_MIN_DP

    @Test fun step1ArrowDropsTo24WithA48dpTouchArea() {
        // Pragma: between two neighbours that aren't tappable, the ▾ shows in 24 dp; the touch area reaches 12 dp each
        // way, over the 8 dp gap and 4 dp onto the neighbour: 48 dp in all.
        val m = listOf(133, arrow, 60)
        assertEquals(arrowMin, TitleBarLayout.arrowOnlyMin(1, m, arrow, pageMin, arrowMin, emptySet()))
        val (b, a) = TitleBarLayout.touchReach(1, m, Triple(133, 24, 60), gap, touch, emptySet())
        assertEquals(12 to 12, b to a)
        assertEquals(touch, b + 24 + a)
        // Through the ladder: Grace's 272 dp bar now gives the button 24 dp at step 2.
        val fit = TitleBarLayout.ladder(233, gap, setOf(0, 2), setOf(0), 1, pageMin, touch, listOf(Rung(1, arrowOnly = true))) { m }
        assertEquals(1, fit.step); assertEquals(Triple(133, 24, 60), fit.widths); assertEquals(12 to 12, fit.touch)
        assertGaps(fit, 233)
        // Before step 1 the button keeps its text and gets no extra reach.
        assertEquals(0 to 0, TitleBarLayout.ladder(400, gap, setOf(0, 2), setOf(0), 1, pageMin, touch, listOf(Rung(0))) { listOf(133, 50, 60) }.touch)
    }

    @Test fun step1NeverTakesATappableNeighboursTouchArea() {
        val m = listOf(133, arrow, 60)
        // Tappable start neighbour: that side keeps 16 dp (half of 32) and the reach stops at the gap's midpoint (4 dp);
        // the other side makes up the rest, so it's still 48.
        assertEquals(28, TitleBarLayout.arrowOnlyMin(1, m, arrow, pageMin, arrowMin, setOf(0)))
        val (b, a) = TitleBarLayout.touchReach(1, m, Triple(133, 28, 60), gap, touch, setOf(0))
        assertEquals(gap / 2, b); assertEquals(touch, b + 28 + a)
        // Both neighbours tappable: 32 dp, reaching only to each gap's midpoint (40 dp touch; never over a target).
        assertEquals(pageMin, TitleBarLayout.arrowOnlyMin(1, m, arrow, pageMin, arrowMin, setOf(0, 2)))
        assertEquals(gap / 2 to gap / 2, TitleBarLayout.touchReach(1, m, Triple(133, 32, 60), gap, touch, setOf(0, 2)))
        // At the bar's edge nothing reaches out: the clock side takes it all (over the gap and onto the clock text).
        val edge = listOf(arrow, 0, 60)
        assertEquals(0 to 20, TitleBarLayout.touchReach(0, edge, Triple(28, 0, 60), gap, touch, emptySet()))
        // Never narrower than the ▾ itself.
        assertEquals(30, TitleBarLayout.arrowOnlyMin(1, listOf(133, 30, 60), 30, pageMin, arrowMin, emptySet()))
    }

    @Test fun sizeOnlyWarningWhenTheBarHasNoDate() {
        // No date: step 2 is skipped (no date levels), so the ladder goes 0, 1, 3.
        assertEquals(listOf(0, 1, 3), TitleBarLayout.rungs(true, TitleBarLayout.dateLevels(emptyList()), smaller = true).map { it.step })
        val m = { r: Rung -> if (r.smaller) listOf(90, if (r.arrowOnly) arrow else 50, 60) else listOf(120, if (r.arrowOnly) arrow else 50, 80) }
        val fit = TitleBarLayout.ladder(220, gap, setOf(0, 2), emptySet(), 1, pageMin, touch,
            TitleBarLayout.rungs(true, emptyList(), smaller = true), measure = m)
        assertEquals(3, fit.step)
        val path = TitleBarLayout.slotsPath("elements[0].content.slots.start")
        assertEquals("elements[0].content.slots", path)
        assertEquals("elements[0].content.slots: the title bar doesn't fit in its 220 dp on this phone, so fit step 3 of 4 was used: the text size went down to medium. Checked on this phone. Use fewer or shorter slots.",
            TitleBarLayout.fitWarning(path, 220, fit.step, "medium", hasDate = false))
        assertEquals("elements[0].content.slots: the title bar doesn't fit in its 220 dp on this phone, so fit step 3 of 4 was used: the text size went down to small. Checked on this phone. Use fewer or shorter slots.",
            TitleBarLayout.fitWarning(path, 220, 3, "small", hasDate = false))
        // Fits again: cleared. Step 1 alone: none.
        assertNull(TitleBarLayout.fitWarning(path, 400, 0, "medium", hasDate = false))
        assertNull(TitleBarLayout.fitWarning(path, 400, 1, "medium", hasDate = false))
        assertEquals("pages[1].overrides.t-main.content.slots", TitleBarLayout.slotsPath("pages[1].overrides.t-main.content.slots.end"))
    }

    @Test fun step3StepsANamedSizeDown() {
        // size large (24 sp), long pattern: even "Oct 19" doesn't fit 170 dp, medium (18 sp) does.
        val pattern = "EEEE, MMMM d"
        assertEquals(listOf(1, 2, 3), TitleBarLayout.dateLevels(listOf(pattern)))
        assertEquals(listOf("Monday, October 19", "Mon, October 19", "Mon, Oct 19", "Oct 19"), (0..3).map { date(pattern, it) })
        val m = measure(pattern, 12.0, clock = 60, pageText = 60)
        val fit = ladder(170, pattern, m)
        assertEquals(3, fit.step); assertTrue(fit.rung.smaller); assertFalse(fit.cut)
        assertClockWhole(fit, 45)
        assertEquals("medium", TitleBarLayout.smallerSize("large"))
        assertEquals("small", TitleBarLayout.smallerSize("medium"))
        assertNull("never below small", TitleBarLayout.smallerSize("small"))
        assertNull("numeric size skips step 3", TitleBarLayout.smallerSize(null))
        // A numeric size (no named step) goes from step 2 straight to step 4.
        val numeric = ladder(170, pattern, m, named = false)
        assertEquals(4, numeric.step); assertFalse(numeric.rung.smaller); assertClockWhole(numeric, 60)
    }

    @Test fun step4CutsTheDateNeverTheClock() {
        val pattern = "EEEE, MMMM d"
        val fit = ladder(130, pattern, measure(pattern, 12.0, clock = 60, pageText = 60))
        assertEquals(4, fit.step); assertTrue(fit.cut); assertTrue(fit.rung.smaller)
        assertClockWhole(fit, 45)
        // The date gives up exactly what's missing: 130 − 45 − 24 (the ▾ alone) − 16 = 45 of its 54.
        assertEquals(Triple(45, 24, 45), fit.widths)
        assertGaps(fit, 130)
    }

    @Test fun warningIsSetFromStep2AndClearedWhenItFits() {
        val pattern = "EEEE, MMMM d"
        val m = measure(pattern, 12.0, clock = 60, pageText = 60)
        val path = "elements[0].content.slots.start"
        fun warn(w: Int) = ladder(w, pattern, m).let { TitleBarLayout.fitWarning(path, w, it.step, "medium") }
        assertNull(warn(400))
        assertEquals("$path: the title bar doesn't fit in its 272 dp on this phone, so fit step 2 of 4 was used: the date was shortened. Checked on this phone. Use a shorter date, like {date:d MMM}.", warn(272))
        assertEquals("$path: the title bar doesn't fit in its 170 dp on this phone, so fit step 3 of 4 was used: the date was shortened and the text size went down to medium. Checked on this phone. Use a shorter date, like {date:d MMM}.", warn(170))
        assertEquals("$path: the title bar doesn't fit in its 130 dp on this phone, so fit step 4 of 4 was used: the date was shortened and cut off with …. Checked on this phone. Use a shorter date, like {date:d MMM}.", warn(130))
        // The ladder reruns on every layout: back to a wide bar (rotation), the warning clears.
        assertNull(warn(500))
        assertNull(TitleBarLayout.fitWarning(path, 300, 0, null))
        assertNull(TitleBarLayout.fitWarning(path, 300, 1, null))
    }

    @Test fun shortDatePatterns() {
        assertEquals("EEE, MMMM d", TitleBarLayout.shortDate("EEEE, MMMM d", 1))
        assertEquals("EEE, MMM d", TitleBarLayout.shortDate("EEEE, MMMM d", 2))
        assertEquals("MMM d", TitleBarLayout.shortDate("EEEE, MMMM d", 3))
        assertEquals("d MMM yyyy", TitleBarLayout.shortDate("EEE d MMM yyyy", 3))
        // No separator after it: the one before goes.
        assertEquals("d MMM", TitleBarLayout.shortDate("d MMM, EEE", 3))
        // Narrow forms and short letters stay; quoted text is literal.
        assertEquals("EEEEE MMMMM d", TitleBarLayout.shortDate("EEEEE MMMMM d", 2))
        assertEquals("'EEEE' d", TitleBarLayout.shortDate("'EEEE' d", 3))
        assertEquals("d 'de' MMM", TitleBarLayout.shortDate("d 'de' MMMM", 2))
        assertEquals(listOf(1), TitleBarLayout.dateLevels(listOf(null)))
        assertEquals(emptyList<Int>(), TitleBarLayout.dateLevels(listOf("d MMM")))
        assertEquals(listOf(3), TitleBarLayout.dateLevels(listOf("EEE d MMM yyyy")))
        // Every allowed letter still formats after shortening.
        for (p in listOf("EEEE, MMMM d", "EEE d MMM yyyy", "d MMM, EEE", "EEEE d. MMMM y")) for (l in 0..3)
            DateTimeFormatter.ofPattern(TitleBarLayout.shortDate(p, l), Locale.US).format(day)
    }

    @Test fun modelKnowsNamedSizesAndSlotPaths() {
        val c = (ConfigCodec.decode("""{"schemaVersion":1,"pages":[{"id":"home","items":[],"overrides":{"t":{"content":{"slots":{"end":"{time}"}}}}},{"id":"two","items":[]}],
            "elements":[{"id":"t","type":"titleBar","content":{"slots":{"start":"{date}","center":"{page}"}},"style":{"size":"large"}}]}""") as ParseResult.Ok).config
        val bar = TitleBarModel.forPage(c, "home")!!
        assertEquals("large", bar.style.sizeName)
        assertEquals("elements[0].content.slots.start", bar.paths[Slot.START])
        assertEquals("pages[0].overrides.t.content.slots.end", bar.paths[Slot.END])
        assertNull(TitleBarModel.style(kotlinx.serialization.json.buildJsonObject { put("size", kotlinx.serialization.json.JsonPrimitive(20)) }).sizeName)
    }
}
