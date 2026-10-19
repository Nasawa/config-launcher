package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R

/** #79 / ux.md §15 "Where warnings are found": load warnings for a valid file (skipped elements, contrast, …). */
object WarningsText {
    /** Plain text for the clipboard: file name, then one `▲` line per warning (same layout as problems). */
    fun copy(fileName: String, header: String, warnings: List<String>): String =
        (listOf(fileName, header) + warnings.map { "▲ $it" }).joinToString("\n")
}

/** ux.md §15: after Copy, the snackbar `Warnings copied.` (polite live region, 4 s). */
@Composable
fun WarningsCopiedSnackbar(onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(4_000); onDismiss() }
    androidx.compose.material3.Snackbar(Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }) { Text(stringResource(R.string.warnings_copied)) }
}

/** The Warnings section: header `1 warning` / `3 warnings`, each warning with its path, ▲ instead of ■. */
@Composable
fun WarningsDialog(warnings: List<String>, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val header = pluralStringResource(R.plurals.warnings_header, warnings.size, warnings.size)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(header) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                warnings.forEach { Text("▲ $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        },
        confirmButton = { TextButton({ onCopy(WarningsText.copy("launcher.json", header, warnings)) }) { Text(stringResource(R.string.copy)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
