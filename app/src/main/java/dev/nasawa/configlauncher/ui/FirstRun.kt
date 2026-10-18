package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.store.ConfigEngine.Inspection

/** docs/ux.md §7 step 2 with the §18.2 "Skip for now" secondary button. */
@Composable
fun FirstRunScreen(onChooseFolder: () -> Unit, onSkip: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Choose your config folder", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pick a folder for launcher.json. A folder that a sync app (like Syncthing) or a git app keeps in sync lets you edit it from your computer, with history. Config Launcher also keeps a small .configlauncher folder there for status and backups.",
                    color = Color.LightGray,
                )
                Spacer(Modifier.height(24.dp))
                Button(onChooseFolder, Modifier.fillMaxWidth()) { Text("Choose folder") }
                TextButton(onSkip, Modifier.fillMaxWidth()) { Text("Skip for now") }
                Text("Skipping keeps your layout on this phone only. You can choose a folder later from the ⋮ menu.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
fun CloudDialog(onUseAnyway: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onUseAnyway) { Text("Use anyway") } },
    dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    text = { Text("Cloud folders can take a while to sync. A Syncthing or git folder works best.") },
)

/** §18.2: the picked folder already has a launcher.json. [onChoose] gets true for "Replace it with mine". */
@Composable
fun MoveDialog(ins: Inspection, onChoose: (Boolean) -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("This folder already has a launcher.json.") },
    text = {
        Text(when (ins) {
            is Inspection.Valid -> "It has ${ins.pages} page${if (ins.pages == 1) "" else "s"} and ${ins.items} items. If you replace it, the folder's file is first copied to .configlauncher/backup/."
            is Inspection.Invalid -> "It has problems:\n" + ins.errors.take(3).joinToString("\n") { "• $it" } + "\nIf you replace it, it is first copied to .configlauncher/backup/."
            Inspection.Empty -> ""
        })
    },
    confirmButton = { TextButton({ onChoose(true) }) { Text("Replace it with mine") } },
    dismissButton = { TextButton({ onChoose(false) }) { Text("Use the folder's layout") } },
)
