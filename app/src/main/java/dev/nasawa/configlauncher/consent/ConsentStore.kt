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
class ConsentStore(private val dir: File?) {
    private val file = dir?.let { File(it, "consent.json") }
    private var entries: MutableMap<String, Consent.Entry> = load()

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
            val cur = next[id] ?: Consent.Entry()
            val (s, _) = Consent.state(lines, next[id])
            val silent = lines.filterNot(Consent::needsOk).toSet()
            if (s == Consent.State.Allowed && !cur.ceiling.containsAll(silent)) next[id] = cur.copy(ceiling = cur.ceiling + silent)
        }
        if (next != entries) { entries = next; save() }
    }

    /** `Allow`: union every declared line into the ceiling and turn the widget on. */
    @Synchronized fun allow(id: String, lines: List<String>) {
        val cur = entries[id] ?: Consent.Entry()
        update(id, cur.copy(ceiling = cur.ceiling + lines, off = false))
    }

    /** `Turn off`: sticky, keeps the ceiling, so `Allow` restores without a prompt while the file stays within it. */
    @Synchronized fun turnOff(id: String) = update(id, (entries[id] ?: Consent.Entry()).copy(off = true))

    /** `Undo` on the Turn off snackbar, and `Allow` when nothing is missing: back on with the same ceiling. */
    @Synchronized fun turnOn(id: String) { entries[id]?.let { update(id, it.copy(off = false)) } }

    /** `Forget approval` (widget detail): clears the ceiling and the Always-allow sites; the widget stays off. */
    @Synchronized fun forget(id: String) = update(id, Consent.Entry(off = true))

    /** Q-D5 "Always allow <host>" ticked on a tap confirm: [origin] is the canonical scheme + host. */
    @Synchronized fun alwaysAllow(id: String, origin: String) {
        val cur = entries[id] ?: Consent.Entry()
        update(id, cur.copy(alwaysAllow = cur.alwaysAllow + origin))
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
            Consent.Entry(set("ceiling"), o["off"]?.jsonPrimitive?.booleanOrNull == true, set("alwaysAllow"))
        }?.toMutableMap() ?: mutableMapOf()
    } catch (_: Exception) {
        // An unreadable store asks again rather than guessing: every card that needs OK waits.
        mutableMapOf()
    }

    private fun save() {
        val f = file ?: return
        val bytes = buildJsonObject {
            put("version", 1)
            put("widgets", JsonObject(entries.toSortedMap().mapValues { (_, e) ->
                buildJsonObject {
                    put("ceiling", JsonArray(e.ceiling.sorted().map { JsonPrimitive(it) }))
                    put("off", e.off)
                    put("alwaysAllow", JsonArray(e.alwaysAllow.sorted().map { JsonPrimitive(it) }))
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
