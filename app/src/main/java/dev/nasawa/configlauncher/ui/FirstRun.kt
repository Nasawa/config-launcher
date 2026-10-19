package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.store.ConfigEngine.Inspection

/** docs/ux.md §7 step 2 with the §18.2 "Skip for now" secondary button and its confirm sheet. */
@Composable
fun FirstRunScreen(onChooseFolder: () -> Unit, onSkip: () -> Unit, cancelled: Boolean = false) {
    var confirmSkip by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.first_run_title), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.first_run_body),
                    color = Color.LightGray,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.first_run_agent_docs), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)   // §20.1
                Spacer(Modifier.height(24.dp))
                if (cancelled) Text(stringResource(R.string.first_run_cancelled),
                    color = Color(0xFFFFCDD2), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                Button(onChooseFolder, Modifier.fillMaxWidth()) { Text(stringResource(R.string.choose_folder)) }
                TextButton({ confirmSkip = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.skip_for_now)) }
            }
        }
        if (confirmSkip) AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text(stringResource(R.string.skip_confirm_title)) },
            text = { Text(stringResource(R.string.skip_confirm_body)) },
            confirmButton = { TextButton({ confirmSkip = false; onSkip() }) { Text(stringResource(R.string.skip_for_now)) } },
            dismissButton = { TextButton({ confirmSkip = false; onChooseFolder() }) { Text(stringResource(R.string.choose_folder)) } },
        )
    }
}

@Composable
fun CloudDialog(onUseAnyway: () -> Unit, onDismiss: () -> Unit, onChooseAnother: () -> Unit = onDismiss) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onUseAnyway) { Text(stringResource(R.string.cloud_use_anyway)) } },
    dismissButton = { TextButton(onChooseAnother) { Text(stringResource(R.string.cloud_choose_another)) } },
    text = { Text(stringResource(R.string.cloud_body)) },
)

/** §18.2: the picked folder already has a launcher.json. [onChoose] gets true for stringResource(R.string.move_replace). */
@Composable
fun MoveDialog(ins: Inspection, onChoose: (Boolean) -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.move_title)) },
    text = {
        Text(when (ins) {
            is Inspection.Valid -> stringResource(R.string.move_valid, pluralStringResource(R.plurals.move_pages, ins.pages, ins.pages), pluralStringResource(R.plurals.move_items, ins.items, ins.items))
            is Inspection.Invalid -> stringResource(R.string.move_invalid, ins.errors.take(3).joinToString("\n") { "• $it" })
            Inspection.Empty, is Inspection.Unreadable -> ""
        })
    },
    confirmButton = { TextButton({ onChoose(true) }) { Text(stringResource(R.string.move_replace)) } },
    // An invalid folder file is never adopted (Grace review): only Replace or Cancel.
    dismissButton = {
        if (ins is Inspection.Invalid) TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
        else TextButton({ onChoose(false) }) { Text(stringResource(R.string.move_use_folder)) }
    },
)

/** #74: what happens after a folder is picked and inspected. */
object FolderPick {
    enum class Next { CREATE, ASK, PROBLEM }

    /**
     * Empty → write our layout there (via moveTo(useMine = false), which adopts instead if a file appeared meanwhile).
     * A readable file → ask. Unreadable → show the problem and change nothing (never write over what we couldn't read).
     */
    fun next(ins: Inspection): Next = when (ins) {
        Inspection.Empty -> Next.CREATE
        is Inspection.Valid, is Inspection.Invalid -> Next.ASK
        is Inspection.Unreadable -> Next.PROBLEM
    }
}

/** #74: the picked folder couldn't be read. Nothing was written; pick it again or another one. */
@Composable
fun FolderProblemDialog(problem: Inspection.Unreadable, onChooseAgain: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.folder_unreadable_title)) },
    text = { Text(stringResource(R.string.folder_unreadable_body, problem.reason)) },
    confirmButton = { TextButton(onChooseAgain) { Text(stringResource(R.string.choose_folder)) } },
    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
)
