package dev.nasawa.configlauncher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.Template
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.model.TitleBarModel
import dev.nasawa.configlauncher.model.TitleBarModel.Align
import dev.nasawa.configlauncher.model.TitleBarModel.Backdrop
import dev.nasawa.configlauncher.model.TitleBarModel.Slot
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Pure layout and colour rules for the bar (ux.md §15), unit-tested. */
object TitleBarLayout {
    /**
     * Widths for start, center, end in a bar [w] wide (ux.md §15). `center` keeps at least half (or what it needs, if
     * less), and the side slots share what's left equally around it, so the centre stays centred. Side slots ellipsize
     * first, then `center`. A slot in [fixed] (clock and date only, #123) never truncates: it gets the width it needs,
     * taken from the other slots, up to the whole bar.
     *
     * #149: when the fixed sides can't both fit symmetrically, centring is given up instead of clipping them: each fixed
     * side gets its own width and the centre takes what's between. The slot holding the `{page}` button ([page], its
     * index) never gets less than [pageMin] (or its own width, if less), so its tap target can't disappear. Only on a bar
     * too narrow for all of that do the fixed slots and the page button shrink, in proportion to what they need.
     */
    fun widths(w: Int, start: Int, center: Int, end: Int, fixed: Set<Int> = emptySet(), page: Int? = null, pageMin: Int = 0, gap: Int = 0): Triple<Int, Int, Int> {
        // ux.md §15 (#154): the [gap] between adjacent non-empty slots is reserved first, like the ▾; see [gaps].
        if (gap > 0) gaps(start, center, end, gap).let { (a, b) -> return widths((w - a - b).coerceAtLeast(0), start, center, end, fixed, page, pageMin) }
        if (w <= 0) return Triple(0, 0, 0)
        val want = intArrayOf(start, center.coerceAtLeast(0), end)
        // What each slot can't go below: a fixed slot its whole width, the page button pageMin.
        val min = IntArray(3) { k -> minOf(w, when { k in fixed -> want[k]; k == page -> minOf(want[k], pageMin); else -> 0 }) }
        val total = min.sum()
        if (total > w) {
            // Too narrow for everything that mustn't shrink: share the bar by need, so nothing collapses to zero.
            val a = (w.toLong() * min[0] / total).toInt()
            val b = (w.toLong() * min[1] / total).toInt()
            return Triple(a, b, w - a - b)
        }
        var c = when {
            want[1] <= 0 -> 0
            1 in fixed -> minOf(want[1], w)
            else -> minOf(want[1], w, maxOf(w / 2, w - 2 * maxOf(start, end)))
        }
        // Centred if the fixed/page sides allow it at the same width on both sides; otherwise each side keeps its minimum.
        val symmetric = w - 2 * maxOf(min[0], min[2])
        c = if (symmetric >= min[1]) minOf(c, symmetric) else minOf(c, w - min[0] - min[2])
        c = c.coerceAtLeast(min[1])
        val rest = w - c
        val s = (rest / 2).coerceIn(min[0], rest - min[2])
        return Triple(s, c, rest - s)
    }

    /** ux.md §15 (#154, Refs #149): the minimum space between adjacent non-empty slots, reserved before widths are shared. */
    const val GAP_DP = 8

    /**
     * Gap after start and after center, in px: [gap] between adjacent non-empty slots only (ux.md §15, #154). An
     * empty slot takes no gap; with no centre, start and end are adjacent.
     */
    fun gaps(start: Int, center: Int, end: Int, gap: Int): Pair<Int, Int> =
        (if (start > 0 && (center > 0 || end > 0)) gap else 0) to (if (center > 0 && end > 0) gap else 0)

    /** #149: the smallest a `{page}` button slot gets: the ▾, its 4 dp gap and an ellipsis at the default size. */
    const val PAGE_MIN_DP = 32

    /** ux.md §15 step 1: the `{page}` slot keeps a 48 dp touch area, which may overlap the gaps next to it. */
    const val TOUCH_DP = 48
    /** ux.md §15 step 1 (Pragma): the ▾-only `{page}` button may be this narrow when its touch area can use both sides. */
    const val ARROW_MIN_DP = 24

    /**
     * One rung of the ux.md §15 overflow ladder (#149). [step] is 0 (as written) to 3; step 4 is [Fit.cut] on the last
     * rung. Rungs are cumulative: [arrowOnly] (step 1) hides the `{page}` slot's text, [dateLevel] (step 2) is how far
     * the date is shortened ([shortDate]), and [smaller] (step 3) is one named size down ([smallerSize]).
     */
    data class Rung(val step: Int, val arrowOnly: Boolean = false, val dateLevel: Int = 0, val smaller: Boolean = false)

    /**
     * The rungs to try, in order (ux.md §15): as written; `{page}` as the ▾ alone (only with a slot holding `{page}`);
     * each date level in [dateLevels]; one size down (only for a named `style.size` above `small`, [smaller]).
     */
    fun rungs(pageSlot: Boolean, dateLevels: List<Int>, smaller: Boolean): List<Rung> = buildList {
        add(Rung(0))
        if (pageSlot) add(Rung(1, arrowOnly = true))
        dateLevels.forEach { add(Rung(2, pageSlot, it)) }
        if (smaller) add(Rung(3, pageSlot, dateLevels.lastOrNull() ?: 0, smaller = true))
    }

    /** The rung that fits, the [widths] to lay out with, and the slot widths they were shared from. */
    data class Fit(
        val rung: Rung, val cut: Boolean, val widths: Triple<Int, Int, Int>, val want: List<Int>,
        /** Step 1: how far the ▾-only button's touch area reaches past its slot, before and after it (px). */
        val touch: Pair<Int, Int> = 0 to 0,
    ) {
        /** The last ladder step used: 0 when the bar fits as written, 4 when the date is cut. */
        val step get() = if (cut) 4 else rung.step
    }

    /** What each slot can't go below, as in [widths]: a [fixed] slot all of it, the [page] slot [pageMin] or less. */
    private fun mins(want: List<Int>, fixed: Set<Int>, page: Int?, pageMin: Int) =
        List(3) { k -> when { k in fixed -> want[k]; k == page -> minOf(want[k], pageMin); else -> 0 } }

    /** True when the clock, the date, the `{page}` minimum and the gaps fit in [w] (ux.md §15), so nothing fixed is cut. */
    fun fits(w: Int, want: List<Int>, fixed: Set<Int>, page: Int?, pageMin: Int, gap: Int): Boolean {
        val (a, b) = gaps(want[0], want[1], want[2], gap)
        return a + b + mins(want, fixed, page, pageMin).sum() <= w
    }

    /**
     * ux.md §15 overflow ladder (#149): tries [rungs] in order and stops at the first that [fits]. [measure] gives the
     * start, center and end widths a rung's text needs, measured with the style it's drawn with (0 for an empty slot).
     * [fixed] slots hold only `{time}` / `{date}`; [dates] are the ones holding a date. At step 1 the [page] button is
     * the ▾ alone ([arrowOnlyMin]); its [touch] (48 dp) area reaches past the slot ([Fit.touch], [touchReach]).
     * If no rung fits, step 4 cuts the date (the widest date slot first) by what's missing; the clock is never cut.
     */
    fun ladder(
        w: Int, gap: Int, fixed: Set<Int>, dates: Set<Int>, page: Int?, pageMin: Int, touch: Int,
        rungs: List<Rung>, arrowMin: Int = pageMin * ARROW_MIN_DP / PAGE_MIN_DP, tappable: Set<Int> = emptySet(),
        measure: (Rung) -> List<Int>,
    ): Fit {
        var last: Pair<Rung, List<Int>>? = null
        fun done(r: Rung, cut: Boolean, m: List<Int>): Fit {
            val widths = widths(w, m[0], m[1], m[2], fixed, page, pageMin, gap)
            val reach = if (r.arrowOnly && page != null && m[page] > 0) touchReach(page, m, widths, gap, touch, tappable) else 0 to 0
            return Fit(r, cut, widths, m, reach)
        }
        for (r in rungs) {
            val m = measure(r).toMutableList()
            if (r.arrowOnly && page != null && m[page] > 0) m[page] = arrowOnlyMin(page, m, m[page], pageMin, arrowMin, tappable)
            if (fits(w, m, fixed, page, pageMin, gap)) return done(r, false, m)
            last = r to m
        }
        val (r, m) = last ?: return Fit(Rung(0), false, Triple(0, 0, 0), listOf(0, 0, 0))
        val cut = m.toMutableList()
        val (a, b) = gaps(m[0], m[1], m[2], gap)
        var over = a + b + mins(m, fixed, page, pageMin).sum() - w
        for (k in dates.filter { it in fixed }.sortedByDescending { m[it] }) {
            // Keep 1 px so the slot (and the gap beside it) stays: the date shows as "…" at worst.
            val take = minOf(over, cut[k] - 1).coerceAtLeast(0)
            cut[k] -= take; over -= take
        }
        return done(r, true, cut)
    }

    /** The nearest non-empty slot before (-1) or after (+1) slot [k], or null at the bar's edge. */
    private fun neighbour(k: Int, dir: Int, m: List<Int>): Int? {
        var i = k + dir
        while (i in 0..2) { if (m[i] > 0) return i; i += dir }
        return null
    }

    /**
     * ux.md §15 step 1 (Pragma): the ▾-only button's visible width. Each side needs half of [arrowMin] (24 dp) when
     * the touch area can reach over that side (a neighbour that isn't tappable), else half of [pageMin] (32 dp): next
     * to a tappable neighbour (it never takes their touch area) or at the bar's edge. Never below the ▾ itself.
     */
    fun arrowOnlyMin(page: Int, m: List<Int>, arrow: Int, pageMin: Int, arrowMin: Int, tappable: Set<Int>): Int {
        fun half(dir: Int) = neighbour(page, dir, m).let { n -> if (n != null && n !in tappable) arrowMin / 2 else pageMin - pageMin / 2 }
        return minOf(pageMin, maxOf(arrow, half(-1) + half(1)))
    }

    /**
     * How far the step-1 button's [touch] area reaches past its slot, before and after it. Over a neighbour that isn't
     * tappable it may reach across the gap and onto that neighbour; next to a tappable one it stops at the gap's
     * midpoint; at the bar's edge it doesn't reach out. What one side can't take, the other side takes if it can.
     */
    fun touchReach(page: Int, m: List<Int>, widths: Triple<Int, Int, Int>, gap: Int, touch: Int, tappable: Set<Int>): Pair<Int, Int> {
        val ws = listOf(widths.first, widths.second, widths.third)
        val need = (touch - ws[page]).coerceAtLeast(0)
        fun cap(dir: Int) = neighbour(page, dir, m).let { n -> when { n == null -> 0; n in tappable -> gap / 2; else -> gap + ws[n] } }
        val (cb, ca) = cap(-1) to cap(1)
        var before = minOf(cb, need - need / 2); var after = minOf(ca, need - before)
        before = minOf(cb, need - after)
        return before to after
    }

    /** ux.md §15 step 3: one named size down (`large` → `medium` → `small`); null for `small`, a numeric size or none. */
    fun smallerSize(name: String?): String? = when (name) { "large" -> "medium"; "medium" -> "small"; else -> null }

    /**
     * ux.md §15 step 2: [pattern] shortened to [level] (cumulative). 1: `EEEE` → `EEE`; 2: `MMMM` → `MMM`; 3: the weekday
     * run is dropped with the literal separator next to it (after it, or else before it). Short and narrow runs, quoted
     * text and other letters are left as written. Plain `{date}` (no pattern) isn't handled here: it goes to the short
     * locale format at level 1.
     */
    fun shortDate(pattern: String, level: Int): String {
        val runs = patternRuns(pattern).toMutableList()
        for (i in runs.indices) {
            val r = runs[i]
            if (level >= 1 && r == "EEEE") runs[i] = "EEE"
            if (level >= 2 && r == "MMMM") runs[i] = "MMM"
        }
        if (level >= 3) {
            var i = runs.indexOfFirst { it.startsWith("E") }
            while (i >= 0) {
                runs.removeAt(i)
                when {
                    i < runs.size && isSeparator(runs[i]) -> runs.removeAt(i)
                    i > 0 && isSeparator(runs[i - 1]) -> runs.removeAt(i - 1)
                }
                i = runs.indexOfFirst { it.startsWith("E") }
            }
        }
        return runs.joinToString("")
    }

    /** The date levels that change some date in the bar: [patterns] are its `{date}` patterns, null for plain `{date}`. */
    fun dateLevels(patterns: List<String?>): List<Int> = (1..3).filter { l ->
        patterns.any { p -> if (p == null) l == 1 else shortDate(p, l) != shortDate(p, l - 1) }
    }

    /** A pattern as runs: one letter repeated, a quoted literal (with its quotes), or other literal characters. */
    private fun patternRuns(p: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < p.length) {
            val c = p[i]
            var j = i + 1
            when {
                c == '\'' -> { while (j < p.length && !(p[j] == '\'' && (j + 1 >= p.length || p[j + 1] != '\''))) j += if (p[j] == '\'') 2 else 1; j = minOf(j + 1, p.length) }
                c.isLetter() -> while (j < p.length && p[j] == c) j++
                else -> while (j < p.length && !p[j].isLetter() && p[j] != '\'') j++
            }
            out += p.substring(i, j); i = j
        }
        return out
    }

    private fun isSeparator(run: String) = run.isNotEmpty() && !run[0].isLetter() && run[0] != '\''

    /**
     * ux.md §15.1 `warn_titlebar_fit` (status.json, so code, not strings.xml), only for step 2 and later. [path] is the
     * slot holding the date, [widthDp] the width the slots share, [size] the named size step 3 went down to.
     */
    fun fitWarning(path: String, widthDp: Int, step: Int, size: String?, hasDate: Boolean = true): String? {
        if (step < 2) return null
        // warn_titlebar_fit_size (no date in the bar: step 2 is skipped, nothing is said about a date).
        if (!hasDate) {
            // Step 4 with no date to cut has no copy yet (open question in #167): only the size step is reported.
            if (size == null) return null
            return "$path: the title bar doesn't fit in its $widthDp dp on this phone, so fit step $step of 4 was used: the text size went down to $size. Checked on this phone. Use fewer or shorter slots."
        }
        val what = when {
            step == 2 -> "the date was shortened"
            step == 3 -> "the date was shortened and the text size went down to $size"
            else -> "the date was shortened and cut off with …"
        }
        return "$path: the title bar doesn't fit in its $widthDp dp on this phone, so fit step $step of 4 was used: $what. Checked on this phone. Use a shorter date, like {date:d MMM}."
    }

    /** The warn_titlebar_fit_size path: the bar's `content.slots` (the slot key dropped from any slot's path). */
    fun slotsPath(anySlotPath: String?): String = anySlotPath?.substringBeforeLast('.') ?: "elements"

    fun textAlignOf(a: Align): TextAlign = when (a) { Align.START -> TextAlign.Start; Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End }

    /** Hex or static token → ARGB; theme tokens resolve through [theme]. */
    fun color(token: String, theme: (String) -> Color?): Color = when {
        token.startsWith("#") -> Color(0xFF000000 or token.substring(1).toLong(16))
        token == "dark" -> Color(0xFF1B1B1F)
        token == "light" || token == "onWallpaper" -> Color.White
        else -> theme(token) ?: Color.White
    }

    /** Theme tokens, resolved through [scheme]: one rule for drawing the bar and for measuring it (#150). */
    val THEME_TOKENS = listOf("primary", "secondary", "onSurface", "surface")

    fun themeColor(scheme: androidx.compose.material3.ColorScheme, token: String): Color? = when (token) {
        "primary" -> scheme.primary; "secondary" -> scheme.secondary; "onSurface" -> scheme.onSurface; "surface" -> scheme.surface; else -> null
    }

    /** #125: the theme tokens as `#RRGGBB` (opaque), for the phone-only contrast check. Pass the scheme the bar draws with. */
    fun themeHex(scheme: androidx.compose.material3.ColorScheme): Map<String, String> =
        THEME_TOKENS.associateWith { String.format(Locale.ROOT, "#%06X", themeColor(scheme, it)!!.toArgb() and 0xFFFFFF) }

    /** ux.md §15: light text always gets the label shadow; dark text never does. */
    fun shadow(c: Color) = c.luminance() > 0.5f

    /**
     * The style the bar's text is drawn and measured with (one `Text` node does both). It's passed as `style`, which
     * Material 3's `Text` uses instead of `LocalTextStyle`, so no letterSpacing is inherited from the theme (#149).
     */
    fun textStyle(color: Color, sizeSp: Float, weight: Int): TextStyle =
        TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight(weight),
            shadow = if (shadow(color)) Shadow(Color(0x80000000), Offset(0f, 1f), 1f) else null)

    /** §1.3 cap: the bar stays one line, so the rendered size is at most 40 sp × 1.3 whatever the font scale. */
    fun cappedSp(sizeSp: Float, fontScale: Float) = minOf(sizeSp, 52f / fontScale.coerceAtLeast(0.1f))
}

/** Once-a-minute clock that runs only while home is started (title-bar.md §9). */
@Composable
private fun minuteClock(): ZonedDateTime {
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            now = ZonedDateTime.now()
            delay((60 - now.second) * 1000L - now.nano / 1_000_000 + 50)
        }
    }
    return now
}

/**
 * #79 title bar (ux.md §15): one line in place of the §1.3 page label. One focusable node per non-empty slot; the slot
 * holding `{page}` opens the page list (≥ 2 pages, 48 dp target) like the page label. [labelBackdrop] is the device's
 * Label backdrop setting, which `backdrop: "auto"` follows. [onFit] gets the §15.1 `warn_titlebar_fit` line (null once
 * the bar fits at step 0 or 1) whenever the overflow ladder's result changes.
 */
@Composable
fun TitleBarRow(bar: TitleBarModel.Bar, pages: List<ResolvedPage>, current: Int, enabled: Boolean, labelBackdrop: Boolean, onFit: (String?) -> Unit = {}, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val now = minuteClock()
    val i = current.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val name = pages.getOrNull(i)?.let(PageList::title) ?: stringResource(R.string.page_untitled, i + 1)
    val is24 = DateFormat.is24HourFormat(ctx)
    val timeDefault = remember(locale, is24) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24) "Hm" else "hma"), locale) }
    val dateDefault = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val dateSpoken = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale) }
    fun fmt(p: String?, default: DateTimeFormatter) =
        (p?.let { runCatching { DateTimeFormatter.ofPattern(it, locale) }.getOrNull() } ?: default).let { f -> runCatching { f.format(now) }.getOrDefault("") }
    val dateShort = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale) }
    // ux.md §15 step 2: the date as shortened to [level]; level 0 is as written.
    fun values(level: Int) = TitleBarModel.Values(name, i + 1, pages.size, { fmt(it, timeDefault) },
        { p -> if (p == null) fmt(null, if (level > 0) dateShort else dateDefault) else fmt(TitleBarLayout.shortDate(p, level), dateDefault) })
    val shown = values(0)
    // TalkBack always reads the full date and time, whatever ladder step is showing.
    val spoken = shown.copy(date = { p -> if (p == null) fmt(null, dateSpoken) else fmt(p, dateDefault) })

    val st = bar.style
    // #180: the light/dark variant follows the home theme (the system setting today, #156's `theme` key later).
    val dark = currentHomeDark()
    val scheme = MaterialTheme.colorScheme
    val theme: (String) -> Color? = { TitleBarLayout.themeColor(scheme, it) }
    val textColor = TitleBarLayout.color(if (dark) st.color.dark else st.color.light, theme)
    val fontScale = LocalConfiguration.current.fontScale
    // Step 3 changes only the bar's own size; the system font scale still multiplies it (sp).
    val smaller = TitleBarLayout.smallerSize(st.sizeName)
    fun style(r: TitleBarLayout.Rung) = TitleBarLayout.textStyle(textColor,
        TitleBarLayout.cappedSp(if (r.smaller && smaller != null) dev.nasawa.configlauncher.config.TitleBar.SIZES.getValue(smaller).toFloat() else st.sizeSp, fontScale), st.weight)
    val back = when (val b = st.backdrop) {
        Backdrop.Auto -> if (labelBackdrop) Modifier.background(LabelBackdrop.scrim, RoundedCornerShape(6.dp)) else Modifier
        Backdrop.None -> Modifier
        Backdrop.Scrim -> Modifier.background(LabelBackdrop.scrim, RoundedCornerShape(6.dp))
        is Backdrop.Custom -> Modifier.background(TitleBarLayout.color(b.color, theme).copy(alpha = b.opacity / 100f), RoundedCornerShape(b.radius.dp))
    }
    val tappable = PageList.tappable(pages)
    val hint = stringResource(R.string.pages_hint)
    val res = androidx.compose.ui.platform.LocalResources.current

    val pageSlot = Slot.entries.firstOrNull { bar.slots[it]?.let(TitleBarModel::hasPage) == true }
    val fixed = Slot.entries.filter { bar.slots[it]?.let(TitleBarModel::clockOnly) == true }.map { it.ordinal }.toSet()
    val dates = Slot.entries.filter { s -> s.ordinal in fixed && bar.slots[s].orEmpty().any { it is Template.Tok && it.name == "date" } }.map { it.ordinal }.toSet()
    val datePatterns = bar.slots.values.flatten().filterIsInstance<Template.Tok>().filter { it.name == "date" }.map { it.pattern }
    val rungs = remember(bar, tappable) { TitleBarLayout.rungs(pageSlot != null, TitleBarLayout.dateLevels(datePatterns), smaller != null) }
    val reported = remember { arrayOfNulls<String>(1).also { it[0] = "\u0000" } }
    // Step 1: the touch area's reach before/after the slot (set by the measure pass), so the ▾ stays centred on its slot.
    val reach = remember { IntArray(2) }

    @Composable
    fun slot(s: Slot, r: TitleBarLayout.Rung) {
        val parts = bar.slots[s]
        if (parts == null) { Box(Modifier); return }
        val style = style(r)
        val text = TitleBarModel.text(parts, values(r.dateLevel))
        // #124: a slot holding {page} is one tap target (≥ 2 pages) with one trailing ▾ after all its text.
        val button = TitleBarModel.hasPage(parts) && tappable
        val said = TitleBarModel.spoken(parts, spoken, button) { t -> res.getString(R.string.titlebar_page_a11y, t) }
        // ux.md §15 step 1: the {page} slot's text is hidden (the ▾ stays on a button); TalkBack still reads it.
        val hidden = r.arrowOnly && TitleBarModel.hasPage(parts)
        if (hidden && !button) { Box(Modifier.semantics { contentDescription = said }); return }
        val align = st.align[s] ?: Align.CENTER
        Box(
            Modifier.heightIn(min = if (button) 48.dp else 0.dp)
                .then(if (button) Modifier.clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = said },
            contentAlignment = if (hidden) androidx.compose.ui.Alignment.Center else androidx.compose.ui.Alignment.CenterStart,
        ) {
            if (!button) Text(text, style = style, textAlign = TitleBarLayout.textAlignOf(align), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            else if (hidden) Row(Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0))
                // Grace's crash at da3849b: the ladder measures intrinsics with an unbounded width, and a layout can't
                // be infinitely wide. Unbounded, the slot is as wide as its ▾ (that's its intrinsic width), not re-centred.
                if (!c.hasBoundedWidth) return@layout layout(p.width, p.height) { p.placeRelative(0, 0) }
                val full = c.maxWidth
                layout(full, p.height) { p.placeRelative(reach[0] + (full - reach[0] - reach[1] - p.width) / 2, 0) }
            }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp))
                Text("▾", style = style.copy(fontSize = 12.sp), maxLines = 1, softWrap = false)
            }
            else Row(
                Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = when (align) { Align.START -> Arrangement.Start; Align.CENTER -> Arrangement.Center; Align.END -> Arrangement.End },
            ) {
                // The text takes what's left after the 4 dp gap and the ▾ (ux.md §15), so truncation ellipsizes the text and never clips the ▾.
                Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Text("▾", style = style.copy(fontSize = 12.sp), maxLines = 1, softWrap = false)
            }
        }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        SubcomposeLayout(
            Modifier.fillMaxWidth().then(back).padding(horizontal = st.paddingH.dp, vertical = st.paddingV.dp),
        ) { cs ->
            val w = cs.maxWidth
            val gap = TitleBarLayout.GAP_DP.dp.roundToPx()
            // #149: the {page} button keeps room for its ▾ and an ellipsis; at step 1 its 48 dp touch area may use the gaps.
            val page = if (tappable) pageSlot?.ordinal else null
            val slots = HashMap<TitleBarLayout.Rung, List<Measurable>>()
            fun content(r: TitleBarLayout.Rung) = slots.getOrPut(r) { subcompose(r) { slot(Slot.START, r); slot(Slot.CENTER, r); slot(Slot.END, r) } }
            // ux.md §15 overflow ladder, rerun on every layout: each rung is measured with the Text and style it's drawn with.
            // Pragma: the ▾ may drop to 24 dp; its touch area never takes another tappable target's (none in the bar today).
            val fit = TitleBarLayout.ladder(w, gap, fixed, dates, page, TitleBarLayout.PAGE_MIN_DP.dp.roundToPx(), TitleBarLayout.TOUCH_DP.dp.roundToPx(), rungs,
                arrowMin = TitleBarLayout.ARROW_MIN_DP.dp.roundToPx()) { r ->
                content(r).mapIndexed { k, m -> if (bar.slots[Slot.entries[k]] == null) 0 else m.maxIntrinsicWidth(cs.maxHeight) }
            }
            val hasDate = datePatterns.isNotEmpty()
            val warn = TitleBarLayout.fitWarning(
                if (hasDate) (dates.firstOrNull() ?: fixed.firstOrNull())?.let { bar.paths[Slot.entries[it]] } ?: "elements"
                else TitleBarLayout.slotsPath(bar.paths.values.firstOrNull()),
                w.toDp().value.toInt(), fit.step, smaller, hasDate)
            if (reported[0] != warn) { reported[0] = warn; onFit(warn) }
            val ms = content(fit.rung)
            val (g0, g1) = TitleBarLayout.gaps(fit.want[0], fit.want[1], fit.want[2], gap)
            val after = intArrayOf(g0, g1, 0)
            val (sw, cw, ew) = fit.widths
            // Step 1: the ▾-only button's touch area reaches past its slot ([TitleBarLayout.touchReach]), the ▾ centred in it.
            reach[0] = fit.touch.first; reach[1] = fit.touch.second
            val grow = { k: Int -> if (k == page) fit.touch else 0 to 0 }
            val ps = listOf(sw, cw, ew).mapIndexed { k, width ->
                val (l, rr) = grow(k)
                ms[k].measure(Constraints.fixedWidth((width + l + rr).coerceAtLeast(0)).copy(minHeight = 0, maxHeight = cs.maxHeight))
            }
            val h = ps.maxOf { it.height }
            layout(w, h) {
                var x = 0
                ps.forEachIndexed { k, p ->
                    val (l, rr) = grow(k)
                    p.placeRelative(x - l, (h - p.height) / 2); x += p.width - l - rr + after[k]
                }
            }
        }
    }
}
