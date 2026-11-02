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
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt
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
    /** #228: each draggable tile's unclipped root bounds and its visual, for the [DragOverlay]. */
    private val unclipped = HashMap<String, Rect>()
    private val visuals = HashMap<String, @Composable () -> Unit>()

    /** #228 (Archimedes): where the lifted tile sat at pickup, unclipped root px; the overlay starts there. */
    var liftedOriginInRoot = Rect.Zero; private set
    /** What the overlay draws for the lifted tile (null: the tile registered no visual). */
    internal var liftedVisual: (@Composable () -> Unit)? = null; private set

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

    /** The lifted tile's offset from [liftedOriginInRoot] and its scale, as the [DragOverlay] draws it. */
    val visualOffset: Offset get() = if (dragged == null) Offset.Zero else offset * lift
    val visualScale: Float get() = if (dragged == null) 1f else 1f + (LIFT_SCALE - 1f) * lift

    fun start(item: ResolvedItem, rect: Rect) {
        settle() // a new long-press during a spring-back snaps it to the end first
        val k = key(item)
        liftedOriginInRoot = unclipped[k] ?: rect; liftedVisual = visuals[k]
        dragged = item; startRect = rect; offset = Offset.Zero; lift = 1f
        engine().dragging = true
        onStart()
    }

    fun move(delta: Offset) { if (!returning) offset += delta }

    private fun end() { returnJob?.cancel(); returnJob = null; returning = false; dragged = null; offset = Offset.Zero; lift = 1f; liftedVisual = null }

    private fun key(item: ResolvedItem) = item.item.id + "|" + item.app?.key

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

    /**
     * §17 Q6: a cancelled drag has no pending op, so a held incoming file is applied right away.
     * A pointer cancel (#214 Step.CANCEL) springs back like a refused drop (#212).
     */
    fun cancel() { if (returning) return; springBack() }

    /**
     * The gesture coroutine ended mid-drag (tile left composition, Activity destroyed / recreated):
     * clear at once, no animation (the layout under the tile is gone), and never write.
     */
    fun cancelNow() {
        if (returning) { end(); return }
        if (dragged == null) return
        engine().dragging = false
        end()
        onCancel()
    }

    /** The tile for [id]/[app] left composition (recreate, reload, page disposed): a drag on it ends at once. */
    internal fun detached(id: String, app: Any?) { if (dragged?.item?.id == id && dragged?.app == app) cancelNow() }

    /** [item] is the lifted tile, during the drag and its spring-back (#228: its source node is drawn at alpha 0). */
    fun isLifted(item: ResolvedItem): Boolean = dragged?.item?.id == item.item.id && dragged?.app == item.app

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
     *
     * #228: while lifted the tile stays composed and keeps the gesture, its semantics and its hit area (the
     * #212/#214 cancel paths run from this coroutine's `finally`), but draws at alpha 0; the root
     * [DragOverlay] draws [visual] instead, so no cell, page, pager or region clips it.
     */
    fun Modifier.tile(
        item: ResolvedItem, canDrag: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
        actions: List<CustomAccessibilityAction> = emptyList(),
        visual: (@Composable () -> Unit)? = null,
    ): Modifier {
        val key = key(item)
        if (visual != null) visuals[key] = visual else visuals.remove(key)
        val isMe = isLifted(item)
        return this
            .onGloballyPositioned { rects[key] = it.boundsInRoot(); unclipped[key] = Rect(it.localToRoot(Offset.Zero), it.size.toSize()) }
            // #212/#214: leaving composition sends a pointer cancel before the gesture coroutine ends; this
            // makes that path instant (no spring-back toward a layout that is gone) whichever runs first.
            .then(DetachElement(this@DragController, item.item.id, item.app))
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
                    var mine = false // this gesture started the drag and has not ended it yet
                    try {
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (ch == null) { if (mine) { mine = false; cancel() }; break }
                            // #214 (5): an ACTION_CANCEL (rotate / window resize mid-drag) arrives as a lift that is already
                            // consumed. It must cancel, never drop (Compose's own waitForUpOrCancellation reads it the same way).
                            when (res.next(ch.position, ch.pressed, ch.isConsumed)) {
                                LongPress.Step.START_DRAG -> { start(item, rects[key] ?: Rect.Zero); mine = true; move(ch.position - down.position); ch.consume() }
                                LongPress.Step.DRAG -> { move(ch.positionChange()); ch.consume() }
                                LongPress.Step.DROP -> { ch.consume(); mine = false; drop(); break }
                                LongPress.Step.MENU -> { ch.consume(); onLongClick(); break }
                                LongPress.Step.CANCEL -> { if (mine) { mine = false; cancel() }; break }
                                LongPress.Step.WAIT -> ch.consume()
                            }
                        }
                    } finally {
                        // The gesture coroutine was cancelled (tile left composition, Activity destroyed) mid-drag: no write,
                        // and release the engine's dragging hold so a held incoming file isn't stuck. Instant (#212): the
                        // spring-back is only for a pointer cancel or a refused drop while the screen is still there.
                        if (mine) cancelNow()
                    }
                }
            }
            .then(if (isMe) Modifier.alpha(0f) else Modifier)
    }
}

/**
 * #228 (Archimedes): the one drag layer, the last child of the HomeScreen root. While a tile is lifted it
 * draws that tile's visual at [DragController.liftedOriginInRoot] + [DragController.visualOffset], scaled by
 * [DragController.visualScale], so the drag and the #212 spring-back stay whole over the dock, the rail
 * (either edge) and across a fold split. Placement is absolute px (`place`, a graphicsLayer translation),
 * never `offset`, which would mirror x in RTL; root and pointer coordinates are physical. No pointer
 * input (hit-testing and drops are unchanged) and no semantics: TalkBack sees only the source tile.
 */
@Composable
fun DragOverlay(drag: DragController, modifier: Modifier = Modifier) {
    val host = remember { floatArrayOf(0f, 0f) }
    Layout(
        content = {
            val visual = drag.liftedVisual
            if (drag.dragged != null && visual != null) {
                val r = drag.liftedOriginInRoot
                Box(
                    Modifier
                        .layout { m, _ ->
                            val w = r.width.roundToInt().coerceAtLeast(0); val h = r.height.roundToInt().coerceAtLeast(0)
                            val p = m.measure(Constraints.fixed(w, h))
                            layout(w, h) { p.place(0, 0) }
                        }
                        .graphicsLayer {
                            val o = drag.visualOffset; val k = drag.visualScale
                            translationX = r.left - host[0] + o.x; translationY = r.top - host[1] + o.y
                            scaleX = k; scaleY = k
                        }
                        .testTag(DRAG_OVERLAY_TAG)
                        .clearAndSetSemantics {},
                ) { visual() }
            }
        },
        modifier = modifier.onGloballyPositioned { val o = it.localToRoot(Offset.Zero); host[0] = o.x; host[1] = o.y },
    ) { ms, c ->
        val ps = ms.map { it.measure(Constraints()) }
        layout(c.maxWidth, c.maxHeight) { ps.forEach { it.place(0, 0) } } // absolute: never mirrored in RTL
    }
}

/** Test tag of the [DragOverlay]'s lifted-tile node (no semantics besides the tag). */
const val DRAG_OVERLAY_TAG = "drag-overlay"

private data class DetachElement(val drag: DragController, val id: String, val app: Any?) : ModifierNodeElement<DetachNode>() {
    override fun create() = DetachNode(drag, id, app)
    override fun update(node: DetachNode) { node.drag = drag; node.id = id; node.app = app }
}

private class DetachNode(var drag: DragController, var id: String, var app: Any?) : Modifier.Node() {
    override fun onDetach() { drag.detached(id, app) }
}

/** Pure state machine for the part of a gesture after the long-press timeout (unit-tested). */
class LongPress private constructor(private val origin: Offset, private val slop: Float, private val canDrag: Boolean) {
    enum class Step { WAIT, START_DRAG, DRAG, DROP, MENU, CANCEL }
    var dragging = false; private set

    /** [consumed]: a lift that someone already consumed is a pointer cancel, not a release (#214). */
    fun next(pos: Offset, pressed: Boolean, consumed: Boolean = false): Step = when {
        !pressed && consumed -> Step.CANCEL
        !pressed -> if (dragging) Step.DROP else Step.MENU
        dragging -> Step.DRAG
        canDrag && (pos - origin).getDistance() > slop -> { dragging = true; Step.START_DRAG }
        else -> Step.WAIT
    }

    companion object { fun track(origin: Offset, slop: Float, canDrag: Boolean) = LongPress(origin, slop, canDrag) }
}
