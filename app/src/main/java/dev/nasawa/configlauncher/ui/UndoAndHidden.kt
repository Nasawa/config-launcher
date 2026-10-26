package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.model.Profile

/** ux.md §14 R3/R8: snackbar [text] with `Undo`, which queues [undo] as a normal edit (same hash-checked write path). */
data class UndoSnack(val text: String, val undo: Edit, val label: String) {
    companion object { const val DURATION_MS = 10_000L }   // the §18.2 Undo window
}

@Composable
fun UndoSnackbar(u: UndoSnack, onUndo: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(u) { kotlinx.coroutines.delay(UndoSnack.DURATION_MS); onDismiss() }
    Snackbar(
        // TalkBack reads the same text (R3).
        Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        action = { TextButton(onUndo) { Text(stringResource(R.string.merge_undo)) } },
        dismissAction = { TextButton(onDismiss) { Text("✕") } },
    ) { Text(u.text) }
}

/** One row of the interim Hidden apps sheet (§14 R8). [app] null = hidden but not installed: package only. */
data class HiddenRow(val packageName: String, val label: String, val app: InstalledApp?)

object HiddenApps {
    /** `drawer.hidden` in file order, labelled from the personal-profile install when there is one. */
    fun rows(c: LauncherConfig, installed: List<InstalledApp>): List<HiddenRow> =
        c.drawer?.hidden.orEmpty().distinct().map { pkg ->
            val app = installed.filter { it.packageName == pkg }.minByOrNull { if (it.profile == Profile.PERSONAL) 0 else 1 }
            HiddenRow(pkg, app?.label?.let(Labels::clean) ?: pkg, app)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAppsSheet(rows: List<HiddenRow>, apps: InstalledApps, onUnhide: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss) {
        Text(stringResource(R.string.hidden_apps), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (rows.isEmpty()) Text(stringResource(R.string.no_hidden_apps), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(rows, key = { it.packageName }) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIconImage(r.app, apps, 40)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(r.label)
                        Text(r.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onUnhide(r.packageName) }) { Text(stringResource(R.string.action_unhide)) }
                }
            }
        }
    }
}
