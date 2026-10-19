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

    fun prompt(card: JsonObject, st: Consent.Status, entry: Consent.Entry?): Prompt {
        val ceiling = entry?.ceiling.orEmpty()
        // Broadened only when something was approved before and is still remembered; after Forget it's first-time again.
        val broadened = st.state == Consent.State.NeedsApprovalAgain
        val lines = st.lines.mapNotNull { l ->
            val isNew = broadened && l !in ceiling
            when {
                l == "data.file" -> Line(Line.Kind.ShowFile, "widgets-data/${st.id}.json", isNew)
                l == "ui.reload" -> Line(Line.Kind.Reload, null, isNew)
                l.startsWith("ui.openApp ") -> Line(Line.Kind.OpenApp, l.removePrefix("ui.openApp "), isNew)
                l.startsWith("ui.openUrl ") -> l.removePrefix("ui.openUrl ").let { o ->
                    val http = o.startsWith("http://")
                    Line(Line.Kind.OpenSource, if (http) o else o.removePrefix("https://"), isNew, http)
                }
                else -> null  // stage 3 has no other kinds (an unknown kind skips the card before it gets here)
            }
        }.toMutableList()
        // Bare ui.openUrl without source adds no ceiling line, but the prompt still lists everything the card can do (A3).
        val caps = (card["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("kind") }
        if ("ui.openUrl" in caps && card.str("source") == null) lines += Line(Line.Kind.OpenAsk)
        return Prompt(st.id, card.str("title")?.takeIf { it.isNotBlank() }, card.str("author")?.takeIf { it.isNotBlank() },
            broadened, entry?.seen, lines.sortedBy { tier(it.kind) })
    }
}
