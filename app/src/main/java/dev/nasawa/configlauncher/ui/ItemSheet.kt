package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.FolderItem
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.model.ShortcutItem

enum class ItemAction { EDIT_LAYOUT, APP_INFO, REMOVE }

/**
 * ux.md §1.1 long-press item sheet. Shows the **real** app name and package (so a label override
 * can't disguise an app), plus `Edit layout`, `App info`, `Remove from home`.
 */
data class ItemSheetModel(
    val title: String,
    /** Installed app's own label; null when not installed (or a folder). */
    val realName: String?,
    val packageName: String?,
    val folderSize: Int?,
    val actions: List<ItemAction>,
) {
    companion object {
        fun of(r: ResolvedItem): ItemSheetModel {
            val pkg = when (val i = r.item) { is AppItem -> i.packageName; is ShortcutItem -> i.packageName; is FolderItem -> null }
            // App info needs an installed package; a folder has none (gap: noted for Pragma).
            val info = pkg != null && r.app != null
            return ItemSheetModel(
                title = Labels.clean(r.title),
                realName = r.app?.label?.let(Labels::clean),
                packageName = pkg,
                folderSize = (r.item as? FolderItem)?.items?.size,
                actions = listOfNotNull(ItemAction.EDIT_LAYOUT, ItemAction.APP_INFO.takeIf { info }, ItemAction.REMOVE),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(r: ResolvedItem, onAction: (ItemAction) -> Unit, onDismiss: () -> Unit) {
    val m = ItemSheetModel.of(r)
    ModalBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium)
            when {
                m.folderSize != null -> Text(pluralStringResource(R.plurals.sheet_folder_apps, m.folderSize, m.folderSize), color = Color.Gray)
                m.realName == null -> Text(stringResource(R.string.sheet_not_installed, m.packageName.orEmpty()), color = Color.Gray)
                else -> {
                    Text(m.realName, style = MaterialTheme.typography.bodyMedium)
                    Text(m.packageName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(12.dp))
            m.actions.forEach { a ->
                TextButton({ onAction(a) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(when (a) {
                        ItemAction.EDIT_LAYOUT -> R.string.action_edit_layout
                        ItemAction.APP_INFO -> R.string.action_app_info
                        ItemAction.REMOVE -> R.string.action_remove_home
                    }), Modifier.fillMaxWidth())
                }
            }
        }
    }
}
