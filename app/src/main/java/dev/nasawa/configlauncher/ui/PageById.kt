package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * #188 / ux.md §1.5 "what's kept": the pager keeps its page by page `id`, not by index, across a window change
 * (rotate, fold, resize, multi-window: the activity is recreated) and across a config reload that reorders,
 * adds or removes pages.
 */
object PageById {
    /**
     * The index to show for [ids]: where [id] is now, or, if that page is gone (or nothing was saved),
     * [lastIndex] clamped to the new list (the page that took its place, or the new last page).
     */
    fun target(ids: List<String>, id: String?, lastIndex: Int): Int {
        if (ids.isEmpty()) return 0
        val at = if (id == null) -1 else ids.indexOf(id)
        return if (at >= 0) at else lastIndex.coerceIn(0, ids.size - 1)
    }
}

/**
 * A [PagerState] for [ids] that follows the settled page's id. The id and index are kept in saved state
 * (rememberSaveable), so a recreated activity starts on the same page by id; the pager's own index-only
 * saver isn't used. When [ids] changes (reload, or a per-window page set), the pager jumps to that id's new
 * index, or falls back as in [PageById.target]. Nothing is written.
 */
/** The page shown, by id and index; saved with the activity's state (see [rememberPagerById]). */
private class PageMemory(var id: String?, var index: Int) {
    /** What's on screen now, when it can be trusted. Read when the activity saves its state. */
    var live: () -> Pair<String, Int>? = { null }

    companion object {
        val Saver = listSaver<PageMemory, Any?>(
            save = { m -> val l = m.live(); listOf(l?.first ?: m.id, l?.second ?: m.index) },
            restore = { PageMemory(it[0] as String?, it[1] as Int) },
        )
    }
}

@Composable
fun rememberPagerById(ids: List<String>): PagerState {
    val memory = rememberSaveable(saver = PageMemory.Saver) { PageMemory(null, 0) }
    val latest by rememberUpdatedState(ids)
    val pager = remember { PagerState(currentPage = PageById.target(ids, memory.id, memory.index)) { latest.size } }
    // The list the saved id was last matched against (the pager starts matched to the first one). Until the
    // effect below has handled a new list, the pager's index may still point into the old order (or be
    // clamped), so it mustn't overwrite the id.
    var handled by remember { mutableStateOf(ids) }
    // Read from the pager at save time too, so a page change with no frame since (a scroll, then an immediate
    // rotate) is still what's saved.
    memory.live = {
        val i = pager.settledPage
        // #214 (4): a pager measured at zero width snaps to its last page; that index is not a page anyone chose.
        val laidOut = pager.layoutInfo.pageSize > 0
        if (laidOut && handled == latest && i == pager.currentPage) latest.getOrNull(i)?.let { it to i } else null
    }
    // Track what's on screen once it settles (swipe, page list, Home → page 1), for the reload effect below.
    val settled = pager.settledPage
    SideEffect { memory.live()?.let { (id, i) -> if (i == settled) { memory.id = id; memory.index = i } } }
    // The page list changed (reload, or a different page set after recreation): keep the same id, or fall back.
    LaunchedEffect(ids) {
        val t = PageById.target(ids, memory.id, memory.index)
        if (ids.isNotEmpty() && pager.currentPage != t) pager.scrollToPage(t)
        ids.getOrNull(t)?.let { memory.id = it; memory.index = t }
        handled = ids
    }
    return pager
}

/**
 * #214 (4): don't measure the pager while it is given zero width. A [androidx.compose.foundation.pager.HorizontalPager]
 * measured at width 0 has page size 0 and snaps to its last page, and stays there when the width comes back. A
 * window resize or density change can lay the old window out at zero width for a frame, which (saved on the
 * recreate that follows) put an RTL tablet or phone on the last page. Skipping that measure keeps the pager where it
 * was; nothing is drawn for a zero-width frame anyway.
 */
fun Modifier.keepPageWhenCollapsed(): Modifier = layout { measurable, constraints ->
    if (constraints.maxWidth == 0) layout(0, constraints.minHeight) {}
    else measurable.measure(constraints).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
}
