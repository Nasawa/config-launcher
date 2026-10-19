package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * #77, ux.md §1/§2: swipe up anywhere on home (grid, empty cells, dock) opens the drawer.
 * Vertical-dominant only, so the horizontal pager and the long-press drag keep their gestures.
 */
object SwipeUp {
    /** Distance before it counts (also well past touch slop). */
    val THRESHOLD = 48.dp

    /** Total movement [d] from the down position: far enough up, and at least twice as vertical as horizontal. */
    fun opens(d: Offset, thresholdPx: Float): Boolean = d.y <= -thresholdPx && abs(d.y) >= 2 * abs(d.x)

    /** Past this sideways movement first, it's a page swipe: stop watching this gesture. */
    fun isHorizontal(d: Offset, slopPx: Float): Boolean = abs(d.x) > slopPx && abs(d.x) > abs(d.y)
}

/**
 * Watches unconsumed moves after children (Main pass): a tile in its drag phase or the pager consumes its own
 * moves, so those never open the drawer. Once a swipe up is recognised the move is consumed, which also
 * cancels a pending tap or long-press underneath.
 */
fun Modifier.swipeUpToOpen(enabled: () -> Boolean, onOpen: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val threshold = SwipeUp.THRESHOLD.toPx()
        while (true) {
            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (ch.isConsumed) break
            val d = ch.position - down.position
            if (SwipeUp.isHorizontal(d, viewConfiguration.touchSlop)) break
            if (SwipeUp.opens(d, threshold)) {
                if (enabled()) { ch.consume(); onOpen() }
                break
            }
        }
    }
}
