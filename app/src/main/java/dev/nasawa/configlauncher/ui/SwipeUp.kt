package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** ux.md §1.4 (#77): swipe up anywhere on home opens the drawer. */
object SwipeUp {
    val THRESHOLD = 48.dp          // ≥ 48 dp up
    const val RATIO = 1.5f         // vertical-dominant: |dy| > 1.5 × |dx|
    val FLING_MIN_DISTANCE = 16.dp // a fling counts after ≥ 16 dp
    val FLING_VELOCITY = 1000.dp   // per second

    enum class Axis { HORIZONTAL, UP, DOWN }

    /** "Whichever axis passes touch slop first wins": null until the movement [d] passes [slopPx]. */
    fun axis(d: Offset, slopPx: Float): Axis? = when {
        abs(d.x) <= slopPx && abs(d.y) <= slopPx -> null
        abs(d.x) >= abs(d.y) -> Axis.HORIZONTAL
        d.y < 0 -> Axis.UP
        else -> Axis.DOWN
    }

    private fun vertical(d: Offset) = abs(d.y) > RATIO * abs(d.x)

    fun opens(d: Offset, thresholdPx: Float): Boolean = d.y <= -thresholdPx && vertical(d)

    /** Released with an upward fling faster than [minVelocityPx] per second, after ≥ [minDistancePx]. */
    fun flings(d: Offset, velocityY: Float, minDistancePx: Float, minVelocityPx: Float): Boolean =
        d.y <= -minDistancePx && velocityY <= -minVelocityPx && vertical(d)
}

/**
 * Watches unconsumed moves after children (Main pass). Off when [enabled] is false (edit mode, drag, drawer open).
 * Ends without opening when: the horizontal or downward axis passes slop first (pager / nothing), a child consumed
 * the move (tile drag, pager), a long-press fired first (no axis before the long-press timeout), or the press
 * started in the bottom system-gesture strip [excludeBottomPx].
 *
 * #191: "a long-press fired first" is timed on the same clock as the tile and home long-press detectors (a timeout
 * from when the down was handled), not from event timestamps. Right after a cold start the main thread is busy, so
 * the down is handled late and the rest of the swipe arrives as one late event, often just the lift far from the
 * down (API 29 `input swipe` waits for the down to be handled). Its timestamp is past the timeout although no
 * long-press fired, and with no moves there is no fling velocity either, so the first swipe did nothing. A lift that
 * has travelled the open distance therefore opens too; it is claimed in the Initial pass, before a tile or the home
 * grid would take it as a tap.
 */
fun Modifier.swipeUpToOpen(enabled: () -> Boolean, excludeBottomPx: () -> Float = { 0f }, onOpen: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled() || down.position.y > size.height - excludeBottomPx()) return@awaitEachGesture
        val threshold = SwipeUp.THRESHOLD.toPx()
        val flingDist = SwipeUp.FLING_MIN_DISTANCE.toPx(); val flingVel = SwipeUp.FLING_VELOCITY.toPx()
        val slop = viewConfiguration.touchSlop
        val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var axis: SwipeUp.Axis? = null
        /** One pointer event; true when this gesture is over for the detector. */
        suspend fun AwaitPointerEventScope.step(): Boolean {
            // A lift that travelled the open distance up is a swipe, not a tap: take it before children see it.
            val early = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return true
            if (!early.pressed && !early.isConsumed && axis != SwipeUp.Axis.HORIZONTAL && axis != SwipeUp.Axis.DOWN) {
                val d = early.position - down.position
                if (SwipeUp.axis(d, slop) == SwipeUp.Axis.UP && SwipeUp.opens(d, threshold) && enabled()) {
                    early.consume(); onOpen(); return true
                }
            }
            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return true
            if (ch.isConsumed) return true
            vt.addPosition(ch.uptimeMillis, ch.position)
            val d = ch.position - down.position
            if (axis == null) axis = SwipeUp.axis(d, slop)
            if (axis == SwipeUp.Axis.HORIZONTAL || axis == SwipeUp.Axis.DOWN) return true
            if (!ch.pressed) {
                if (axis == SwipeUp.Axis.UP && enabled() && SwipeUp.flings(d, vt.calculateVelocity().y, flingDist, flingVel)) onOpen()
                return true
            }
            if (axis == SwipeUp.Axis.UP && SwipeUp.opens(d, threshold)) {
                if (enabled()) { ch.consume(); onOpen() }
                return true
            }
            return false
        }
        // Until an axis passes slop: if the long-press timeout runs out first, the long-press fired first, so no swipe.
        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var over = false
            while (!over && axis == null) over = step()
            over
        } ?: return@awaitEachGesture
        if (decided) return@awaitEachGesture
        while (!step()) Unit
    }
}
