package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R

/** #79 / ux.md §15 "Where warnings are found": load warnings for a valid file (skipped elements, contrast, …). */
object WarningsText {
    /** How long Copy shows "Copied" ✓ (§15). */
    const val COPIED_MS = 2_000L

    /** Plain text for the clipboard: file name, then one `▲` line per warning (same layout as problems). */
    fun copy(fileName: String, header: String, warnings: List<String>): String =
        (listOf(fileName, header) + warnings.map { "▲ $it" }).joinToString("\n")
}

/** The Warnings section: header `1 warning` / `3 warnings`, each warning with its path, ▲ instead of ■. */
@Composable
fun WarningsDialog(warnings: List<String>, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val header = pluralStringResource(R.plurals.warnings_header, warnings.size, warnings.size)
    var copiedAt by remember { mutableStateOf<Long?>(null) }
    val announce = stringResource(R.string.warnings_copied)
    LaunchedEffect(copiedAt) { if (copiedAt != null) { delay(WarningsText.COPIED_MS); copiedAt = null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(header) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                warnings.forEach { Text("▲ $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        },
        confirmButton = {
            // §15 (Pragma, #134): Copy turns into "Copied" with a check for ~2 s, then back; TalkBack announces
            // "Warnings copied." (polite live region). The list stays open.
            TextButton({ onCopy(WarningsText.copy("launcher.json", header, warnings)); copiedAt = System.nanoTime() },
                // Live region only while it says Copied, so going back to Copy isn't announced.
                Modifier.semantics { if (copiedAt != null) { liveRegion = LiveRegionMode.Polite; contentDescription = announce } }) {
                if (copiedAt != null) {
                    Text("✓")
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copied))
                } else Text(stringResource(R.string.copy))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
