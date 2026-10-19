package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.Card
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #142 (S3-C): what the grid and the prompt sheet show for a card that isn't live (ux.md §17.3, §17.5). Pure Kotlin;
 * the copy itself is in strings.xml (§17.13), chosen by [Reason] and [Line.Kind].
 */
object ConsentUi {
    enum class Reason { NeedsOk, NeedsOkAgain, Off, Update }

    /** A consent tile in a card's cells. [title] is null when the card has none. [status] is null for [Reason.Update]. */
    data class Tile(val id: String?, val placement: Elements.Placement, val title: String?, val reason: Reason, val status: Consent.Status?)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Tiles for [pageId]: every shown card that is waiting or turned off, and every grid-placed element this launcher
     * skips (newer `requires`, unknown type or capability kind: cells kept, `Update Config Launcher to show this`).
     * Allowed cards get no tile; they're drawn by the card renderer (#140).
     */
    fun tiles(c: LauncherConfig, report: List<Consent.Status>, pageId: String): List<Tile> {
        val byId = report.associateBy { it.id }
        return c.elements.orEmpty().mapNotNull { e ->
            val p = Elements.placement(e)?.takeIf { it.page == pageId } ?: return@mapNotNull null
            val title = e.str("title")?.takeIf { it.isNotBlank() }
            if (Elements.skip(e) != null) return@mapNotNull Tile(e.str("id"), p, title, Reason.Update, null)
            if (Elements.typeOf(e) != Card) return@mapNotNull null
            val st = byId[e.str("id")] ?: return@mapNotNull null
            val r = when (st.state) {
                Consent.State.NeedsApproval -> Reason.NeedsOk
                Consent.State.NeedsApprovalAgain -> Reason.NeedsOkAgain
                Consent.State.Off -> Reason.Off
                Consent.State.Allowed -> return@mapNotNull null
            }
            Tile(st.id, p, title, r, st)
        }
    }

    /** A live (allowed) card's cells: a long-press / TalkBack target until the #140 renderer draws its content there. */
    data class Live(val id: String, val placement: Elements.Placement, val title: String?)

    fun live(c: LauncherConfig, report: List<Consent.Status>, pageId: String): List<Live> {
        val allowed = report.filter { it.state == Consent.State.Allowed }.map { it.id }.toSet()
        return c.elements.orEmpty().mapNotNull { e ->
            val id = e.str("id")?.takeIf { it in allowed && Elements.typeOf(e) == Card && Elements.skip(e) == null } ?: return@mapNotNull null
            val p = Elements.placement(e)?.takeIf { it.page == pageId } ?: return@mapNotNull null
            Live(id, p, e.str("title")?.takeIf { it.isNotBlank() })
        }
    }

    /** Long-press sheet for a widget (§17.3): `Review` on a consent tile, `Permissions` on a live card, then `Edit layout`. */
    enum class SheetAction { REVIEW, PERMISSIONS, EDIT_LAYOUT }

    fun sheetActions(live: Boolean): List<SheetAction> =
        listOf(if (live) SheetAction.PERMISSIONS else SheetAction.REVIEW, SheetAction.EDIT_LAYOUT)

    /** Widgets the badge counts (§17.4): waiting for OK, not turned off. */
    fun count(report: List<Consent.Status>): Int =
        report.count { it.state == Consent.State.NeedsApproval || it.state == Consent.State.NeedsApprovalAgain }

    /** One capability line in the prompt (§17.1 shapes). [arg] is the file, app package or `source` as shown. */
    data class Line(val kind: Kind, val arg: String? = null, val isNew: Boolean = false, val http: Boolean = false) {
        enum class Kind { ShowFile, Reload, OpenApp, OpenSource, OpenAsk }
    }

    /** The prompt for one card: broadened or first-time, and its lines in tier order (Show, then Open). */
    data class Prompt(val id: String, val title: String?, val author: String?, val broadened: Boolean, val seen: Long?, val lines: List<Line>) {
        val newLines get() = lines.filter { it.isNew }
        val already get() = lines.filterNot { it.isNew }
    }

    private fun tier(k: Line.Kind) = k.ordinal

    /**
     * The prompt as the user opened it, with the fingerprint it was built from. `Allow` approves exactly [lines]
     * (ConsentStore.allow refuses if the card changed), never whatever the file says when the button is tapped.
     */
    data class Opened(val prompt: Prompt, val lines: List<String>)

    fun open(c: LauncherConfig, report: List<Consent.Status>, entry: (String) -> Consent.Entry?, id: String): Opened? {
        val st = report.firstOrNull { it.id == id }?.takeIf { it.state != Consent.State.Allowed } ?: return null
        val card = c.elements.orEmpty().firstOrNull { it.str("id") == id && Elements.typeOf(it) == Card } ?: return null
        return Opened(prompt(card, st, entry(id)), st.lines)
    }

    /** The open sheet still shows this card as it is: same fingerprint and still not live. Otherwise it closes. */
    fun current(o: Opened, report: List<Consent.Status>): Boolean =
        report.firstOrNull { it.id == o.prompt.id }?.let { it.lines == o.lines && it.state != Consent.State.Allowed } == true

    fun prompt(card: JsonObject, st: Consent.Status, entry: Consent.Entry?): Prompt {
        val ceiling = entry?.ceiling.orEmpty()
        // Broadened whenever something is missing from a remembered approval: NeedsApprovalAgain, or a turned-off card
        // whose file has since asked for more. After Forget the ceiling is empty, so it's first-time again.
        val broadened = st.missing.isNotEmpty() && ceiling.isNotEmpty()
        return Prompt(st.id, card.str("title")?.takeIf { it.isNotBlank() }, card.str("author")?.takeIf { it.isNotBlank() },
            broadened, entry?.seen, shown(card, st) { broadened && it !in ceiling })
    }

    /** A host as the copy writes it (§17.1): bare for https, in full for http. */
    fun host(origin: String): String = if (origin.startsWith("http://")) origin else origin.removePrefix("https://")

    /** One ceiling line as a prompt/detail/history line; null for a kind stage 3 doesn't have. */
    fun line(id: String, l: String, isNew: Boolean = false): Line? = when {
        l == "data.file" -> Line(Line.Kind.ShowFile, "widgets-data/$id.json", isNew)
        l == "ui.reload" -> Line(Line.Kind.Reload, null, isNew)
        l.startsWith("ui.openApp ") -> Line(Line.Kind.OpenApp, l.removePrefix("ui.openApp "), isNew)
        l.startsWith("ui.openUrl ") -> l.removePrefix("ui.openUrl ").let { o -> Line(Line.Kind.OpenSource, host(o), isNew, o.startsWith("http://")) }
        else -> null  // stage 3 has no other kinds (an unknown kind skips the card before it gets here)
    }

    /** Everything the card can do, in tier order, plus the bare `ui.openUrl` line that has no ceiling entry (A3). */
    private fun shown(card: JsonObject, st: Consent.Status, isNew: (String) -> Boolean = { false }): List<Line> {
        val lines = st.lines.mapNotNull { line(st.id, it, isNew(it)) }.toMutableList()
        val caps = (card["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("kind") }
        if ("ui.openUrl" in caps && card.str("source") == null) lines += Line(Line.Kind.OpenAsk)
        return lines.sortedBy { tier(it.kind) }
    }

    // ---- #142 S3-C.2: review list and widget detail (ux.md §17.4, §17.6) ----

    /** Tier nouns for `New: …` (`review_noun_*`); `internet` comes with `net.fetch` (S5). */
    enum class Noun { Data, Apps, Links }
    /** Allowed-row capability chips (`chip_*`); `Internet` comes with `net.fetch` (S5). */
    enum class Chip { Show, Open }

    private fun noun(l: Line.Kind) = when (l) { Line.Kind.ShowFile, Line.Kind.Reload -> Noun.Data; Line.Kind.OpenApp -> Noun.Apps; else -> Noun.Links }
    private fun chip(l: Line.Kind) = if (l == Line.Kind.ShowFile || l == Line.Kind.Reload) Chip.Show else Chip.Open

    /** Where a card sits: 1-based page number and its span, for `Page 2 · 4 × 3`. */
    data class Where(val page: Int, val w: Int, val h: Int)

    data class Row(val id: String, val title: String, val where: Where?, val state: Consent.State, val author: String?,
                   val changed: Long?, val news: List<Noun>, val chips: List<Chip>)

    data class Review(val needs: List<Row>, val allowed: List<Row>, val off: List<Row>) {
        val empty get() = needs.isEmpty() && allowed.isEmpty() && off.isEmpty()
    }

    private fun card(c: LauncherConfig, id: String) = c.elements.orEmpty().firstOrNull { it.str("id") == id && Elements.typeOf(it) == Card }

    private fun where(c: LauncherConfig, card: JsonObject): Where? = Elements.placement(card)?.let { p ->
        val i = c.pages.indexOfFirst { it.id == p.page }
        if (i < 0) null else Where(i + 1, p.w, p.h)
    }

    /** The `Widget permissions` list: one row per card in the file, in file order, by section (no `Allow all`, Q-D2). */
    fun review(c: LauncherConfig, report: List<Consent.Status>, entries: Map<String, Consent.Entry>): Review {
        val rows = report.mapNotNull { st ->
            val card = card(c, st.id) ?: return@mapNotNull null
            val e = entries[st.id]
            val lines = shown(card, st)
            Row(st.id, st.title, where(c, card), st.state, card.str("author")?.takeIf { it.isNotBlank() }, e?.changed ?: e?.seen,
                st.missing.mapNotNull { line(st.id, it)?.kind?.let(::noun) }.distinct().sorted(),
                lines.map { chip(it.kind) }.distinct().sorted())
        }
        return Review(rows.filter { it.state == Consent.State.NeedsApproval || it.state == Consent.State.NeedsApprovalAgain },
            rows.filter { it.state == Consent.State.Allowed }, rows.filter { it.state == Consent.State.Off })
    }

    data class Detail(val id: String, val title: String, val author: String?, val where: Where?, val changed: Long?,
                      val state: Consent.State,
                      /** `This widget can:` with whether each line is within the approval (`Allowed`). */
                      val lines: List<Pair<Line, Boolean>>,
                      /** `Approved on this phone:` the full ceiling, flagged `not used now` when the card no longer declares it. */
                      val approved: List<Pair<Line, Boolean>>,
                      /** `Always allowed sites`: canonical origins (shown with [host]). */
                      val always: List<String>,
                      val history: List<Consent.Event>,
                      /** Nothing missing, so `Allow` turns it back on without a prompt. */
                      val allowNeedsNoPrompt: Boolean) {
        /** `Forget approval` shows whenever this phone holds an approval for the widget. */
        val canForget get() = approved.isNotEmpty() || always.isNotEmpty()
    }

    fun detail(c: LauncherConfig, st: Consent.Status, e: Consent.Entry?): Detail? {
        val card = card(c, st.id) ?: return null
        val ceiling = e?.ceiling.orEmpty()
        val allowedNow = st.state == Consent.State.Allowed
        val lines = shown(card, st).map { l -> l to (allowedNow || l.kind == Line.Kind.OpenAsk || !Consent.needsOk(raw(l)) || raw(l) in ceiling) }
        val approved = ceiling.sorted().mapNotNull { r -> line(st.id, r)?.let { it to (r !in st.lines) } }.sortedBy { tier(it.first.kind) }
        return Detail(st.id, st.title, card.str("author")?.takeIf { it.isNotBlank() }, where(c, card), e?.changed ?: e?.seen, st.state,
            lines, approved, e?.alwaysAllow.orEmpty().sorted(), e?.history.orEmpty().reversed(), st.missing.isEmpty())
    }

    /** The ceiling line a shown line came from (for the open-source line, its canonical origin). */
    private fun raw(l: Line): String = when (l.kind) {
        Line.Kind.ShowFile -> "data.file"
        Line.Kind.Reload -> "ui.reload"
        Line.Kind.OpenApp -> "ui.openApp ${l.arg}"
        Line.Kind.OpenSource -> "ui.openUrl ${Consent.canonicalSource(l.arg!!)}"
        Line.Kind.OpenAsk -> "ui.openUrl"
    }

    /**
     * B9 (§17.4): shown when the count went up past what the user last dismissed (`×` or `Review`), and never above a
     * B1–B8 banner. [dismissedAt] follows the count down, so the next rise shows it again.
     */
    object B9 {
        fun visible(count: Int, dismissedAt: Int, otherBanner: Boolean) = !otherBanner && count > 0 && count > dismissedAt
        fun follow(count: Int, dismissedAt: Int) = minOf(count, dismissedAt)
    }
}
