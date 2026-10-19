package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.Card
import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #138 (S3-B): the per-widget approval model, pure Kotlin (dynamic-widgets.md §4 at #146 3237674; ux.md §17.2).
 *
 * A card's capabilities become **lines**, one `(kind, target)` each: `data.file`, `ui.reload`, `ui.openApp <package>`
 * and, only when the card has a `source`, `ui.openUrl <scheme://host>`. The sorted lines are the card's fingerprint.
 * Layout, text, style, span and `author` aren't in it, so editing them never changes the consent state.
 *
 * Only lines in [needsOk] can make a card wait. Show-tier `data.file` and `ui.reload` and Open-tier `ui.openApp` are
 * silent (Q-D1, Pragma's Open-tier split). Bare `ui.openUrl` without `source` adds no line at all: every tap confirms
 * anyway (Q-D5 follow-up), so removing `source` never re-prompts.
 */
object Consent {
    enum class State(val key: String) { NeedsApproval("needsApproval"), NeedsApprovalAgain("needsApprovalAgain"), Off("off"), Allowed("allowed") }

    /** One card's consent on this phone: its state, the declared lines and the ones still outside the ceiling. */
    data class Status(val id: String, val path: String, val title: String, val state: State, val lines: List<String>, val missing: List<String>) {
        /** The status.json sentence (Pragma §17.13, English in code), or null when the card is live. */
        val warning: String? get() = when (state) {
            State.NeedsApproval -> "$path: widget \"$title\" is waiting for approval on this phone. It isn't shown until then."
            State.NeedsApprovalAgain -> "$path: widget \"$title\" asks for more access and is waiting for approval on this phone. It isn't shown until then."
            State.Off -> "$path: widget \"$title\" is turned off on this phone."
            State.Allowed -> null
        }
    }

    /** What this phone remembers for one widget id. Never written to the synced folder. */
    data class Entry(val ceiling: Set<String> = emptySet(), val off: Boolean = false, val alwaysAllow: Set<String> = emptySet())

    /** `source` as scheme + host: a bare host means https (dynamic-widgets.md §4 "Normalising source"). */
    fun canonicalSource(s: String): String = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The card's lines, sorted and de-duplicated (its fingerprint). */
    fun lines(card: JsonObject): List<String> {
        val source = card.str("source")?.let(::canonicalSource)
        val out = sortedSetOf<String>()
        (card["capabilities"] as? JsonArray).orEmpty().forEach { c ->
            val o = c as? JsonObject ?: return@forEach
            when (val kind = o.str("kind")) {
                "ui.openApp" -> (o["packages"] as? JsonArray).orEmpty().forEach { p -> (p as? JsonPrimitive)?.content?.let { out += "ui.openApp $it" } }
                "ui.openUrl" -> if (source != null) out += "ui.openUrl $source"
                null -> {}
                else -> out += kind
            }
        }
        return out.toList()
    }

    /** Lines that need the user's OK before the card goes live. In stage 3 only `ui.openUrl` with a `source`. */
    fun needsOk(line: String): Boolean = line.startsWith("ui.openUrl ")

    /**
     * Phone data + Internet combination (ux.md §17.1): prompts once even when each part was approved alone. Stage 3
     * has no `phone.*` or `net.fetch` kinds, so there is never a combination line yet.
     */
    @Suppress("UNUSED_PARAMETER")
    fun comboLine(lines: List<String>): String? = null

    private fun required(lines: List<String>): List<String> = lines.filter(::needsOk) + listOfNotNull(comboLine(lines))

    /** The state of one card. Off wins; otherwise live iff every line that needs OK is within the ceiling. */
    fun state(lines: List<String>, e: Entry?): Pair<State, List<String>> {
        val missing = required(lines).filter { it !in e?.ceiling.orEmpty() }
        val s = when {
            e?.off == true -> State.Off
            missing.isEmpty() -> State.Allowed
            e?.ceiling.orEmpty().isEmpty() -> State.NeedsApproval
            else -> State.NeedsApprovalAgain
        }
        return s to missing
    }

    /** Cards this phone shows (skipped ones have no consent state), with their status.json path. */
    fun cards(c: LauncherConfig): List<Pair<String, JsonObject>> = c.elements.orEmpty().mapIndexedNotNull { i, e ->
        if (Elements.typeOf(e) == Card && e.str("id") != null) "elements[$i]" to e else null
    }

    fun report(c: LauncherConfig, entries: Map<String, Entry>): List<Status> = cards(c).map { (path, e) ->
        val id = e.str("id")!!
        val lines = lines(e)
        val (s, missing) = state(lines, entries[id])
        Status(id, path, e.str("title") ?: id, s, lines, missing)
    }
}
