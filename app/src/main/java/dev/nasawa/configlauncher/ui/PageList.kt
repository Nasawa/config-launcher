package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.ResolvedPage

/** #78: one row per page, in file order. [title] null = untitled ("Page N"). */
data class PageRow(val number: Int, val title: String?, val current: Boolean)

object PageList {
    fun rows(pages: List<ResolvedPage>, current: Int): List<PageRow> =
        pages.mapIndexed { i, p -> PageRow(i + 1, p.title?.takeIf { it.isNotBlank() }?.let(Labels::clean), i == current) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListSheet(rows: List<PageRow>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss) {
        Text(stringResource(R.string.pages_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val currentLabel = stringResource(R.string.page_current)
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, r ->
                val name = r.title ?: stringResource(R.string.page_untitled, r.number)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(i) }
                        .semantics { selected = r.current; if (r.current) stateDescription = currentLabel }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${r.number}", color = Color.Gray, modifier = Modifier.width(32.dp))
                    Text(name, Modifier.weight(1f), style = if (r.current) MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary) else MaterialTheme.typography.bodyLarge)
                    if (r.current) Text("●", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
