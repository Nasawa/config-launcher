package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.model.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.security.MessageDigest

sealed interface ParseResult {
    data class Ok(val config: LauncherConfig) : ParseResult
    data class Invalid(val errors: List<String>) : ParseResult
}

/**
 * JSON codec. Decoding = parse tree → migrate → [Validator] → model. Encoding is canonical
 * (AQ-2): 2-space indent, schema key order, `x-` keys after known keys, LF, trailing newline, no BOM.
 */
object ConfigCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    const val MAX_BYTES = Limits.MAX_BYTES

    fun decode(bytes: ByteArray): ParseResult {
        if (bytes.size > MAX_BYTES) return ParseResult.Invalid(listOf("file is larger than ${MAX_BYTES / 1024 / 1024} MiB"))
        var text = JsonPreScan.decodeUtf8(bytes) ?: return ParseResult.Invalid(listOf("not valid UTF-8"))
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        return decode(text)
    }

    fun decode(text: String): ParseResult {
        // A git merge left conflict markers in the file: say so instead of a generic syntax error.
        GitMarkers.firstLine(text)?.let {
            return ParseResult.Invalid(listOf("not valid JSON: git merge conflict markers (line $it); finish the merge in git"))
        }
        JsonPreScan.check(text)?.let { return ParseResult.Invalid(listOf("not valid JSON: $it")) }
        val tree = try { Json.parseToJsonElement(text) } catch (e: Exception) {
            return ParseResult.Invalid(listOf("not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}"))
        }
        val migrated = Migrations.migrate(tree)
        migrated.error?.let { return ParseResult.Invalid(listOf(it)) }
        val errors = Validator.validate(migrated.tree)
        if (errors.isNotEmpty()) return ParseResult.Invalid(errors)
        return ParseResult.Ok(toModel(migrated.tree.jsonObject))
    }

    fun encode(config: LauncherConfig): String = pretty.encodeToString(JsonElement.serializer(), toJson(config)) + "\n"

    fun encodeBytes(config: LauncherConfig): ByteArray = encode(config).toByteArray(Charsets.UTF_8)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- tree -> model (input already validated) ----
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.extras() = filterKeys { it.startsWith("x-") }

    private fun toModel(o: JsonObject) = LauncherConfig(
        schemaRef = o.s("\$schema"),
        schemaVersion = o.i("schemaVersion") ?: 1,
        revision = o.s("revision")?.toLongOrNull(),
        grid = (o["grid"] as? JsonObject)?.let { Grid(it.i("columns") ?: 4, it.i("rows") ?: 5) },
        pages = o["pages"]!!.jsonArray.map { p ->
            val po = p.jsonObject
            Page(po.s("id")!!, po.s("title"), po["items"]!!.jsonArray.map { item(it.jsonObject) }, po.extras(), po["overrides"] as? JsonObject)
        },
        dock = (o["dock"] as? JsonArray)?.map { item(it.jsonObject) },
        drawer = (o["drawer"] as? JsonObject)?.let { d ->
            Drawer(d.s("sort"), (d["hidden"] as? JsonArray)?.map { it.jsonPrimitive.content }, (d["rules"] as? JsonArray)?.toList())
        },
        extras = o.extras(),
        elements = (o["elements"] as? JsonArray)?.map { it.jsonObject },
    )

    private fun item(o: JsonObject): Item = when (o.s("type")) {
        "app" -> AppItem(o.s("id")!!, o.s("package")!!, o.s("activity"), Profile.of(o.s("profile")), o.s("label"),
            (o["tags"] as? JsonArray)?.map { it.jsonPrimitive.content }, o.i("x"), o.i("y"), o.extras())
        "shortcut" -> ShortcutItem(o.s("id")!!, o.s("package")!!, o.s("shortcutId")!!, Profile.of(o.s("profile")), o.s("label"), o.i("x"), o.i("y"), o.extras())
        else -> FolderItem(o.s("id")!!, o.s("label"), o["items"]!!.jsonArray.map { item(it.jsonObject) }, o.i("x"), o.i("y"), o.extras())
    }

    // ---- model -> tree, schema key order ----
    private fun JsonObjectBuilder.opt(k: String, v: String?) { if (v != null) put(k, v) }
    private fun JsonObjectBuilder.opt(k: String, v: Number?) { if (v != null) put(k, v) }
    private fun JsonObjectBuilder.extras(e: Extras) { e.toSortedMap().forEach { (k, v) -> put(k, v) } }

    fun toJson(c: LauncherConfig): JsonObject = buildJsonObject {
        opt("\$schema", c.schemaRef)
        put("schemaVersion", c.schemaVersion)
        opt("revision", c.revision)
        c.grid?.let { putJsonObject("grid") { put("columns", it.columns); put("rows", it.rows) } }
        putJsonArray("pages") {
            c.pages.forEach { p ->
                addJsonObject {
                    put("id", p.id); opt("title", p.title)
                    putJsonArray("items") { p.items.forEach { add(itemJson(it)) } }
                    p.overrides?.let { put("overrides", it) }   // #79, verbatim
                    extras(p.extras)
                }
            }
        }
        c.dock?.let { d -> putJsonArray("dock") { d.forEach { add(itemJson(it)) } } }
        c.drawer?.let { d ->
            putJsonObject("drawer") {
                opt("sort", d.sort)
                d.hidden?.let { h -> putJsonArray("hidden") { h.forEach { add(it) } } }
                d.rules?.let { r -> put("rules", JsonArray(r.map(::canonicalRule))) }
            }
        }
        // #79: verbatim, file order. A known element type will get canonical key order when it's added (title bar).
        c.elements?.let { put("elements", JsonArray(it.map(Elements::canonical))) }
        extras(c.extras)
    }

    fun itemJson(i: Item): JsonObject = buildJsonObject {
        put("id", i.id)
        when (i) {
            is AppItem -> {
                put("type", "app"); put("package", i.packageName); opt("activity", i.activity)
                opt("profile", i.profile?.wire); opt("label", i.label)
                i.tags?.let { t -> putJsonArray("tags") { t.forEach { add(it) } } }
            }
            is ShortcutItem -> {
                put("type", "shortcut"); put("package", i.packageName); put("shortcutId", i.shortcutId)
                opt("profile", i.profile?.wire); opt("label", i.label)
            }
            is FolderItem -> {
                put("type", "folder"); opt("label", i.label)
                putJsonArray("items") { i.items.forEach { add(itemJson(it)) } }
            }
        }
        opt("x", i.x); opt("y", i.y)
        extras(i.extras)
    }

    private val RULE_KEYS = listOf("id", "group", "match")
    private val MATCH_KEYS = listOf("packages", "packagePrefix", "tag", "category")

    private fun ordered(o: JsonObject, keys: List<String>, inner: (String, JsonElement) -> JsonElement = { _, v -> v }) =
        JsonObject(LinkedHashMap<String, JsonElement>().apply {
            keys.forEach { k -> o[k]?.let { put(k, inner(k, it)) } }
            o.forEach { (k, v) -> if (k !in keys) put(k, v) }
        })

    /** ARCHITECTURE.md §20.3: drawer rules are written as id, group, match{packages, packagePrefix, tag, category}. */
    internal fun canonicalRule(r: JsonElement): JsonElement =
        if (r !is JsonObject) r
        else ordered(r, RULE_KEYS) { k, v -> if (k == "match" && v is JsonObject) ordered(v, MATCH_KEYS) else v }
}
