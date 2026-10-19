package dev.nasawa.configlauncher.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.ResolvedPage

/** ux.md §1.3 (#78): one row per page, in file order. [title] null = untitled ("Page N"). */
data class PageRow(val number: Int, val title: String?, val current: Boolean)

object PageList {
    fun rows(pages: List<ResolvedPage>, current: Int): List<PageRow> =
        pages.mapIndexed { i, p -> PageRow(i + 1, title(p), i == current) }

    fun title(p: ResolvedPage): String? = p.title?.takeIf { it.isNotBlank() }?.let(Labels::clean)

    /** Shown with ≥ 2 pages, or when the only page has a title. */
    fun showLabel(pages: List<ResolvedPage>): Boolean = pages.size >= 2 || pages.size == 1 && title(pages[0]) != null

    /** A tap target (with ▾) only with ≥ 2 pages. */
    fun tappable(pages: List<ResolvedPage>): Boolean = pages.size >= 2
}

/** ux.md §12 reduced motion: animator duration scale 0 ("Remove animations"). */
object ReducedMotion {
    fun on(ctx: Context): Boolean = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/**
 * §1.3 page label: one line under the banner slot, centred, 14 sp, white with the label shadow (+ scrim with
 * Label backdrop). Tappable with ≥ 2 pages (≥ 48 dp tall, ≥ 96 dp wide, trailing ▾); inert while dragging ([enabled]).
 */
@Composable
fun PageLabel(pages: List<ResolvedPage>, current: Int, enabled: Boolean, backdrop: Boolean, onOpen: () -> Unit) {
    if (!PageList.showLabel(pages)) return
    val i = current.coerceIn(0, pages.size - 1)
    val name = PageList.title(pages[i]) ?: stringResource(R.string.page_untitled, i + 1)
    val tappable = PageList.tappable(pages)
    val spoken = if (tappable) stringResource(R.string.page_a11y, name, i + 1, pages.size) else name
    val hint = stringResource(R.string.pages_hint)
    val maxW = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(96).dp
    val style = TextStyle(color = Color.White, fontSize = 14.sp, shadow = Shadow(Color(0x80000000), Offset(0f, 1f), 1f))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(min = if (tappable) 96.dp else 0.dp, max = maxW).heightIn(min = if (tappable) 48.dp else 0.dp)
                .then(if (tappable) Modifier.clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Row(LabelBackdrop.modifier(backdrop), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tappable) Text(" ▾", style = style.copy(fontSize = 12.sp))
            }
        }
    }
}

/**
 * §1.3 page list: title `Pages`, rows 56 dp, current row with a leading ✓ and bold text (others keep a blank 24 dp slot).
 * Opens scrolled to the current page, with focus on its row. Tap → [onPick] (the caller closes and jumps, or just
 * closes for the current page). TalkBack: list with collection info; rows `<name>, page N of M`, current one selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListSheet(rows: List<PageRow>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val current = rows.indexOfFirst { it.current }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismiss) {
        Text(stringResource(R.string.pages_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp).semantics { collectionInfo = CollectionInfo(rows.size, 1) }, state = state) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, r ->
                val name = r.title ?: stringResource(R.string.page_untitled, r.number)
                val spoken = stringResource(R.string.page_a11y, name, r.number, rows.size)
                Row(
                    Modifier.fillMaxWidth().height(56.dp)
                        .then(if (r.current) Modifier.focusRequester(focus) else Modifier)
                        .clickable { onPick(i) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = spoken; selected = r.current
                            collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(24.dp)) { if (r.current) Text("✓") }
                    Text(name, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (r.current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
