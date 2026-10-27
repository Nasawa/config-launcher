package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.TitleBarEdits
import dev.nasawa.configlauncher.model.TitleBarEdits.Choice
import dev.nasawa.configlauncher.model.TitleBarModel.Slot

/**
 * #79 phone controls (ux.md §15.2): the on/off switch and, when on, a picker per slot with `Page title`, `Clock`,
 * `Date` and `None`. A slot holding anything else shows a selected, read-only `Custom (set in the file)` row with the
 * raw template, and `Picking another option replaces it.` Each change is one write.
 */
/** §15.2: what the user sees is Left / Center / Right, mirrored in RTL (start is on the right there). The file keeps start/end. */
fun slotLabel(slot: Slot, rtl: Boolean): Int = when (slot) {
    Slot.CENTER -> R.string.titlebar_slot_center
    Slot.START -> if (rtl) R.string.titlebar_slot_right else R.string.titlebar_slot_left
    Slot.END -> if (rtl) R.string.titlebar_slot_left else R.string.titlebar_slot_right
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBarSheet(state: TitleBarEdits.State, onToggle: (Boolean) -> Unit, onPick: (Slot, Choice) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.titlebar_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val present = state as? TitleBarEdits.State.Present
            val on = present?.on == true
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(on, role = Role.Switch, onValueChange = onToggle).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.titlebar_show), Modifier.weight(1f))
                Switch(on, onCheckedChange = null)
            }
            if (present != null && on) for (slot in Slot.entries) {
                val s = present.slots.getValue(slot)
                Text(stringResource(slotLabel(slot, rtl)),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
                Column(Modifier.selectableGroup()) {
                    if (s.choice == Choice.CUSTOM) {
                        // Read-only but not disabled (§15.2): TalkBack reads "Custom (set in the file), <template>, selected".
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(true, role = Role.RadioButton, onClick = {}).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(true, onClick = null)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(stringResource(R.string.titlebar_custom))
                                Text(s.raw.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.titlebar_custom_note), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    for (c in listOf(Choice.PAGE, Choice.CLOCK, Choice.DATE, Choice.NONE)) {
                        val selected = s.choice == c
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = { if (!selected) onPick(slot, c) }).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null)
                            Text(stringResource(when (c) { Choice.PAGE -> R.string.titlebar_page; Choice.CLOCK -> R.string.titlebar_clock; Choice.DATE -> R.string.titlebar_date; else -> R.string.titlebar_none }),
                                Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }
}
