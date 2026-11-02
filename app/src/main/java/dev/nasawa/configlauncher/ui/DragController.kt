package dev.nasawa.configlauncher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.store.ConfigEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Long-press drag to reorder. Tracks the dragged tile in root coordinates and on drop turns the
 * pointer position into an [Edit]: a page cell (MoveToCell) or a dock slot (MoveToDock).
 */
class DragController(private val engine: () -> ConfigEngine, private val onCancel: () -> Unit = {}, private val onStart: () -> Unit = {}, private val submit: (Edit) -> Unit) {
    var dragged by mutableStateOf<ResolvedItem?>(null); private set
    var offset by mutableStateOf(Offset.Zero); private set
    private var startRect = Rect.Zero
    private val rects = HashMap<String, Rect>()

    var gridRect = Rect.Zero
    var dockRect = Rect.Zero
    var dockCount = 0
    /** True when the dock is the §1.5 end-edge rail (drop index follows Y). */
    var dockVertical = false
    /** §1.5 RTL (#187): columns and a bottom dock mirror; rects stay physical (boundsInRoot). */
    var rtl = false
    var grid = Grid()
    var currentPageId: String? = null

    /**
     * #212 (ux.md §1.5/§4.2): 1 while lifted, eased to 0 while a refused or cancelled drag springs back.
     * The translation and the 1.15 lift scale both follow it, so they return together.
     */
    var lift by mutableFloatStateOf(1f); private set
    /** True while a refused/cancelled drag springs back; [dragged] stays set so the pager and new drags wait. */
    var returning by mutableStateOf(false); private set
    /** Where the spring-back runs: a composition scope (its frame clock). Null → instant, as before #212. */
    var scope: CoroutineScope? = null
    /** §12 reduced motion (wired to [ReducedMotion.on]): the return is instant. */
    var reducedMotion: () -> Boolean = { false }
    private var returnJob: Job? = null

    /** What the dragged tile's graphicsLayer draws (identity when nothing is lifted). */
    val visualOffset: Offset get() = if (dragged == null) Offset.Zero else offset * lift
    val visualScale: Float get() = if (dragged == null) 1f else 1f + (LIFT_SCALE - 1f) * lift

    fun start(item: ResolvedItem, rect: Rect) {
        settle() // a new long-press during a spring-back snaps it to the end first
        dragged = item; startRect = rect; offset = Offset.Zero; lift = 1f
        engine().dragging = true
        onStart()
    }

    fun move(delta: Offset) { if (!returning) offset += delta }

    private fun end() { returnJob?.cancel(); returnJob = null; returning = false; dragged = null; offset = Offset.Zero; lift = 1f }

    /** Finishes a running spring-back at once (nothing lifted afterwards). */
    fun settle() { if (returning) end() }

    /**
     * #212: nothing is written; `dragging` drops right away so the §17 Q6 held-file apply isn't
     * delayed, then the tile eases back to its cell. Leaving composition (rotate, recreate) cancels
     * the scope and the `finally` clears the lift.
     */
    private fun springBack() {
        if (dragged == null || returning) return
        engine().dragging = false
        onCancel()
        val s = scope
        if (s == null || reducedMotion() || offset == Offset.Zero) { end(); return }
        returning = true
        var job: Job? = null
        job = s.launch {
            try {
                animate(lift, 0f, animationSpec = RETURN_SPEC) { v, _ -> lift = v }
            } finally {
                if (returnJob === job) end()
            }
        }
        returnJob = job
        if (job.isCompleted) end() // a scope that is already cancelled
    }

    /** §17 Q6: a cancelled drag has no pending op, so a held incoming file is applied right away. */
    fun cancel() { if (returning) return; springBack() }

    fun drop() {
        val item = dragged ?: return
        if (returning) return
        val c = startRect.center + offset
        val e: Edit? = when {
            dockRect.contains(c) -> {
                val index = GridHit.dockIndex(
                    c.x, c.y, dockRect.left, dockRect.top, dockRect.width, dockRect.height,
                    dockCount, dockVertical, rtl,
                )
                Edit.MoveToDock(item.item.id, index)
            }
            gridRect.contains(c) && currentPageId != null -> {
                GridHit.cellInBlock(
                    c.x, c.y, gridRect.left, gridRect.top, gridRect.width, gridRect.height,
                    grid.columns, grid.rows, rtl,
                )?.let { (x, y) -> Edit.MoveToCell(item.item.id, currentPageId!!, x, y) }
            }
            else -> null
        }
        if (e == null) { springBack(); return }
        end(); engine().dragging = false
        submit(e)
    }

    companion object {
        const val LIFT_SCALE = 1.15f
        /** Critically damped (no overshoot); settles in about 170–200 ms. */
        val RETURN_SPEC = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    }

    /**
     * Tile gestures (#53, ux.md §1.1/§4.1): tap → [onClick]; hold past the long-press timeout and
     * release without moving → [onLongClick] (item sheet); hold then move more than 8 dp → drag (when
     * [canDrag]). A release after a long-press never fires [onClick].
     */
    fun Modifier.tile(
        item: ResolvedItem, canDrag: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
        actions: List<CustomAccessibilityAction> = emptyList(),
    ): Modifier {
        val key = item.item.id + "|" + item.app?.key
        val isMe = dragged?.item?.id == item.item.id && dragged?.app == item.app
        return this
            .onGloballyPositioned { rects[key] = it.boundsInRoot() }
            // One TalkBack node: the label merges into the node that owns tap, long-press (the item sheet)
            // and the ux.md §12 custom actions.
            .semantics(mergeDescendants = true) {
                onClick { onClick(); true }; onLongClick { onLongClick(); true }
                if (actions.isNotEmpty()) customActions = actions
            }
            .pointerInput(item.item.id, item.app?.key, canDrag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var up: PointerInputChange? = null
                    var ended = false
                    val tapPhase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        up = waitForUpOrCancellation(); ended = true
                    }
                    if (tapPhase != null || ended) {
                        up?.let { it.consume(); onClick() }
                        return@awaitEachGesture
                    }
                    val res = LongPress.track(down.position, 8.dp.toPx(), canDrag)
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (ch == null) { if (res.dragging) cancel(); break }
                        when (res.next(ch.position, ch.pressed)) {
                            LongPress.Step.START_DRAG -> { start(item, rects[key] ?: Rect.Zero); move(ch.position - down.position); ch.consume() }
                            LongPress.Step.DRAG -> { move(ch.positionChange()); ch.consume() }
                            LongPress.Step.DROP -> { ch.consume(); drop(); break }
                            LongPress.Step.MENU -> { ch.consume(); onLongClick(); break }
                            LongPress.Step.WAIT -> ch.consume()
                        }
                    }
                }
            }
            .then(if (isMe) Modifier.zIndex(10f).graphicsLayer { val o = visualOffset; val k = visualScale; translationX = o.x; translationY = o.y; scaleX = k; scaleY = k } else Modifier)
    }
}

/** Pure state machine for the part of a gesture after the long-press timeout (unit-tested). */
class LongPress private constructor(private val origin: Offset, private val slop: Float, private val canDrag: Boolean) {
    enum class Step { WAIT, START_DRAG, DRAG, DROP, MENU }
    var dragging = false; private set

    fun next(pos: Offset, pressed: Boolean): Step = when {
        !pressed -> if (dragging) Step.DROP else Step.MENU
        dragging -> Step.DRAG
        canDrag && (pos - origin).getDistance() > slop -> { dragging = true; Step.START_DRAG }
        else -> Step.WAIT
    }

    companion object { fun track(origin: Offset, slop: Float, canDrag: Boolean) = LongPress(origin, slop, canDrag) }
}
