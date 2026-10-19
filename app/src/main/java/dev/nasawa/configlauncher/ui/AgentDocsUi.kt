package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R

/** ux.md §7.1: `Agent instructions updated.` after an untouched AGENTS.md was replaced. */
@Composable
fun AgentDocsSnackbar(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(4_000); onDismiss() }
    Snackbar(Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }) { Text(stringResource(R.string.agent_docs_updated)) }
}

/** ux.md §7.1: the user edited AGENTS.md; Replace backs it up to `.configlauncher/backup/` first. */
@Composable
fun AgentDocsReplaceDialog(onReplace: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(stringResource(R.string.agent_docs_replace_title)) },
    text = { Text(stringResource(R.string.agent_docs_replace_body)) },
    confirmButton = { TextButton(onReplace) { Text(stringResource(R.string.agent_docs_replace)) } },
    dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.cancel)) } },
)
