package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.ResolvedItem

/**
 * ux.md §12: every drag action also exists as a custom accessibility action on the item, so the layout
 * is editable without drag. Each is one write.
 */
enum class TileAction { MOVE_TO, MOVE_TO_DOCK, REMOVE, HIDE }

enum class TilePlace { PAGE, DOCK, FOLDER }

object TileActions {
    /**
     * [dockFull]: the dock is at the drag cap, so `Move to dock` would be refused.
     * Folder children only get `Remove from home` (moving out of a folder isn't a drag target yet).
     */
    fun of(place: TilePlace, dockFull: Boolean, canHide: Boolean = false): List<TileAction> = when (place) {
        TilePlace.PAGE -> listOfNotNull(TileAction.MOVE_TO, TileAction.MOVE_TO_DOCK.takeUnless { dockFull }, TileAction.REMOVE)
        TilePlace.DOCK -> listOf(TileAction.MOVE_TO, TileAction.REMOVE)
        TilePlace.FOLDER -> listOf(TileAction.REMOVE)
    } + listOfNotNull(TileAction.HIDE.takeIf { canHide })   // [canHide]: an app (hides its package from the drawer)
}

/** ux.md §12 `Move to…` with a page/row/column picker. Starts at the item's own cell when it's on a page. */
@Composable
fun MoveToDialog(r: ResolvedItem, pageIds: List<String>, grid: Grid, onMove: (String, Int, Int) -> Unit, onDismiss: () -> Unit) {
    if (pageIds.isEmpty()) { onDismiss(); return }
    var page by remember { mutableIntStateOf(0) }
    var x by remember { mutableIntStateOf((r.item.x ?: 0).coerceIn(0, grid.columns - 1)) }
    var y by remember { mutableIntStateOf((r.item.y ?: 0).coerceIn(0, grid.rows - 1)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_title, Labels.clean(r.title))) },
        text = {
            Column {
                Stepper(stringResource(R.string.move_to_page, page + 1, pageIds.size), page, pageIds.size) { page = it }
                Stepper(stringResource(R.string.move_to_row, y + 1), y, grid.rows) { y = it }
                Stepper(stringResource(R.string.move_to_column, x + 1), x, grid.columns) { x = it }
            }
        },
        confirmButton = { TextButton({ onMove(pageIds[page], x, y) }) { Text(stringResource(R.string.move_to_confirm)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, size: Int, set: (Int) -> Unit) {
    val less = stringResource(R.string.a11y_decrease, label); val more = stringResource(R.string.a11y_increase, label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton({ set((value - 1).coerceAtLeast(0)) }, Modifier.semantics { contentDescription = less }, enabled = value > 0) { Text("−") }
        Text(label, Modifier.weight(1f))
        TextButton({ set((value + 1).coerceAtMost(size - 1)) }, Modifier.semantics { contentDescription = more }, enabled = value < size - 1) { Text("+") }
    }
}
