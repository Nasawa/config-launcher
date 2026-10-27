package dev.nasawa.configlauncher.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.model.TitleBarModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Grace's crash on #167 at da3849b (A51 config at 320 dp): rendering the real [TitleBarRow], not just the ladder
 * function. Step 1's ▾-only slot laid itself out at the incoming max width, which is infinite while the ladder
 * measures intrinsics, and threw `Size(2147483647 x …) is out of range`.
 *
 * The bar reads the clock through [LocalTitleBarNow] (device clock when unset). These tests pin a measured
 * worst-case date so weekday/month glyph width can't flake CI the way `Mon 26 Oct 2026` vs `Tue 27 Oct 2026` did.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class TitleBarRenderTest {
    @get:Rule val rule = createComposeRule()

    /** The title bar and pages of Grace's A51 config (cl-qa dev6-recheck/a51-launcher.json). */
    private val a51 = """{"schemaVersion":1,"grid":{"columns":4,"rows":5},
        "pages":[{"id":"home","title":"Home","items":[]},{"id":"second","title":"Second","items":[]}],
        "elements":[{"id":"t-main","type":"titleBar","anchor":"top",
          "content":{"slots":{"start":"{date:EEE d MMM yyyy}","center":"{page}","end":"{time:HH:mm}"}},
          "style":{"size":"large","color":"light","padding":{"horizontal":16,"vertical":6},"backdrop":{"color":"#000000","opacity":60,"radius":12}}}]}"""
    private val bar = TitleBarModel.forPage((ConfigCodec.decode(a51) as ParseResult.Ok).config, "home")!!
    private val pages = listOf(ResolvedPage("home", "Home", emptyList()), ResolvedPage("second", "Second", emptyList()))
    private val pattern = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
    private val noon = { d: LocalDate -> d.atTime(12, 0).atZone(ZoneOffset.UTC) }

    /** Widest `EEE d MMM yyyy` date under Roboto at the bar's 24 sp, measured once per class. */
    private lateinit var widest: ZonedDateTime
    private lateinit var widestSpoken: String

    @Before fun pinWidestDate() {
        if (::widest.isInitialized) return
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, ctx.resources.displayMetrics)
        }
        // Measure each piece of `EEE d MMM yyyy` under Roboto 24 sp, then pick a real date that formats to the
        // widest weekday + two-digit day + month. Ties keep the first (stable across runs).
        fun w(s: String) = paint.measureText(s)
        val weekdays = (1..7).map { dow ->
            val d = LocalDate.of(2026, 1, 5).plusDays((dow - 1).toLong()) // Mon 5 Jan … Sun 11 Jan
            pattern.format(noon(d)).substringBefore(' ') to d.dayOfWeek
        }
        val months = (1..12).map { m ->
            val d = LocalDate.of(2026, m, 15)
            DateTimeFormatter.ofPattern("MMM", Locale.US).format(d) to m
        }
        val days = (10..31).map { it.toString() }
        val bestDow = weekdays.maxBy { w(it.first) }
        val bestDay = days.maxBy { w(it) }
        val bestMonth = months.maxBy { w(it.first) }
        // A 2026 date in [bestMonth] whose weekday is [bestDow] and day is two digits (prefer [bestDay]).
        val prefer = bestDay.toInt()
        var pick: LocalDate? = null
        for (day in listOf(prefer) + ((10..31).filter { it != prefer })) {
            if (day > LocalDate.of(2026, bestMonth.second, 1).lengthOfMonth()) continue
            val d = LocalDate.of(2026, bestMonth.second, day)
            if (d.dayOfWeek == bestDow.second) { pick = d; break }
        }
        // Fallback: any two-digit day in that month with that weekday.
        if (pick == null) {
            val first = LocalDate.of(2026, bestMonth.second, 10)
            pick = first.plusDays(((bestDow.second.value - first.dayOfWeek.value + 7) % 7).toLong())
        }
        widest = noon(pick!!)
        widestSpoken = pattern.format(widest)
        val short = w(pattern.format(noon(LocalDate.of(2026, 5, 4)))) // Mon 4 May 2026 (single-digit day)
        assertTrue(
            "widest ($widestSpoken = ${bestDow.first}+${bestDay}+${bestMonth.first} @ ${w(widestSpoken)}px) should beat short Mon 4 May (${short}px)",
            w(widestSpoken) >= short,
        )
    }

    @Composable private fun WithClock(now: ZonedDateTime = widest, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalTitleBarNow provides now) { MaterialTheme(content = content) }
    }

    @Test fun a51BarRendersAt320dp() {
        val fits = mutableListOf<String?>()
        rule.setContent { WithClock { TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = { fits += it }) {} } }
        rule.waitForIdle()
        // At 320 dp this bar needs step 2 (Grace's run): the ▾-only rung before it was measured without crashing.
        assertTrue("step 2 at 320 dp with $widestSpoken: $fits", fits.lastOrNull()?.contains("fit step 2 of 4") == true)
    }

    /** Every width from 411 dp down to 120 dp, so every rung of the ladder, step 1's ▾-only one included, is drawn. */
    @Test fun a51BarRendersAtEveryWidth() {
        var width by mutableIntStateOf(411)
        val steps = sortedSetOf<String>()
        rule.setContent {
            WithClock {
                Box(Modifier.requiredWidth(width.dp)) {
                    TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = { w -> steps += (w?.substringAfter("fit step ")?.take(1) ?: "0") }) {}
                }
            }
        }
        // Every narrower rung is tried only after step 1's ▾-only one, so reaching steps 2–4 means it was measured and drawn.
        for (w in 411 downTo 120 step 3) { width = w; rule.waitForIdle() }
        assertEquals(sortedSetOf("0", "2", "3", "4"), steps)
    }

    /**
     * Grace's A51 widths (dev6-a51/widths): at 320 dp the {page} 48 dp touch area overlaps the date and the clock.
     * Pragma: that's allowed only because neither is tappable (ux.md §15 #78: only the {page} slot is, in this release).
     * The touch area then reaches over the 8 dp gap and onto each neighbour, never past what makes it 48 dp.
     *
     * App vs test: the app is correct — date and clock aren't tappable, and [TitleBarLayout.touchReach] only reaches
     * over non-tappable neighbours. The flake was the test reading the live clock (and, before #194, asserting a
     * hardcoded `Mon 26 Oct 2026`). This pins the measured widest weekday/month so the step-2 overlap case stays stable.
     */
    @Test fun a51PageTouchAreaAt320dpOnlyOverlapsSlotsThatArentTappable() {
        rule.setContent { WithClock { TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = {}) {} } }
        rule.waitForIdle()
        assertTouchAreaAt320(widestSpoken, expectOverlap = true)
    }

    /**
     * Cheap 7 × 12 sweep at 320 dp on the A51 config: every US weekday × month. Asserts §15 step 1 invariants —
     * only {page} is tappable; its touch never lands on another tappable target (none exist beside it); when the
     * ladder is on the ▾-only rung the touch area is exactly 48 dp. Short dates may fit without step 1, in which
     * case the full "Home ▾" button can be wider than 48 dp (Material min, not max) and need not overlap neighbours.
     */
    @Test fun a51PageTouchAreaAcrossEveryWeekdayAndMonthAt320dp() {
        val samples = ArrayList<ZonedDateTime>(84)
        for (month in 1..12) {
            for (dow in 1..7) {
                val first = LocalDate.of(2026, month, 10)
                val delta = ((dow - first.dayOfWeek.value) + 7) % 7
                val d = first.plusDays(delta.toLong())
                if (d.monthValue != month) continue
                samples += noon(d)
            }
        }
        assertTrue("expected ~84 samples, got ${samples.size}", samples.size >= 70)
        var now by mutableStateOf(samples.first())
        rule.setContent { WithClock(now) { TitleBarRow(bar, pages, 0, enabled = true, labelBackdrop = false, onFit = {}) {} } }
        var arrowOnly = 0
        for (sample in samples) {
            now = sample
            rule.waitForIdle()
            if (assertTouchAreaAt320(pattern.format(sample), expectOverlap = false)) arrowOnly++
        }
        // With the A51 bar at 320 dp, most dates (including the widest) need the ▾-only rung.
        assertTrue("expected several ▾-only dates at 320 dp, got $arrowOnly of ${samples.size}", arrowOnly >= 20)
    }

    /**
     * @return true when the placed {page} touch area is the 48 dp ▾-only case (Grace's overflow).
     */
    private fun assertTouchAreaAt320(spokenDate: String, expectOverlap: Boolean): Boolean {
        // The rungs the ladder measured but didn't pick stay composed (unplaced); only the placed ones are on screen.
        val shown = rule.onAllNodes(isRoot().not()).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced && it.boundsInRoot.width > 0 }
        fun said(n: SemanticsNode) = n.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().joinToString()
        val taps = shown.filter { SemanticsActions.OnClick in it.config }
        assertEquals("only the {page} slot is tappable ($spokenDate)", listOf("Home, Pages"), taps.map(::said))
        // TalkBack always reads the full pattern date, whatever ladder step is showing.
        val date = shown.single { said(it) == spokenDate }.boundsInRoot
        val clock = shown.single { said(it).matches(Regex("\\d\\d:\\d\\d")) }.boundsInRoot
        val page = taps.single()
        val pageTouch = page.touchBoundsInRoot
        val pageBox = page.boundsInRoot
        val dp = rule.density.density
        // Overlap with a neighbour is only allowed when that neighbour isn't tappable (date + clock here).
        val over = maxOf(date.right - pageTouch.left, 0f) + maxOf(pageTouch.right - clock.left, 0f)
        // Never take touch area from a tappable neighbour: the only tappable node is {page} itself.
        assertTrue("page touch stays on-screen ($spokenDate)", pageTouch.width > 0)
        val arrowOnly = kotlin.math.abs(pageTouch.width - 48 * dp) <= 1f
        if (expectOverlap) {
            assertTrue("it overlaps the date or clock here: $date $pageTouch $clock ($spokenDate)", over > 0)
            assertEquals("and it is 48 dp, no wider ($spokenDate)", 48 * dp, pageTouch.width, 1f)
        } else if (arrowOnly) {
            // ▾-only: 48 dp exactly; may or may not overlap depending on neighbour widths, but must not hit a tappable.
            assertEquals("▾-only touch is 48 dp ($spokenDate)", 48 * dp, pageTouch.width, 1f)
        } else {
            // Full "Home ▾": Material min 48 dp, not a max — width may exceed 48.
            assertTrue("full page button ≥ 48 dp ($spokenDate): ${pageTouch.width / dp}", pageTouch.width + 1f >= 48 * dp)
        }
        // Visible slot never claims another slot's OnClick (already asserted via taps == one).
        assertTrue("page box is placed ($spokenDate)", pageBox.width > 0)
        return arrowOnly
    }
}

