package dev.nasawa.configlauncher.widgetdata

import dev.nasawa.configlauncher.config.Card
import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.JsonPreScan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime

/**
 * #139 (S3-D): a card's local data file (dynamic-widgets.md §3 "Local"; ux.md §17.9 "Data states").
 *
 * The path is always `.configlauncher/widgets-data/<elementId>.json`, derived from the element id (the schema has no
 * `path` field), so one card can't read another's file. UTF-8 JSON with an object root, at most [MAX_BYTES].
 * Pure Kotlin; [WidgetDataReader] does the reading.
 */
object WidgetData {
    const val DIR = "widgets-data"
    const val MAX_BYTES = 256 * 1024
    /** ux.md §17.9: `staleAfter` defaults to 30 min when the card doesn't set one. */
    const val DEFAULT_STALE_MS = 30L * 60_000

    private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    /** File name for [id], or null when the id couldn't be a safe file name (the validator already rejects those). */
    fun fileName(id: String): String? = if (ID.matches(id)) "$id.json" else null

    /** One successful read of a data file. */
    data class Data(val root: JsonObject, val updatedAt: Long?, val hash: String)

    sealed interface Parsed {
        data class Ok(val data: Data) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    fun parse(bytes: ByteArray): Parsed {
        if (bytes.size > MAX_BYTES) return Parsed.Bad("larger than ${MAX_BYTES / 1024} KiB")
        val text = JsonPreScan.decodeUtf8(bytes) ?: return Parsed.Bad("not valid UTF-8")
        // Same strict pre-scan as launcher.json: no recursion, depth and duplicate keys checked first.
        JsonPreScan.check(text)?.let { return Parsed.Bad("not valid JSON: $it") }
        val root = try { Json.parseToJsonElement(text) } catch (_: Exception) { return Parsed.Bad("not valid JSON") }
        if (root !is JsonObject) return Parsed.Bad("the root must be an object")
        return Parsed.Ok(Data(root, updatedAt(root), ConfigCodec.sha256(bytes)))
    }

    /** Root `updatedAt` (ISO-8601 with an offset, e.g. `2026-10-19T08:38:00-06:00`) in epoch ms; null if absent or not a date. */
    fun updatedAt(root: JsonObject): Long? = (root["updatedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }

    /** `data.staleAfter` (`PnDTnHnMnS`, the [Card.DURATION] subset) in ms, or [DEFAULT_STALE_MS]. */
    fun staleAfterMs(card: JsonObject): Long {
        val s = ((card["data"] as? JsonObject)?.get("staleAfter") as? JsonPrimitive)?.content ?: return DEFAULT_STALE_MS
        if (!Card.DURATION.matches(s)) return DEFAULT_STALE_MS
        var ms = 0L
        Regex("([0-9]+)([DHMS])").findAll(s.substringBefore('T')).forEach { ms += it.groupValues[1].toLong() * 86_400_000 }
        Regex("([0-9]+)([HMS])").findAll(s.substringAfter('T', "")).forEach {
            val n = it.groupValues[1].toLong()
            ms += when (it.groupValues[2]) { "H" -> n * 3_600_000; "M" -> n * 60_000; else -> n * 1000 }
        }
        return ms
    }

    /** Stale when the data says when it was written and that's longer ago than `staleAfter`. A reload never resets this. */
    fun isStale(updatedAt: Long?, staleAfterMs: Long, now: Long): Boolean = updatedAt != null && now - updatedAt > staleAfterMs

    private val SEG = Regex("^([A-Za-z0-9_-]+)((\\[[0-9]+])*)$")
    private val IDX = Regex("\\[([0-9]+)]")

    /**
     * `{data:a.b[0].c}` lookup (Q-A5: dots and `[index]` only). Null when any step is missing; the card then shows `—`.
     * The template grammar itself is checked with the card renderer (S3-F).
     */
    fun lookup(root: JsonObject?, path: String): JsonElement? {
        var cur: JsonElement = root ?: return null
        for (seg in path.split('.')) {
            val m = SEG.matchEntire(seg) ?: return null
            cur = (cur as? JsonObject)?.get(m.groupValues[1]) ?: return null
            for (i in IDX.findAll(m.groupValues[2])) cur = (cur as? JsonArray)?.getOrNull(i.groupValues[1].toInt()) ?: return null
        }
        return cur
    }

    const val EMPTY = "—"

    /** A bound value as text: strings and numbers as written, `true`/`false`; anything missing, null or structured is `—`. */
    fun text(root: JsonObject?, path: String): String = when (val v = lookup(root, path)) {
        is JsonPrimitive -> if (v.isString) v.content.ifEmpty { EMPTY } else if (v.content == "null") EMPTY else v.content
        else -> EMPTY
    }

    /** The status.json sentence (Pragma §17.13 `warn_data_missing`, English in code). */
    fun missingWarning(path: String, id: String) = "$path: widgets-data/$id.json is missing or unreadable, so the last values are shown."
}
