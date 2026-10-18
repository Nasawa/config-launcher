package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
 * pointer position into an [Edit]: a page cell (MoveToCell / Add for Unsorted apps) or a dock slot.
 */
class DragController(private val engine: () -> ConfigEngine, private val onCancel: () -> Unit = {}, private val submit: (Edit) -> Unit) {
    var dragged by mutableStateOf<ResolvedItem?>(null); private set
    var offset by mutableStateOf(Offset.Zero); private set
    private var startRect = Rect.Zero
    private var fromSynthetic = false
    private val rects = HashMap<String, Rect>()

    var gridRect = Rect.Zero
    var dockRect = Rect.Zero
    var dockCount = 0
    var grid = Grid()
    var currentPageId: String? = null
    var currentPageSynthetic = false
    var firstPageId: String? = null

    fun start(item: ResolvedItem, rect: Rect, synthetic: Boolean) {
        dragged = item; startRect = rect; offset = Offset.Zero; fromSynthetic = synthetic
        engine().dragging = true
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
                val index = ((c.x - dockRect.left) / dockRect.width * slots).toInt().coerceIn(0, dockCount)
                if (fromSynthetic) Edit.Add(item.item.at(null, null).withNewId(), null, index = index)
                else Edit.MoveToDock(item.item.id, index)
            }
            gridRect.contains(c) && !currentPageSynthetic && currentPageId != null -> {
                val x = ((c.x - gridRect.left) / gridRect.width * grid.columns).toInt().coerceIn(0, grid.columns - 1)
                val y = ((c.y - gridRect.top) / gridRect.height * grid.rows).toInt().coerceIn(0, grid.rows - 1)
                if (fromSynthetic) Edit.Add(item.item.withNewId(), currentPageId, x, y)
                else Edit.MoveToCell(item.item.id, currentPageId!!, x, y)
            }
            // Dropped on the Unsorted page: "add to home" on the first page's first free cell.
            fromSynthetic && firstPageId != null -> Edit.Add(item.item.withNewId(), firstPageId)
            else -> null
        }
        end()
        if (e != null) submit(e) else onCancel()
    }

    private fun dev.nasawa.configlauncher.model.Item.withNewId() =
        (this as dev.nasawa.configlauncher.model.AppItem).copy(id = Edits.newId(engine().state.value.config, packageName))

    /** Modifier for a draggable tile. */
    fun Modifier.draggable(item: ResolvedItem, synthetic: Boolean): Modifier {
        val key = item.item.id + "|" + item.app?.key
        val isMe = dragged?.item?.id == item.item.id && dragged?.app == item.app
        return this
            .onGloballyPositioned { rects[key] = it.boundsInRoot() }
            .pointerInput(item.item.id, item.app?.key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start(item, rects[key] ?: Rect.Zero, synthetic) },
                    onDrag = { change, amount -> change.consume(); move(amount) },
                    onDragEnd = { drop() },
                    onDragCancel = { cancel() },
                )
            }
            .then(if (isMe) Modifier.zIndex(10f).graphicsLayer { translationX = offset.x; translationY = offset.y; scaleX = 1.15f; scaleY = 1.15f } else Modifier)
    }
}
