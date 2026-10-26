package dev.nasawa.configlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.consent.Consent
import dev.nasawa.configlauncher.consent.ConsentUi

/** `10:42` today, a date otherwise (§17.13 time placeholders). */
fun consentTime(t: Long): String =
    android.text.format.DateUtils.formatSameDayTime(t, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString()

/** The locale's list format (`a, b and c`). */
private fun joinList(items: List<String>): String = android.icu.text.ListFormatter.getInstance().format(items)

@Composable
private fun secondary() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun whereText(w: ConsentUi.Where?) = w?.let { stringResource(R.string.review_row_where, it.page, it.w, it.h) }

/** `in launcher.json · 10:42`, plus ` · says "Claw"` only when `author` is present (Q-C4). */
@Composable
private fun withAuthor(source: String, author: String?) = consentText(author)?.let { stringResource(R.string.author_inline, source, it) } ?: source

// ---- B9, count chip (§17.4) ----

/** B9: lowest-priority notice, `Review` · `×` (collapses to the chip). Polite live region, announced once. */
@Composable
fun B9Banner(count: Int, onReview: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF263238), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Shield(Color.White, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(pluralStringResource(R.plurals.b9_widgets_review, count, count), color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onReview) { Text(stringResource(R.string.b9_review)) }
        val dismiss = stringResource(R.string.b9_dismiss_a11y)
        TextButton(onDismiss, Modifier.semantics { contentDescription = dismiss }) { Text("×", color = Color.White) }
    }
}

/**
 * Count chip: 20 dp pill at the top end of the banner slot, shield + number, tap → review list. It sits beside the
 * banners, not in a row of its own; the 48 dp touch target comes from Compose's minimum touch size around it.
 */
@Composable
fun ReviewCountChip(count: Int, onClick: () -> Unit) {
    val said = pluralStringResource(R.plurals.chip_review_a11y, count, count)
    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            Modifier.widthIn(min = 48.dp).clickable(onClick = onClick).clearAndSetSemantics {
                contentDescription = said; liveRegion = LiveRegionMode.Polite; onClick { onClick(); true }
            },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.height(20.dp).background(Color(0xCC263238), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Shield(Color.White, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text("$count", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---- Review list `Widget permissions` (§17.4) ----

@Composable
private fun FullScreen(title: String, onDismiss: () -> Unit, snack: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text("←") }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading(); paneTitle = title })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), content = content)
            }
            // Pinned to the bottom of the screen, outside the scroll, so it's on-screen at 320 dp, large font or a long page.
            Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { snack() }
            }
        }
    }
}

@Composable
private fun Section(header: String) {
    Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })
}

/** No `Allow all` (Q-D2): each widget gets its own decision. Needs-OK rows open the prompt, the others the detail. */
@Composable
fun ReviewScreen(review: ConsentUi.Review, onNeeds: (String) -> Unit, onOpen: (String) -> Unit, onDismiss: () -> Unit,
                 notice: String? = null) {
    FullScreen(stringResource(R.string.review_title), onDismiss, snack = { notice?.let { NoticeSnack(it) } }) {
        if (review.empty) Text(stringResource(R.string.review_empty), color = secondary(), modifier = Modifier.padding(top = 24.dp))
        if (review.needs.isNotEmpty()) {
            Section(stringResource(R.string.review_section_needs, review.needs.size))
            review.needs.forEach { r -> ReviewRow(r) { onNeeds(r.id) } }
        }
        if (review.allowed.isNotEmpty()) {
            Section(stringResource(R.string.review_section_allowed, review.allowed.size))
            review.allowed.forEach { r -> ReviewRow(r) { onOpen(r.id) } }
        }
        if (review.off.isNotEmpty()) {
            Section(stringResource(R.string.review_section_off, review.off.size))
            review.off.forEach { r -> ReviewRow(r) { onOpen(r.id) } }
        }
    }
}

@Composable
private fun ReviewRow(r: ConsentUi.Row, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        Text(consentText(r.title) ?: r.id, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        whereText(r.where)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary()) }
        when (r.state) {
            Consent.State.NeedsApproval, Consent.State.NeedsApprovalAgain -> {
                if (r.news.isNotEmpty()) Text(stringResource(R.string.review_row_new, joinList(r.news.map { nounText(it) })), style = MaterialTheme.typography.bodySmall)
                r.changed?.let { Text(withAuthor(stringResource(R.string.review_source_file, consentTime(it)), r.author), style = MaterialTheme.typography.bodySmall, color = secondary()) }
            }
            Consent.State.Allowed -> if (r.chips.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.chips.forEach { c ->
                    Text(stringResource(if (c == ConsentUi.Chip.Show) R.string.chip_show else R.string.chip_open), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            Consent.State.Off -> {}
        }
    }
}

@Composable
private fun nounText(n: ConsentUi.Noun) = stringResource(when (n) {
    ConsentUi.Noun.Data -> R.string.review_noun_data
    ConsentUi.Noun.Apps -> R.string.review_noun_apps
    ConsentUi.Noun.Links -> R.string.review_noun_links
})

// ---- Widget detail (§17.6) ----

/** A snackbar on the detail page: text, optional Undo, and how long it stays. */
private data class DetailSnack(val text: String, val undo: (() -> Unit)?, val ms: Long)

/**
 * Header, `This widget can:`, history, `Approved on this phone:`, `Always allowed sites`, then Turn off / Allow and
 * `Forget approval`. Nothing here writes launcher.json: consent is device-local. [onTurnOff] and [onRemoveSite]
 * return their Undo, or null when there's nothing to put back. [notice] is the home's changed-card snackbar.
 */
@Composable
fun DetailScreen(d: ConsentUi.Detail, appLabel: (String) -> String, onTurnOff: () -> (() -> Unit)?, onAllow: () -> Unit,
                 onForget: () -> Unit, onRemoveSite: (String) -> (() -> Unit)?, onDismiss: () -> Unit, notice: String? = null) {
    val title = consentText(d.title) ?: d.id
    val res = androidx.compose.ui.platform.LocalResources.current
    var snack by remember { mutableStateOf<DetailSnack?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    FullScreen(title, onDismiss, snack = {
        snack?.let { s ->
            LaunchedEffect(s) { kotlinx.coroutines.delay(s.ms); if (snack == s) snack = null }
            Snackbar(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                action = s.undo?.let { u -> { TextButton({ snack = null; u() }) { Text(stringResource(R.string.merge_undo)) } } },
            ) { Text(s.text) }
        } ?: notice?.let { NoticeSnack(it) }
    }) {
        whereText(d.where)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = secondary()) }
        d.changed?.let { Text(withAuthor(stringResource(R.string.detail_changed_file, consentTime(it)), d.author), style = MaterialTheme.typography.bodySmall, color = secondary()) }

        Section(stringResource(R.string.consent_can))
        d.lines.forEach { (l, allowed) ->
            Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
                Text("· " + lineText(l, appLabel))
                if (allowed) Text(stringResource(R.string.detail_status_allowed), style = MaterialTheme.typography.bodySmall, color = secondary(), modifier = Modifier.padding(start = 10.dp))
                if (l.http) {
                    val note = stringResource(R.string.cap_http_note_link, l.arg ?: "")
                    Text("▲ $note", style = MaterialTheme.typography.bodySmall, color = secondary(),
                        modifier = Modifier.padding(start = 10.dp).clearAndSetSemantics { contentDescription = note })
                }
            }
        }

        if (d.history.isNotEmpty()) {
            // Quiet: never a badge or dot.
            Section(stringResource(R.string.detail_history))
            d.history.forEach { e -> Text(stringResource(R.string.history_entry, consentTime(e.at), historyText(d.id, e, appLabel)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }

        if (d.approved.isNotEmpty()) {
            Section(stringResource(R.string.detail_approved))
            d.approved.forEach { (l, notUsed) ->
                Column(Modifier.padding(vertical = 2.dp).semantics(mergeDescendants = true) {}) {
                    Text("· " + lineText(l, appLabel), style = MaterialTheme.typography.bodySmall)
                    if (notUsed) Text(stringResource(R.string.detail_not_used), style = MaterialTheme.typography.bodySmall, color = secondary(), modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        if (d.always.isNotEmpty()) {
            Section(stringResource(R.string.detail_always_header))
            d.always.forEach { o ->
                val host = ConsentUi.host(o)
                val a11y = stringResource(R.string.detail_site_remove_a11y, host)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(host, modifier = Modifier.weight(1f))
                    TextButton({
                        val undo = onRemoveSite(o)
                        snack = DetailSnack(res.getString(R.string.site_removed_snack, host), undo, UndoSnack.DURATION_MS)
                    }, Modifier.semantics { contentDescription = a11y }) { Text(stringResource(R.string.detail_site_remove)) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        if (d.state == Consent.State.Off) Button(onAllow) { Text(stringResource(R.string.detail_allow)) }
        else if (d.state == Consent.State.Allowed) Button({
            val undo = onTurnOff()
            snack = DetailSnack(res.getString(R.string.turned_off_snack, title), undo, UndoSnack.DURATION_MS)
        }) { Text(stringResource(R.string.detail_turn_off)) }
        if (d.canForget) {
            val a11y = stringResource(R.string.detail_forget_a11y, title)
            TextButton({ confirmForget = true }, Modifier.semantics { contentDescription = a11y }) {
                Text(stringResource(R.string.detail_forget), color = MaterialTheme.colorScheme.error)
            }
        }
        // Room to scroll the last button clear of a snackbar pinned over the bottom.
        if (snack != null || notice != null) Spacer(Modifier.height(72.dp))
    }
    if (confirmForget) ForgetDialog(title, onForget = {
        confirmForget = false
        onForget()
        snack = DetailSnack(res.getString(R.string.forget_snack), null, SHORT_SNACK_MS)
    }, onCancel = { confirmForget = false })
}

/** The changed-card notice (consent_changed_snack) shown over a consent screen; HomeScreen times it out. */
@Composable
private fun NoticeSnack(text: String) = Snackbar(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { Text(text) }

/** 4 s snackbars with no Undo: `Approval forgotten.` (§17.6) and the changed-card notice. */
const val SHORT_SNACK_MS = 4_000L

@Composable
private fun historyText(id: String, e: Consent.Event, appLabel: (String) -> String): String = when (e.kind) {
    Consent.Event.Kind.Allowed -> stringResource(R.string.history_allowed)
    Consent.Event.Kind.TurnedOff -> stringResource(R.string.history_turned_off)
    Consent.Event.Kind.TurnedOn -> stringResource(R.string.history_turned_on)
    Consent.Event.Kind.Forgotten -> stringResource(R.string.history_forgotten)
    Consent.Event.Kind.Always -> stringResource(R.string.history_always, ConsentUi.host(e.arg ?: ""))
    Consent.Event.Kind.SiteRemoved -> stringResource(R.string.history_site_removed, ConsentUi.host(e.arg ?: ""))
    Consent.Event.Kind.NoLonger -> stringResource(R.string.history_no_longer, ConsentUi.line(id, e.arg ?: "")?.let { lineText(it, appLabel) } ?: (e.arg ?: ""))
    Consent.Event.Kind.Layout -> stringResource(R.string.history_layout)
}

/** Can't be undone, so it confirms; `Cancel` has initial focus, `Forget` is in the error colour. */
@Composable
fun ForgetDialog(title: String, onForget: () -> Unit, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.forget_title, title)) },
        text = { Text(stringResource(R.string.forget_body)) },
        dismissButton = { TextButton(onCancel, Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.forget_cancel)) } },
        confirmButton = { TextButton(onForget) { Text(stringResource(R.string.forget_confirm), color = MaterialTheme.colorScheme.error) } },
    )
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { cancelFocus.requestFocus() } }
}
