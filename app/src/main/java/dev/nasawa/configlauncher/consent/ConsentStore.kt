package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * #138 (S3-B): device-local approvals, in app-private storage (`filesDir/consent/consent.json`, temp + rename).
 * Never in the synced folder or status.json, so nothing that writes launcher.json can approve its own widget
 * (ux.md §17 A1, Q-D6).
 *
 * Per widget id it keeps the approval **ceiling** (a union of every line ever allowed; file edits never shrink it),
 * the sticky **off** switch and the Q-D5 **Always allow** sites (separate from the ceiling, never in the fingerprint).
 */
class ConsentStore(private val dir: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = dir?.let { File(it, "consent.json") }
    private var entries: MutableMap<String, Consent.Entry> = load()
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** #142: bumps on every change, so the UI recomputes tiles and prompts. */
    val version: kotlinx.coroutines.flow.StateFlow<Int> = _version

    @Synchronized fun entries(): Map<String, Consent.Entry> = entries.toMap()
    @Synchronized fun entry(id: String): Consent.Entry? = entries[id]

    @Synchronized fun report(c: LauncherConfig): List<Consent.Status> = Consent.report(c, entries)

    /**
     * After every successful load: drop approvals for ids that left the file (GC), and fold a live card's silent
     * lines into its ceiling, so the ceiling lists everything it may use ("Approved on this phone") and a later
     * broadening reads `Needs your OK again`. Writes only when something changed.
     */
    @Synchronized fun settle(c: LauncherConfig) {
        val cards = Consent.cards(c)
        // Every id still in the file, shown or skipped: a card skipped for now (e.g. a newer `requires`) keeps its approval.
        val ids = c.elements.orEmpty().mapNotNull { (it["id"] as? JsonPrimitive)?.content }.toSet()
        val next = entries.filterKeys { it in ids }.toMutableMap()
        cards.forEach { (_, e) ->
            val id = (e["id"] as JsonPrimitive).content
            val lines = Consent.lines(e)
            var cur = next[id] ?: Consent.Entry()
            val now by lazy { clock() }
            if (cur.seen == null) cur = cur.copy(seen = now)
            // #142: quiet history for a narrowed or restyled card (§17.2, §17.6); a broadened one prompts instead.
            val look = Consent.look(e)
            val before = cur.lines
            if (before == null) cur = cur.copy(lines = lines, look = look)
            else if (before != lines || cur.look != look) {
                val events = (before - lines.toSet()).map { Consent.Event(now, Consent.Event.Kind.NoLonger, it) }
                    .ifEmpty { if (before == lines) listOf(Consent.Event(now, Consent.Event.Kind.Layout)) else emptyList() }
                cur = cur.copy(lines = lines, look = look, changed = now).logged(events)
            }
            val (s, _) = Consent.state(lines, cur)
            val silent = lines.filterNot(Consent::needsOk).toSet()
            if (s == Consent.State.Allowed && !cur.ceiling.containsAll(silent)) cur = cur.copy(ceiling = cur.ceiling + silent)
            next[id] = cur
        }
        if (next != entries) { entries = next; save() }
    }

    private fun Consent.Entry.logged(events: List<Consent.Event>) =
        if (events.isEmpty()) this else copy(history = (history + events).takeLast(Consent.HISTORY_MAX))
    private fun Consent.Entry.logged(kind: Consent.Event.Kind, arg: String? = null) = logged(listOf(Consent.Event(clock(), kind, arg)))

    /**
     * `Allow`: union the lines the user was [shown] into the ceiling and turn the widget on. Refused (false, nothing
     * written) unless the card in [current] still has exactly that fingerprint, so no caller can approve lines the
     * user didn't see, e.g. after a poll broadened the file while the sheet was open (#158 review).
     */
    @Synchronized fun allow(id: String, shown: List<String>, current: LauncherConfig): Boolean {
        val card = Consent.cards(current).firstOrNull { (_, e) -> (e["id"] as? JsonPrimitive)?.content == id }?.second ?: return false
        if (Consent.lines(card) != shown) return false
        val cur = entries[id] ?: Consent.Entry()
        update(id, cur.copy(ceiling = cur.ceiling + shown, off = false).logged(Consent.Event.Kind.Allowed))
        return true
    }

    /** `Turn off`: sticky, keeps the ceiling, so `Allow` restores without a prompt while the file stays within it. */
    @Synchronized fun turnOff(id: String) {
        val cur = entries[id] ?: Consent.Entry()
        if (!cur.off) update(id, cur.copy(off = true).logged(Consent.Event.Kind.TurnedOff))
    }

    /** `Allow` on the detail page when nothing is missing: back on with the same ceiling. */
    @Synchronized fun turnOn(id: String) {
        entries[id]?.takeIf { it.off }?.let { update(id, it.copy(off = false).logged(Consent.Event.Kind.TurnedOn)) }
    }

    /** `Forget approval` (widget detail): clears the ceiling and the Always-allow sites; the widget stays off. History stays. */
    @Synchronized fun forget(id: String) {
        val cur = entries[id] ?: Consent.Entry()
        update(id, cur.copy(ceiling = emptySet(), alwaysAllow = emptySet(), off = true).logged(Consent.Event.Kind.Forgotten))
    }

    /** Q-D5 "Always allow <host>" ticked on a tap confirm: [origin] is the canonical scheme + host. */
    @Synchronized fun alwaysAllow(id: String, origin: String) {
        val cur = entries[id] ?: Consent.Entry()
        if (origin !in cur.alwaysAllow) update(id, cur.copy(alwaysAllow = cur.alwaysAllow + origin).logged(Consent.Event.Kind.Always, origin))
    }

    /** `Remove` under `Always allowed sites`: taps to [origin] ask again. */
    @Synchronized fun removeAlways(id: String, origin: String) {
        val cur = entries[id] ?: return
        if (origin in cur.alwaysAllow) update(id, cur.copy(alwaysAllow = cur.alwaysAllow - origin).logged(Consent.Event.Kind.SiteRemoved, origin))
    }

    /**
     * `Undo` on the Turn off and site-removed snackbars: put back the approval as it was in [before], and drop the
     * [undone] history row. What the file changed meanwhile (lines, look, times) is kept.
     */
    @Synchronized fun undo(id: String, before: Consent.Entry, undone: Consent.Event.Kind) {
        val cur = entries[id] ?: return
        val history = if (cur.history.lastOrNull()?.kind == undone) cur.history.dropLast(1) else cur.history
        update(id, cur.copy(ceiling = before.ceiling, off = before.off, alwaysAllow = before.alwaysAllow, history = history))
    }

    @Synchronized fun isAlwaysAllowed(id: String, origin: String): Boolean = origin in entries[id]?.alwaysAllow.orEmpty()

    private fun update(id: String, e: Consent.Entry) {
        if (entries[id] == e) return
        entries[id] = e
        save()
    }

    private fun load(): MutableMap<String, Consent.Entry> = try {
        val root = file?.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        root?.get("widgets")?.jsonObject?.mapValues { (_, v) ->
            val o = v.jsonObject
            fun set(k: String) = o[k]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            fun long(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()
            Consent.Entry(set("ceiling"), o["off"]?.jsonPrimitive?.booleanOrNull == true, set("alwaysAllow"), long("seen"),
                (o["lines"] as? JsonArray)?.map { it.jsonPrimitive.content }, long("look")?.toInt(), long("changed"),
                (o["history"] as? JsonArray).orEmpty().mapNotNull { h ->
                    val ho = h as? JsonObject ?: return@mapNotNull null
                    val k = Consent.Event.Kind.of((ho["kind"] as? JsonPrimitive)?.content) ?: return@mapNotNull null
                    val at = (ho["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return@mapNotNull null
                    Consent.Event(at, k, (ho["arg"] as? JsonPrimitive)?.content)
                })
        }?.toMutableMap() ?: mutableMapOf()
    } catch (_: Exception) {
        // An unreadable store asks again rather than guessing: every card that needs OK waits.
        mutableMapOf()
    }

    private fun save() {
        _version.value++
        val f = file ?: return
        val bytes = buildJsonObject {
            put("version", 1)
            put("widgets", JsonObject(entries.toSortedMap().mapValues { (_, e) ->
                buildJsonObject {
                    put("ceiling", JsonArray(e.ceiling.sorted().map { JsonPrimitive(it) }))
                    put("off", e.off)
                    put("alwaysAllow", JsonArray(e.alwaysAllow.sorted().map { JsonPrimitive(it) }))
                    e.seen?.let { put("seen", it) }
                    e.lines?.let { l -> put("lines", JsonArray(l.map { JsonPrimitive(it) })) }
                    e.look?.let { put("look", it) }
                    e.changed?.let { put("changed", it) }
                    if (e.history.isNotEmpty()) put("history", JsonArray(e.history.map { h ->
                        buildJsonObject { put("at", h.at); put("kind", h.kind.key); h.arg?.let { put("arg", it) } }
                    }))
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            dir!!.mkdirs()
            val tmp = File(dir, "consent.json.tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) { /* kept in memory; the next change retries */ }
    }
}
