package dev.nasawa.configlauncher.ui

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
    var grid = Grid()
    var currentPageId: String? = null

    fun start(item: ResolvedItem, rect: Rect) {
        dragged = item; startRect = rect; offset = Offset.Zero
        engine().dragging = true
        onStart()
    }

    fun move(delta: Offset) { offset += delta }

    private fun end() { dragged = null; offset = Offset.Zero; engine().dragging = false }

    /** §17 Q6: a cancelled drag has no pending op, so a held incoming file is applied right away. */
    fun cancel() { end(); onCancel() }

    fun drop() {
        val item = dragged ?: return
        val c = startRect.center + offset
        val e: Edit? = when {
            dockRect.contains(c) -> {
                val slots = dockCount + 1
                val index = if (dockVertical) {
                    if (dockRect.height <= 0f) 0
                    else ((c.y - dockRect.top) / dockRect.height * slots).toInt().coerceIn(0, dockCount)
                } else {
                    if (dockRect.width <= 0f) 0
                    else ((c.x - dockRect.left) / dockRect.width * slots).toInt().coerceIn(0, dockCount)
                }
                Edit.MoveToDock(item.item.id, index)
            }
            gridRect.contains(c) && currentPageId != null -> {
                val x = ((c.x - gridRect.left) / gridRect.width * grid.columns).toInt().coerceIn(0, grid.columns - 1)
                val y = ((c.y - gridRect.top) / gridRect.height * grid.rows).toInt().coerceIn(0, grid.rows - 1)
                Edit.MoveToCell(item.item.id, currentPageId!!, x, y)
            }
            else -> null
        }
        end()
        if (e != null) submit(e) else onCancel()
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
            .then(if (isMe) Modifier.zIndex(10f).graphicsLayer { translationX = offset.x; translationY = offset.y; scaleX = 1.15f; scaleY = 1.15f } else Modifier)
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
