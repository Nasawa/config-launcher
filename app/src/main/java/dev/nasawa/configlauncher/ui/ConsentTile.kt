package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.consent.ConsentUi
import dev.nasawa.configlauncher.consent.ConsentUi.Line

/** Title or author for display: bidi and zero-width characters stripped (§13); null when nothing is left. */
fun consentText(s: String?): String? = s?.let { Labels.clean(it) }?.takeIf { it != "?" || s.trim() == "?" }

@Composable
private fun reasonText(r: ConsentUi.Reason) = stringResource(when (r) {
    ConsentUi.Reason.NeedsOk -> R.string.tile_needs_ok
    ConsentUi.Reason.NeedsOkAgain -> R.string.tile_needs_ok_again
    ConsentUi.Reason.Off -> R.string.tile_off
    ConsentUi.Reason.Update -> R.string.tile_update
})

/** 24 dp shield outline (§17.3); no icon library in the app, so it's drawn. */
@Composable
private fun Shield(color: Color) = Canvas(Modifier.size(24.dp)) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.85f, h * 0.22f); lineTo(w * 0.85f, h * 0.48f)
        cubicTo(w * 0.85f, h * 0.72f, w * 0.68f, h * 0.86f, w * 0.5f, h * 0.94f)
        cubicTo(w * 0.32f, h * 0.86f, w * 0.15f, h * 0.72f, w * 0.15f, h * 0.48f)
        lineTo(w * 0.15f, h * 0.22f); close()
    }
    drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
}

/**
 * #142 consent tile (ux.md §17.3): in a non-live widget's cells, never its content. 12 dp frame, white 12 % fill,
 * 1 dp dashed white 40 % outline; shield, title (not at 1×1 or 2×1), reason (not at 1×1). One TalkBack node.
 */
@Composable
fun ConsentTile(tile: ConsentUi.Tile, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val reason = reasonText(tile.reason)
    val title = consentText(tile.title)
    val lower = reason.replaceFirstChar { it.lowercase() }
    val said = if (title != null) stringResource(R.string.tile_a11y, title, lower) else stringResource(R.string.tile_a11y_untitled, lower)
    val review = stringResource(R.string.widget_action_review)
    val outline = Color(0x66FFFFFF)
    Box(
        modifier.fillMaxSize().padding(2.dp)
            .background(Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
            .drawBehind {
                val r = 12.dp.toPx()
                drawRoundRect(outline, cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .clickable(onClickLabel = review, onClick = onReview)
            .clearAndSetSemantics {
                contentDescription = said
                role = Role.Button
                onClick(label = review) { onReview(); true }
                customActions = listOf(CustomAccessibilityAction(review) { onReview(); true })
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val p = tile.placement
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(Color.White)
            if (title != null && !(p.h == 1 && p.w <= 2)) Text(title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (!(p.w == 1 && p.h == 1)) Text(reason, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** `Update Config Launcher to show this` → hint sheet (§17.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateHintSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss) {
        Text(stringResource(R.string.tile_update_hint), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp))
    }
}

/**
 * #142 prompt sheet (ux.md §17.5): a bottom sheet opened only from a tap. Title, source, author quote only when
 * present, the capability lines in tier order (broadened: `New` then `Already allowed`), footer, `Keep off` · `Allow`.
 * Focus starts on the title, not on `Allow`. Back or swipe-down decides nothing. The preview of the layout with `—`
 * comes with the card renderer (#140).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(prompt: ConsentUi.Prompt, appLabel: (String) -> String, onAllow: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val titleFocus = remember { FocusRequester() }
    ModalBottomSheet(onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            val title = consentText(prompt.title) ?: prompt.id
            Text(stringResource(if (prompt.broadened) R.string.consent_title_more else R.string.consent_title, title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.focusRequester(titleFocus).semantics { heading(); paneTitle = title })
            prompt.seen?.let { t ->
                val time = android.text.format.DateUtils.formatSameDayTime(t, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString()
                Text(stringResource(R.string.consent_source_file, time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            consentText(prompt.author)?.let { Text(stringResource(R.string.consent_author, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            if (prompt.broadened) {
                LineList(stringResource(R.string.consent_new_header), prompt.newLines, appLabel, isNew = true)
                if (prompt.already.isNotEmpty()) { Spacer(Modifier.height(12.dp)); LineList(stringResource(R.string.consent_already), prompt.already, appLabel, isNew = false) }
            } else LineList(stringResource(R.string.consent_can), prompt.lines, appLabel, isNew = false)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.consent_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text(stringResource(R.string.consent_keep_off)) }
                Spacer(Modifier.width(8.dp))
                Button(onAllow) { Text(stringResource(R.string.consent_allow)) }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
}

@Composable
private fun LineList(header: String, lines: List<Line>, appLabel: (String) -> String, isNew: Boolean) {
    Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    // §17.7: the lines are one list ("List, 3 items").
    Column(Modifier.semantics { collectionInfo = CollectionInfo(lines.size, 1) }) {
        lines.forEachIndexed { i, l ->
            val text = lineText(l, appLabel)
            val shown = if (isNew) stringResource(R.string.consent_new_prefix, text) else text
            val spoken = if (isNew) stringResource(R.string.consent_new_a11y, text) else text
            Column(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {
                collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
            }) {
                Text("· $shown", modifier = Modifier.clearAndSetSemantics { contentDescription = spoken })
                if (l.kind == Line.Kind.OpenSource) {
                    Text(stringResource(R.string.cap_open_others), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                    // Q-C3: the http note sits directly under the line, with ▲ so it isn't colour-only.
                    if (l.http) Text("▲ " + stringResource(R.string.cap_http_note_link, l.arg ?: ""), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun lineText(l: Line, appLabel: (String) -> String): String = when (l.kind) {
    Line.Kind.ShowFile -> stringResource(R.string.cap_show_file, l.arg ?: "")
    Line.Kind.Reload -> stringResource(R.string.cap_reload)
    Line.Kind.OpenApp -> stringResource(R.string.cap_open_app, appLabel(l.arg ?: ""))
    Line.Kind.OpenSource -> stringResource(R.string.cap_open_source, l.arg ?: "")
    Line.Kind.OpenAsk -> stringResource(R.string.cap_open_ask)
}
