package dev.nasawa.configlauncher.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** `.configlauncher/status.json`: last load result, errors, file hash, time (ARCHITECTURE.md §16/§17). */
object StatusJson {
    const val FILE = "status.json"

    fun resultOf(issue: Issue): String = when (issue) {
        is Issue.ParseError -> "parse_error"
        is Issue.Invalid -> "invalid"
        is Issue.TooNew -> "too_new"
        else -> "error"
    }

    private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun iso(ms: Long): String = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(ms))

    /** `launcher.<UTC timestamp>.json`, sortable and safe on every SAF provider. */
    fun brokenFileName(ms: Long): String = "launcher." + fmt("yyyyMMdd'T'HHmmss'Z'").format(Date(ms)) + ".json"

    fun encode(
        result: String, errors: List<String>, hash: String?, ms: Long,
        backupError: String? = null, conflictFiles: List<String> = emptyList(), folder: String? = null,
    ): ByteArray = buildJsonObject {
        put("result", result)
        put("errors", JsonArray(errors.map { JsonPrimitive(it) }))
        put("hash", hash)
        put("time", iso(ms))
        // §17 Q4: null when the private backup copy is current.
        put("backupError", backupError)
        // OQ-21: never loaded, merged or deleted; listed so the agent/user can clean up.
        put("conflictFiles", JsonArray(conflictFiles.map { JsonPrimitive(it) }))
        // #57: where this launcher.json is, so you can tell it's the active one.
        put("folder", folder)
    }.toString().plus("\n").toByteArray(Charsets.UTF_8)
}
