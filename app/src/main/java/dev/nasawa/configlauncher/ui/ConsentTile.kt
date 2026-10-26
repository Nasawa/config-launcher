package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
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
internal fun reasonText(r: ConsentUi.Reason) = stringResource(when (r) {
    ConsentUi.Reason.NeedsOk -> R.string.tile_needs_ok
    ConsentUi.Reason.NeedsOkAgain -> R.string.tile_needs_ok_again
    ConsentUi.Reason.Off -> R.string.tile_off
    ConsentUi.Reason.Update -> R.string.tile_update
})

/** 24 dp shield outline (§17.3); no icon library in the app, so it's drawn. */
@Composable
internal fun Shield(color: Color, iconSize: androidx.compose.ui.unit.Dp = 24.dp, shadow: Boolean = false) = Canvas(Modifier.size(iconSize)) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.85f, h * 0.22f); lineTo(w * 0.85f, h * 0.48f)
        cubicTo(w * 0.85f, h * 0.72f, w * 0.68f, h * 0.86f, w * 0.5f, h * 0.94f)
        cubicTo(w * 0.32f, h * 0.86f, w * 0.15f, h * 0.72f, w * 0.15f, h * 0.48f)
        lineTo(w * 0.15f, h * 0.22f); close()
    }
    // Over the wallpaper it gets the home-label shadow (0x80 black, 1 px down), so it reads on a light wallpaper too.
    if (shadow) translate(0f, 1f) { drawPath(p, LabelShadow.color, style = Stroke(width = 2.dp.toPx())) }
    drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
}

/**
 * #142 consent tile (ux.md §17.3): in a non-live widget's cells, never its content. 12 dp frame, white 12 % fill,
 * 1 dp dashed white 40 % outline; shield, title (not at 1×1 or 2×1), reason (not at 1×1). One TalkBack node.
 */
@Composable
fun ConsentTile(tile: ConsentUi.Tile, onReview: () -> Unit, modifier: Modifier = Modifier, onLongPress: () -> Unit = {}) {
    val reason = reasonText(tile.reason)
    // title is optional (#160): an untitled card is named by its id, on screen and in TalkBack.
    val title = consentText(tile.title) ?: tile.id
    val lower = reason.replaceFirstChar { it.lowercase() }
    val said = stringResource(R.string.tile_a11y, title ?: "", lower)
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
            .combinedClickable(onClickLabel = review, onClick = onReview, onLongClick = onLongPress)
            .clearAndSetSemantics {
                contentDescription = said
                onClick(label = review) { onReview(); true }
                customActions = listOf(CustomAccessibilityAction(review) { onReview(); true })
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val p = tile.placement
        // #158 (Pragma): security UI must read on any wallpaper: home-label shadow, plus the 60 % scrim with Label backdrop on.
        val shadowed = androidx.compose.ui.text.TextStyle(shadow = LabelShadow.shadow)
        Column(LabelBackdrop.modifier(LocalLabelBackdrop.current), horizontalAlignment = Alignment.CenterHorizontally) {
            Shield(Color.White, shadow = true)
            if (title != null && !(p.h == 1 && p.w <= 2)) Text(title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = shadowed)
            if (!(p.w == 1 && p.h == 1)) Text(reason, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = shadowed)
        }
    }
}

/**
 * A live card's cells (#142 S3-C.2). The #140 renderer draws its content here; until then it's an empty target that
 * keeps the cells from opening the home menu, with the long-press sheet and the `Permissions` TalkBack action (§17.7).
 * No tap action, so TalkBack doesn't offer a "double-tap to activate" that does nothing (Grace #164 review).
 */
@Composable
fun LiveCardTarget(live: ConsentUi.Live, onLongPress: () -> Unit, onPermissions: () -> Unit, modifier: Modifier = Modifier) {
    val name = consentText(live.title) ?: live.id
    val said = stringResource(R.string.card_a11y, name)
    val permissions = stringResource(R.string.widget_action_permissions)
    Box(modifier.fillMaxSize().pointerInput(onLongPress) { detectTapGestures(onLongPress = { onLongPress() }) }.clearAndSetSemantics {
        contentDescription = said
        onLongClick { onLongPress(); true }
        customActions = listOf(CustomAccessibilityAction(permissions) { onPermissions(); true })
    })
}

/**
 * The widget long-press sheet (§17.3): name, `Widget · from launcher.json`, then `Review` or `Permissions`, `Edit layout`.
 * [reason] is shown when the tile is too small to draw it (1×1): §17.3 "title and reason … on long-press".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetItemSheet(name: String, live: Boolean, reason: ConsentUi.Reason? = null, onAction: (ConsentUi.SheetAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.widget_sheet_source_file), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            reason?.let { Text(reasonText(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(12.dp))
            ConsentUi.sheetActions(live).forEach { a ->
                TextButton({ onAction(a) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(when (a) {
                        ConsentUi.SheetAction.REVIEW -> R.string.widget_action_review
                        ConsentUi.SheetAction.PERMISSIONS -> R.string.widget_action_permissions
                        ConsentUi.SheetAction.EDIT_LAYOUT -> R.string.action_edit_layout
                    }), Modifier.fillMaxWidth())
                }
            }
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
/** The home-label text shadow (PageList, ux.md §12): 50 % black, 1 px down. */
object LabelShadow {
    val color = Color(0x80000000)
    val shadow = androidx.compose.ui.graphics.Shadow(color, androidx.compose.ui.geometry.Offset(0f, 1f), 1f)
}

/** MotionEvent flags that mean another app's window covered the touch (tap-jacking guard). */
object Obscured {
    const val MASK = android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED or android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    fun of(flags: Int) = flags and MASK != 0
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ConsentSheet(prompt: ConsentUi.Prompt, appLabel: (String) -> String, onAllow: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val titleFocus = remember { FocusRequester() }
    val title = consentText(prompt.title) ?: prompt.id
    val heading = stringResource(if (prompt.broadened) R.string.consent_title_more else R.string.consent_title, title)
    ModalBottomSheet(onDismiss, sheetState = sheet, modifier = Modifier.semantics { paneTitle = heading }) {
        // #158 review: no approving through an overlay. The sheet's window drops touches while another window covers
        // it, and Allow also ignores a press flagged as partly obscured (Android 10/11 don't block pass-through touches).
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view) {
            val was = view.filterTouchesWhenObscured
            view.filterTouchesWhenObscured = true
            onDispose { view.filterTouchesWhenObscured = was }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            // §17.7: focus starts on the title (focusable so the request lands), never on Allow.
            Text(heading, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() })
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
                var obscured by remember { androidx.compose.runtime.mutableStateOf(false) }
                Button({ if (!obscured) onAllow() }, Modifier.pointerInteropFilter { ev ->
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) obscured = Obscured.of(ev.flags)
                    false
                }) { Text(stringResource(R.string.consent_allow)) }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { titleFocus.requestFocus() } }
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
                    // Q-C3 / §17.5: the http note sits directly under the source line, with ▲ so it isn't colour-only;
                    // TalkBack reads it without the glyph (#158, Pragma).
                    if (l.http) {
                        val note = stringResource(R.string.cap_http_note_link, l.arg ?: "")
                        Text("▲ $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp).clearAndSetSemantics { contentDescription = note })
                    }
                    Text(stringResource(R.string.cap_open_others), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
internal fun lineText(l: Line, appLabel: (String) -> String): String = when (l.kind) {
    Line.Kind.ShowFile -> stringResource(R.string.cap_show_file, l.arg ?: "")
    Line.Kind.Reload -> stringResource(R.string.cap_reload)
    Line.Kind.OpenApp -> stringResource(R.string.cap_open_app, appLabel(l.arg ?: ""))
    Line.Kind.OpenSource -> stringResource(R.string.cap_open_source, l.arg ?: "")
    Line.Kind.OpenAsk -> stringResource(R.string.cap_open_ask)
}
