package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/**
 * #137 S3-A: the `card` element (feature level 4), docs/design/dynamic-widgets.md §1–§4, §9, §11. Schema, codec and
 * validation only: nothing renders yet (S3-E), there's no consent store (S3-B) and no data runtime (S3-D).
 *
 * - Grid-placed (`page`, `x`, `y`, `w`, `h`), `requires` 4 or higher (feature level 3 stays reserved for `appWidget`).
 * - `capabilities`: the stage-3 kinds only, with kind-specific keys (§4): `data.file` and `ui.reload` (no fields),
 *   `ui.openApp` + `packages`, and a bare `ui.openUrl` (Q-D5: no `hosts`; taps to the card's optional `source` site
 *   open silently, any other host asks on every tap). On the phone, a kind this launcher doesn't know **skips the
 *   card** with a warning and keeps its cells ([unknownCapability]); `scripts/validate.py` reports it as an error so
 *   agents catch typos.
 * - `layouts`: 1–3 `"WxH"` keys, each a list of nodes: containers `column`, `row`, `tile`; primitives `text`, `metric`,
 *   `list`, `icon`, `progress`, `button`. Taps must be allowed by a declared capability.
 * - Optional `title`, `author` (omitted = hidden), `source` (in the consent fingerprint; not a fetch origin), `surface`,
 *   `minSpan`, `data.staleAfter`.
 * Strict like every known type: unknown keys are errors. `scripts/validate.py` mirrors this.
 */
object Card : Elements.ElementType {
    override val name = "card"
    override val level = 4

    val KEYS = listOf("id", "type", "requires", "page", "x", "y", "w", "h", "minSpan", "title", "author", "source", "surface", "capabilities", "data", "layouts")
    /** Stage-3 capability kinds and their fields (§4 "Capability field names"). */
    val KINDS: Map<String, List<String>> = linkedMapOf("data.file" to emptyList(), "ui.reload" to emptyList(), "ui.openApp" to listOf("packages"), "ui.openUrl" to emptyList())
    val TAP_KINDS: Map<String, List<String>> = linkedMapOf("ui.openUrl" to listOf("url"), "ui.openApp" to listOf("package"), "ui.reload" to emptyList())
    val NODES: Map<String, List<String>> = linkedMapOf(
        "column" to listOf("children"), "row" to listOf("children"), "tile" to listOf("children"),
        "text" to listOf("text", "role", "maxLines"), "metric" to listOf("label", "value", "unit", "status"),
        "list" to listOf("label", "items", "maxItems", "emptyText"), "icon" to listOf("name", "package"),
        "progress" to listOf("label", "value"), "button" to listOf("label"),
    )
    val NODE_REQUIRED: Map<String, Set<String>> = mapOf(
        "column" to setOf("children"), "row" to setOf("children"), "tile" to setOf("children"), "text" to setOf("text"),
        "metric" to setOf("value"), "list" to setOf("items"), "progress" to setOf("value"), "button" to setOf("label", "tap"),
    )
    val COMMON = listOf("type", "priority", "weight", "tap")
    val ROLES = listOf("caption", "body", "title")
    val LAYOUT = Regex("^([1-9][0-9]?)x([1-9][0-9]?)$")
    val HOST = Regex("^(https?://)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    val DURATION = Regex("^P(?=[0-9]|T[0-9])([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$")
    private val URL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")

    const val MIN_REQUIRES = 4
    const val MAX_CAPABILITIES = 8
    const val MAX_TARGETS = 8
    const val MAX_LAYOUTS = 3
    const val MAX_CHILDREN = 12
    const val MAX_DEPTH = 4
    const val MAX_NODES = 48
    const val MAX_TEXT = 200
    const val MAX_URL = 2048

    /** §4 "Enforcement": the first `capabilities[].kind` this launcher doesn't know, so the card is skipped. */
    override fun unknownCapability(o: JsonObject): String? =
        (o["capabilities"] as? JsonArray)?.firstNotNullOfOrNull { c -> ((c as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it !in KINDS } }

    private class Caps(val kinds: Set<String>, val packages: Set<String>)

    override fun validate(o: JsonObject, path: String, c: Validator.Checks) {
        c.keys(o, path, KEYS.toSet(), setOf("requires", "page", "layouts"))
        (o["requires"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.let {
            if (it < MIN_REQUIRES) c.err("$path.requires", "a card needs feature level $MIN_REQUIRES or higher")
        }
        o["minSpan"]?.let { e -> c.obj(e, "$path.minSpan")?.let { m ->
            c.keys(m, "$path.minSpan", setOf("w", "h"), setOf("w", "h"), xKeys = false)
            c.int(m, "w", "$path.minSpan", 1, 12); c.int(m, "h", "$path.minSpan", 1, 12)
        } }
        c.str(o, "title", path, max = 40)
        c.str(o, "author", path, max = 40, min = 1)
        // Q-D5: the site taps open silently: a bare hostname (https) or http:// / https:// + hostname.
        c.str(o, "source", path)?.let { if (it.length > 253 + 8 || !HOST.matches(it)) c.err("", "$path.source must be a lowercase hostname, optionally with http:// or https://.") }
        c.str(o, "surface", path)?.let { if (it != "auto") c.err("", "$path.surface must be \"auto\".") }
        val caps = capabilities(o, path, c)
        o["data"]?.let { e -> c.obj(e, "$path.data")?.let { d ->
            c.keys(d, "$path.data", setOf("staleAfter"), emptySet(), xKeys = false)
            c.str(d, "staleAfter", "$path.data", max = 32, re = DURATION)
        } }
        o["layouts"]?.let { e -> c.obj(e, "$path.layouts")?.let { ls -> layouts(ls, "$path.layouts", caps, c) } }
    }

    private fun capabilities(o: JsonObject, path: String, c: Validator.Checks): Caps {
        val kinds = mutableSetOf<String>(); val packages = mutableSetOf<String>()
        c.arr(o, "capabilities", path, max = MAX_CAPABILITIES)?.forEachIndexed { i, e ->
            val cp = "$path.capabilities[$i]"
            val co = c.obj(e, cp) ?: return@forEachIndexed
            val kind = c.str(co, "kind", cp)
            if (kind == null) { if ("kind" !in co) c.err(cp, "missing required \"kind\""); return@forEachIndexed }
            val fields = KINDS[kind] ?: return@forEachIndexed   // unknown kinds skip the card before validation
            c.keys(co, cp, (listOf("kind") + fields).toSet(), fields.toSet(), xKeys = false)
            if (!kinds.add(kind)) c.err("$cp.kind", "\"$kind\" is listed twice")
            c.arr(co, "packages", cp, max = MAX_TARGETS, min = 1)?.forEachIndexed { j, p ->
                val s = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s == null || !Validator.PKG.matches(s)) c.err("$cp.packages[$j]", "must be a package name") else if (!packages.add(s)) c.err("$cp.packages[$j]", "\"$s\" is listed twice")
            }
        }
        return Caps(kinds, packages)
    }

    private fun layouts(ls: JsonObject, path: String, caps: Caps, c: Validator.Checks) {
        if (ls.isEmpty()) c.err(path, "needs at least 1 entries")
        if (ls.size > MAX_LAYOUTS) c.err(path, "has ${ls.size} entries, at most $MAX_LAYOUTS allowed")
        for (k in ls.keys) {
            val m = LAYOUT.matchEntire(k)
            if (m == null || m.groupValues[1].toInt() > 12 || m.groupValues[2].toInt() > 12) { c.err(path, "\"$k\" is not a size like \"4x3\" (lowercase x, 1 to 12)"); continue }
            val count = intArrayOf(0)
            c.arr(ls, k, path, max = MAX_CHILDREN, min = 1)?.forEachIndexed { i, n -> node(n, "$path.$k[$i]", 1, count, "$path.$k", caps, c) }
        }
    }

    private fun node(e: JsonElement, np: String, depth: Int, count: IntArray, lp: String, caps: Caps, c: Validator.Checks) {
        if (++count[0] == MAX_NODES + 1) c.err(lp, "has more than $MAX_NODES nodes")
        val o = c.obj(e, np) ?: return
        val type = c.str(o, "type", np)
        if (type == null) { if ("type" !in o) c.err(np, "missing required \"type\""); return }
        val fields = NODES[type] ?: run { c.err("$np.type", "unknown node type \"$type\". Use ${NODES.keys.joinToString(", ")}."); return }
        c.keys(o, np, (COMMON + fields).toSet(), NODE_REQUIRED[type].orEmpty(), xKeys = false)
        c.int(o, "priority", np, 0, 9)
        c.int(o, "weight", np, 1, 12)
        val has = { k: String -> k in fields }
        if (has("text")) c.str(o, "text", np, max = MAX_TEXT)
        if (has("role")) c.str(o, "role", np)?.let { if (it !in ROLES) c.err("$np.role", "must be caption, body or title") }
        if (has("maxLines")) c.int(o, "maxLines", np, 1, 6)
        if (has("label")) c.str(o, "label", np, max = 40)
        if (type == "progress") o["value"]?.let { v ->
            val p = v as? JsonPrimitive
            if (p?.isString == true) c.str(o, "value", np, max = MAX_TEXT)
            else if ((p?.content?.toDoubleOrNull() ?: -1.0) !in 0.0..1.0) c.err("$np.value", "must be a template or a number from 0 to 1")
        } else if (has("value")) c.str(o, "value", np, max = MAX_TEXT)
        if (has("unit")) c.str(o, "unit", np, max = 16)
        if (has("status")) c.str(o, "status", np, max = MAX_TEXT)
        if (has("items")) c.str(o, "items", np, max = MAX_TEXT)
        if (has("maxItems")) o["maxItems"]?.let { v ->
            if ((v as? JsonPrimitive)?.let { it.isString && it.content == "auto" } != true) c.int(o, "maxItems", np, 1, 20)
        }
        if (has("emptyText")) c.str(o, "emptyText", np, max = 80)
        if (type == "icon") {
            c.str(o, "name", np, max = 64)
            c.str(o, "package", np, re = Validator.PKG)
            if (("name" in o) == ("package" in o)) c.err(np, "needs exactly one of \"name\" or \"package\"")
        }
        o["tap"]?.let { tap(it, "$np.tap", caps, c) }
        if (has("children") && "children" in o) {
            if (depth >= MAX_DEPTH) c.err(np, "is nested too deep (at most $MAX_DEPTH levels)")
            else c.arr(o, "children", np, max = MAX_CHILDREN, min = 1)?.forEachIndexed { i, n -> node(n, "$np.children[$i]", depth + 1, count, lp, caps, c) }
        }
    }

    private fun tap(e: JsonElement, tp: String, caps: Caps, c: Validator.Checks) {
        val o = c.obj(e, tp) ?: return
        val kind = c.str(o, "kind", tp)
        if (kind == null) { if ("kind" !in o) c.err(tp, "missing required \"kind\""); return }
        val fields = TAP_KINDS[kind] ?: run { c.err("$tp.kind", "must be ui.openUrl, ui.openApp or ui.reload"); return }
        c.keys(o, tp, (listOf("kind") + fields).toSet(), fields.toSet(), xKeys = false)
        if (kind !in caps.kinds) { c.err(tp, "$kind isn't in this card's capabilities"); return }
        c.str(o, "package", tp, re = Validator.PKG)?.let { if (it !in caps.packages) c.err("$tp.package", "\"$it\" isn't in the ui.openApp packages") }
        c.str(o, "url", tp, max = MAX_URL, min = 1)?.let { url -> if ('{' !in url) urlProblem(url)?.let { c.err("$tp.url", it) } }
    }

    /**
     * A literal tap URL (a `{data:…}` URL is checked at tap time) must be http or https with no user info. Which host
     * it opens is decided at tap time (Q-D5): the card's `source` opens silently, anything else asks every time.
     */
    fun urlProblem(url: String): String? {
        val m = URL.find(url) ?: return "must be an http or https URL"
        val scheme = m.groupValues[1].lowercase()
        if (scheme != "http" && scheme != "https") return "must be an http or https URL"
        if ('@' in m.groupValues[2]) return "must not contain user info"
        return null
    }

    override fun canonical(o: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in KEYS) o[k]?.let { v ->
            out[k] = when (k) {
                "minSpan" -> order(v as JsonObject, listOf("w", "h"))
                "capabilities" -> JsonArray((v as JsonArray).map { cap -> (cap as JsonObject).let { co -> order(co, listOf("kind") + KINDS[(co["kind"] as JsonPrimitive).content].orEmpty()) } })
                else -> v
            }
        }
        o.filterKeys { it.startsWith("x-") }.toSortedMap().forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    private fun order(o: JsonObject, keys: List<String>) = JsonObject(LinkedHashMap<String, JsonElement>().apply { for (k in keys) o[k]?.let { put(k, it) } })
}
