package dev.nasawa.configlauncher.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.layout.Layout
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
 * Label backdrop setting, which `backdrop: "auto"` follows.
 */
@Composable
fun TitleBarRow(bar: TitleBarModel.Bar, pages: List<ResolvedPage>, current: Int, enabled: Boolean, labelBackdrop: Boolean, onOpen: () -> Unit) {
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
    val shown = TitleBarModel.Values(name, i + 1, pages.size, { fmt(it, timeDefault) }, { fmt(it, dateDefault) })
    val spoken = shown.copy(date = { p -> if (p == null) fmt(null, dateSpoken) else fmt(p, dateDefault) })

    val st = bar.style
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val theme: (String) -> Color? = { TitleBarLayout.themeColor(scheme, it) }
    val textColor = TitleBarLayout.color(if (dark) st.color.dark else st.color.light, theme)
    val fontScale = LocalConfiguration.current.fontScale
    val size = TitleBarLayout.cappedSp(st.sizeSp, fontScale)
    val style = TextStyle(color = textColor, fontSize = size.sp, fontWeight = FontWeight(st.weight),
        shadow = if (TitleBarLayout.shadow(textColor)) Shadow(Color(0x80000000), Offset(0f, 1f), 1f) else null)
    val back = when (val b = st.backdrop) {
        Backdrop.Auto -> if (labelBackdrop) Modifier.background(LabelBackdrop.scrim, RoundedCornerShape(6.dp)) else Modifier
        Backdrop.None -> Modifier
        Backdrop.Scrim -> Modifier.background(LabelBackdrop.scrim, RoundedCornerShape(6.dp))
        is Backdrop.Custom -> Modifier.background(TitleBarLayout.color(b.color, theme).copy(alpha = b.opacity / 100f), RoundedCornerShape(b.radius.dp))
    }
    val tappable = PageList.tappable(pages)
    val hint = stringResource(R.string.pages_hint)
    val res = androidx.compose.ui.platform.LocalResources.current

    @Composable
    fun slot(s: Slot) {
        val parts = bar.slots[s]
        if (parts == null) { Box(Modifier); return }
        val text = TitleBarModel.text(parts, shown)
        // #124: a slot holding {page} is one tap target (≥ 2 pages) with one trailing ▾ after all its text.
        val button = TitleBarModel.hasPage(parts) && tappable
        val said = TitleBarModel.spoken(parts, spoken, button) { t -> res.getString(R.string.titlebar_page_a11y, t) }
        val align = st.align[s] ?: Align.CENTER
        Box(
            Modifier.heightIn(min = if (button) 48.dp else 0.dp)
                .then(if (button) Modifier.clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = said },
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) {
            if (!button) Text(text, style = style, textAlign = TitleBarLayout.textAlignOf(align), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
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
        Layout(
            content = { slot(Slot.START); slot(Slot.CENTER); slot(Slot.END) },
            modifier = Modifier.fillMaxWidth().then(back).padding(horizontal = st.paddingH.dp, vertical = st.paddingV.dp),
        ) { ms, cs ->
            val w = cs.maxWidth
            val want = ms.map { it.maxIntrinsicWidth(cs.maxHeight) }
            val fixed = Slot.entries.filter { bar.slots[it]?.let(TitleBarModel::clockOnly) == true }.map { it.ordinal }.toSet()
            // #149: the {page} button keeps room for its ▾ and an ellipsis; Compose widens its touch area to 48 dp.
            val page = if (tappable) Slot.entries.firstOrNull { bar.slots[it]?.let(TitleBarModel::hasPage) == true }?.ordinal else null
            val cWant = if (bar.slots[Slot.CENTER] == null) 0 else want[1]
            val (g0, g1) = TitleBarLayout.gaps(want[0], cWant, want[2], TitleBarLayout.GAP_DP.dp.roundToPx())
            val (sw, cw, ew) = TitleBarLayout.widths(w, want[0], cWant, want[2], fixed,
                page, TitleBarLayout.PAGE_MIN_DP.dp.roundToPx(), TitleBarLayout.GAP_DP.dp.roundToPx())
            val ps = listOf(sw, cw, ew).mapIndexed { k, width -> ms[k].measure(Constraints.fixedWidth(width.coerceAtLeast(0)).copy(minHeight = 0, maxHeight = cs.maxHeight)) }
            val h = ps.maxOf { it.height }
            layout(w, h) {
                var x = 0
                val after = intArrayOf(g0, g1, 0)
                ps.forEachIndexed { k, p -> p.placeRelative(x, (h - p.height) / 2); x += p.width + after[k] }
            }
        }
    }
}

