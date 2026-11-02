package dev.nasawa.configlauncher.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.map

/**
 * ux.md §1.5 Foldables: a separating hinge (half-open, or a dual screen) puts home in one half.
 * Pure geometry in window pixels, the space FoldingFeature.bounds uses (home is edge-to-edge).
 * A flat hinge (`isSeparating == false`) or no hinge is null: today's layout.
 */
sealed interface FoldSplit {
    /** Vertical hinge: home in the start half [startPx] wide; the end half [endPx] wide is wallpaper. */
    data class Book(val startPx: Int, val endPx: Int) : FoldSplit
    /** Horizontal hinge: grid above [hingeTopPx], dots and dock below [hingeBottomPx]. */
    data class Tabletop(val hingeTopPx: Int, val hingeBottomPx: Int) : FoldSplit

    companion object {
        fun of(windowW: Int, windowH: Int, left: Int, top: Int, right: Int, bottom: Int, vertical: Boolean, separating: Boolean, rtl: Boolean): FoldSplit? {
            if (!separating) return null
            return if (vertical) {
                val start = if (rtl) windowW - right else left
                val end = if (rtl) left else windowW - right
                if (start <= 0 || end < 0) null else Book(start, end)
            } else {
                if (top <= 0 || bottom >= windowH) null else Tabletop(top, bottom)
            }
        }
    }
}

/** The current split, read only through WindowInfoTracker (#197); null until the first emission. */
@Composable
fun rememberFoldSplit(): FoldSplit? {
    val activity = LocalActivity.current ?: return null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val flow = remember(activity, rtl) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val w = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstNotNullOfOrNull { f ->
                val b = f.bounds
                // bounds are already in window coordinates.
                FoldSplit.of(w.width(), w.height(), b.left, b.top, b.right, b.bottom,
                    f.orientation == FoldingFeature.Orientation.VERTICAL, f.isSeparating, rtl)
            }
        }
    }
    val split by flow.collectAsState(null)
    return split
}
