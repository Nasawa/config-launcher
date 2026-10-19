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
     */
    fun widths(w: Int, start: Int, center: Int, end: Int, fixed: Set<Int> = emptySet()): Triple<Int, Int, Int> {
        val sNeed = maxOf(if (0 in fixed) start else 0, if (2 in fixed) end else 0)
        if (center <= 0) {
            // No centre: the sides split the bar, unless a fixed side needs more than half.
            val half = w / 2
            return when {
                0 in fixed && start > half -> minOf(start, w).let { Triple(it, 0, w - it) }
                2 in fixed && end > w - half -> minOf(end, w).let { Triple(w - it, 0, it) }
                else -> Triple(half, 0, w - half)
            }
        }
        val side = maxOf(start, end)
        var c = if (1 in fixed) minOf(center, w) else minOf(center, w, maxOf(w / 2, w - 2 * side))
        // A fixed side slot takes its width from the centre (unless the centre is fixed too).
        if (1 !in fixed && 2 * sNeed > w - c) c = maxOf(0, minOf(c, w - 2 * sNeed))
        val s = (w - c) / 2
        return Triple(s, c, w - c - s)
    }

    /** Hex or static token → ARGB; theme tokens resolve through [theme]. */
    fun color(token: String, theme: (String) -> Color?): Color = when {
        token.startsWith("#") -> Color(0xFF000000 or token.substring(1).toLong(16))
        token == "dark" -> Color(0xFF1B1B1F)
        token == "light" || token == "onWallpaper" -> Color.White
        else -> theme(token) ?: Color.White
    }

    /** #125: the theme tokens as `#RRGGBB` (opaque), for the phone-only contrast check. */
    fun themeHex(scheme: androidx.compose.material3.ColorScheme): Map<String, String> =
        mapOf("primary" to scheme.primary, "secondary" to scheme.secondary, "onSurface" to scheme.onSurface, "surface" to scheme.surface)
            .mapValues { (_, c) -> String.format(Locale.ROOT, "#%06X", c.toArgb() and 0xFFFFFF) }

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
    val theme: (String) -> Color? = { when (it) { "primary" -> scheme.primary; "secondary" -> scheme.secondary; "onSurface" -> scheme.onSurface; "surface" -> scheme.surface; else -> null } }
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
    val pageSpoken = stringResource(R.string.page_a11y, name, i + 1, pages.size)

    @Composable
    fun slot(s: Slot) {
        val parts = bar.slots[s]
        if (parts == null) { Box(Modifier); return }
        val text = TitleBarModel.text(parts, shown)
        val hasPage = TitleBarModel.hasPage(parts)
        val said = TitleBarModel.text(parts, spoken).let { if (hasPage && tappable) it.replace(name, pageSpoken) else it }
        val align = when (st.align[s] ?: Align.CENTER) { Align.START -> TextAlign.Start; Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End }
        Box(
            Modifier.heightIn(min = if (hasPage && tappable) 48.dp else 0.dp)
                .then(if (hasPage && tappable) Modifier.clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = said },
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) { Text(text, style = style, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()) }
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Layout(
            content = { slot(Slot.START); slot(Slot.CENTER); slot(Slot.END) },
            modifier = Modifier.fillMaxWidth().then(back).padding(horizontal = st.paddingH.dp, vertical = st.paddingV.dp),
        ) { ms, cs ->
            val w = cs.maxWidth
            val want = ms.map { it.maxIntrinsicWidth(cs.maxHeight) }
            val fixed = Slot.entries.filter { bar.slots[it]?.let(TitleBarModel::clockOnly) == true }.map { it.ordinal }.toSet()
            val (sw, cw, ew) = TitleBarLayout.widths(w, want[0], if (bar.slots[Slot.CENTER] == null) 0 else want[1], want[2], fixed)
            val ps = listOf(sw, cw, ew).mapIndexed { k, width -> ms[k].measure(Constraints.fixedWidth(width.coerceAtLeast(0)).copy(minHeight = 0, maxHeight = cs.maxHeight)) }
            val h = ps.maxOf { it.height }
            layout(w, h) {
                var x = 0
                ps.forEach { p -> p.placeRelative(x, (h - p.height) / 2); x += p.width }
            }
        }
    }
}

