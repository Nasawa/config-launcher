package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/**
 * Hand-written validator mirroring schema/launcher-config.v1.schema.json (ARCHITECTURE.md §2),
 * plus semantic rules the schema cannot express (AQ-3): unique IDs, cells inside the grid,
 * no overlapping cells. Errors are path-based, e.g. "pages[0].items[2].x: 5 is outside the grid".
 */
object Validator {
    private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    internal val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val CLS = Regex("^[A-Za-z0-9_.$]+$")
    private val CATEGORIES = setOf("game", "audio", "video", "image", "social", "news", "maps", "productivity", "accessibility", "undefined")

    fun validate(root: JsonElement): List<String> = Run().apply { root(root); tooLarge(root, "") }.errors

    /**
     * #113: the writer emits a number as Long, then ULong, then Double (kotlinx JsonLiteralSerializer), and throws on
     * a non-finite Double. A number outside the Double range anywhere (x- keys included) is therefore an error.
     */
    private fun Run.tooLarge(e: JsonElement, path: String) {
        when (e) {
            is JsonObject -> for ((k, v) in e) tooLarge(v, if (path.isEmpty()) k else "$path.$k")
            is JsonArray -> e.forEachIndexed { i, v -> tooLarge(v, "$path[$i]") }
            is JsonPrimitive -> if (!e.isString && e !is JsonNull && e.content.toLongOrNull() == null && e.content.toULongOrNull() == null &&
                e.content.toDoubleOrNull()?.isFinite() == false) err(path, "${e.content} is too large for a number")
        }
    }

    private enum class Ctx { PAGE, FOLDER, DOCK }

    /** Checks an [Elements.ElementType] uses, so element types share the validator's paths and messages. */
    interface Checks {
        fun err(path: String, msg: String)
        fun obj(e: JsonElement?, path: String): JsonObject?
        fun keys(o: JsonObject, path: String, allowed: Set<String>, required: Set<String>, xKeys: Boolean = true)
        fun str(o: JsonObject, k: String, path: String, max: Int? = null, min: Int = 0, re: Regex? = null): String?
        fun int(o: JsonObject, k: String, path: String, min: Long, max: Long? = null): Long?
        fun bool(o: JsonObject, k: String, path: String): Boolean?
        fun arr(o: JsonObject, k: String, path: String, max: Int? = null, min: Int = 0): JsonArray?
    }

    private class Run : Checks {
        val errors = mutableListOf<String>()
        val ids = mutableMapOf<String, String>()
        var itemCount = 0
        var columns = 4
        var rows = 5
        /** Page ID → occupied cells → path, so grid-placed elements are checked against items (#79 Stage 1). */
        val pageCells = mutableMapOf<String, MutableMap<Pair<Long, Long>, String>>()
        /** Type name → path of the first shown element, for single-instance types (one titleBar). */
        val singles = mutableMapOf<String, String>()
        /** Element ID → type, for shown elements; overrides naming them get the type's checks. */
        val shownIds = mutableMapOf<String, Elements.ElementType>()
        /** Override entries (path, value) collected per page, checked once the elements are known. */
        val overrideEntries = mutableListOf<Triple<String, String, JsonObject>>()

        override fun err(path: String, msg: String) { errors += if (path.isEmpty()) msg else "$path: $msg" }

        override fun obj(e: JsonElement?, path: String): JsonObject? =
            e as? JsonObject ?: null.also { err(path, "must be an object") }

        override fun keys(o: JsonObject, path: String, allowed: Set<String>, required: Set<String>, xKeys: Boolean) {
            for (k in o.keys) if (k !in allowed && !(xKeys && k.startsWith("x-"))) err(path, "unknown key \"$k\"")
            for (k in required) if (k !in o) err(path, "missing required \"$k\"")
        }

        override fun str(o: JsonObject, k: String, path: String, max: Int?, min: Int, re: Regex?): String? {
            val v = o[k] ?: return null
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s == null) { err("$path.$k", "must be a string"); return null }
            val len = s.codePointCount(0, s.length) // JSON Schema counts code points
            if (len > (max ?: Limits.MAX_STRING)) err("$path.$k", "longer than ${max ?: Limits.MAX_STRING} characters")
            if (len < min) err("$path.$k", "must not be empty")
            if (re != null && !re.matches(s)) err("$path.$k", "\"$s\" is not valid")
            return s
        }

        override fun int(o: JsonObject, k: String, path: String, min: Long, max: Long?): Long? {
            val v = o[k] ?: return null
            val p = v as? JsonPrimitive
            val n = if (p != null && !p.isString) p.content.toLongOrNull() else null
            if (n == null) { err("$path.$k", "must be an integer"); return null }
            if (n < min || (max != null && n > max)) err("$path.$k", "$n is out of range ${min}..${max ?: "∞"}")
            return n
        }

        override fun bool(o: JsonObject, k: String, path: String): Boolean? {
            val v = o[k] ?: return null
            val p = v as? JsonPrimitive
            if (p == null || p.isString || (p.content != "true" && p.content != "false")) { err("$path.$k", "must be true or false"); return null }
            return p.content == "true"
        }

        override fun arr(o: JsonObject, k: String, path: String, max: Int?, min: Int): JsonArray? {
            val v = o[k] ?: return null
            val a = v as? JsonArray ?: run { err("$path.$k", "must be an array"); return null }
            if (max != null && a.size > max) err("$path.$k", "has ${a.size} entries, at most $max allowed")
            if (a.size < min) err("$path.$k", "needs at least $min entries")
            return a
        }

        fun root(e: JsonElement) {
            val o = obj(e, "") ?: return
            keys(o, "", setOf("\$schema", "schemaVersion", "revision", "grid", "pages", "dock", "drawer", "elements", "unsorted"), setOf("schemaVersion", "pages"))
            str(o, "\$schema", "")
            o["schemaVersion"]?.let { if ((it as? JsonPrimitive)?.content != "1" || it.isString) err("schemaVersion", "must be 1") }
            int(o, "revision", "", 0)
            o["grid"]?.let { g ->
                obj(g, "grid")?.let { go ->
                    keys(go, "grid", setOf("columns", "rows"), emptySet(), xKeys = false)
                    int(go, "columns", "grid", 2, 12)?.let { columns = it.toInt().coerceIn(2, 12) }
                    int(go, "rows", "grid", 2, 12)?.let { rows = it.toInt().coerceIn(2, 12) }
                }
            }
            arr(o, "pages", "", max = 20, min = 1)?.forEachIndexed { i, p -> page(p, "pages[$i]") }
            arr(o, "dock", "", max = 7)?.forEachIndexed { i, it -> item(it, "dock[$i]", Ctx.DOCK) }
            o["drawer"]?.let { drawer(it) }
            arr(o, "elements", "", max = Limits.MAX_ELEMENTS)?.forEachIndexed { i, e -> element(e, "elements[$i]") }
            for ((id, vp, vo) in overrideEntries) shownIds[id]?.validateOverride(vo, vp, this)
            if (itemCount > Limits.MAX_ITEMS) err("", "$itemCount items in total, at most ${Limits.MAX_ITEMS} allowed")
            o["unsorted"]?.let { u ->
                obj(u, "unsorted")?.let { uo ->
                    keys(uo, "unsorted", setOf("mode", "pageTitle"), emptySet(), xKeys = false)
                    str(uo, "mode", "unsorted")?.let { if (it !in setOf("drawer", "page", "hidden")) err("unsorted.mode", "must be drawer, page or hidden") }
                    str(uo, "pageTitle", "unsorted", max = 40)
                }
            }
        }

        fun id(o: JsonObject, path: String) {
            val s = str(o, "id", path, re = ID) ?: return
            val prev = ids.put(s, path)
            if (prev != null) err("$path.id", "duplicate id \"$s\" (also at $prev)")
        }

        /**
         * #79: `id`, `type` and `requires` are checked for every element. A skipped one (unknown type, `requires`
         * above the feature level) gets nothing more: it's a warning ([Elements.warnings]), kept verbatim.
         */
        fun element(e: JsonElement, path: String) {
            val o = obj(e, path) ?: return
            for (k in listOf("id", "type")) if (k !in o) err(path, "missing required \"$k\"")
            if ("id" in o) id(o, path)
            val type = str(o, "type", path, re = Elements.TYPE)
            int(o, "requires", path, 1)
            placement(o, path)
            if (type == null) return
            val t = Elements.typeOf(o) ?: return
            if (t.single) singles[t.name]?.let { err(path, "only one ${t.name} is allowed ($it is already one).") } ?: singles.put(t.name, path)
            t.validate(o, path, this)
            (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { shownIds.putIfAbsent(it, t) }
        }

        /**
         * #79 Stage 1 (docs/research/widgets.md §5.1): the grid-placement base block, checked for **every** element
         * before the type check, so a skipped element still reserves its cells and nothing can overlap them.
         */
        fun placement(o: JsonObject, path: String) {
            val placed = Elements.PLACEMENT.filter { it in o }
            str(o, "anchor", path, max = 32)
            if ("anchor" in o && placed.isNotEmpty()) err(path, "has both \"anchor\" and a grid position; use one")
            if (placed.isEmpty()) return
            for (k in Elements.PLACEMENT) if (k !in o) err(path, "missing required \"$k\" (grid-placed elements need page, x, y, w and h)")
            val page = str(o, "page", path, re = ID)
            val x = int(o, "x", path, 0, 11); val y = int(o, "y", path, 0, 11)
            val w = int(o, "w", path, 1, 12); val h = int(o, "h", path, 1, 12)
            if (page == null || x == null || y == null || w == null || h == null) return
            val cells = pageCells[page] ?: run { err("$path.page", "no page with id \"$page\""); return }
            if (x + w > columns) err(path, "x $x + w $w is outside the grid ($columns columns)")
            if (y + h > rows) err(path, "y $y + h $h is outside the grid ($rows rows)")
            for (cy in y until minOf(y + h, rows.toLong())) for (cx in x until minOf(x + w, columns.toLong())) {
                cells.put(cx to cy, path)?.let { other -> err(path, "overlaps $other at cell ($cx, $cy)"); return }
            }
        }

        /** #79 `pages[].overrides`: element ID → any of `enabled`, `content`, `style`. A missing element is a warning. */
        fun overrides(e: JsonElement, path: String) {
            val o = obj(e, path) ?: return
            if (o.size > Limits.MAX_OVERRIDES) err(path, "has ${o.size} entries, at most ${Limits.MAX_OVERRIDES} allowed")
            for ((k, v) in o) {
                if (!ID.matches(k)) { err(path, "\"$k\" is not a valid element id"); continue }
                val vp = "$path.$k"
                val vo = obj(v, vp) ?: continue
                keys(vo, vp, setOf("enabled", "content", "style"), emptySet(), xKeys = false)
                if (vo.isEmpty()) err(vp, "needs at least one of enabled, content or style")
                bool(vo, "enabled", vp)
                val c = vo["content"]?.let { obj(it, "$vp.content") }
                val st = vo["style"]?.let { obj(it, "$vp.style") }
                if (("content" !in vo || c != null) && ("style" !in vo || st != null)) overrideEntries += Triple(k, vp, vo)
            }
        }

        fun page(e: JsonElement, path: String) {
            val o = obj(e, path) ?: return
            keys(o, path, setOf("id", "title", "items", "overrides"), setOf("id", "items"))
            id(o, path)
            str(o, "title", path, max = 40)
            o["overrides"]?.let { overrides(it, "$path.overrides") }
            val cells = mutableMapOf<Pair<Long, Long>, String>()
            (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { pageCells.putIfAbsent(it, cells) }
            arr(o, "items", path, max = 144)?.forEachIndexed { i, it ->
                val ip = "$path.items[$i]"
                item(it, ip, Ctx.PAGE)
                val io = it as? JsonObject ?: return@forEachIndexed
                val x = (io["x"] as? JsonPrimitive)?.content?.toLongOrNull()
                val y = (io["y"] as? JsonPrimitive)?.content?.toLongOrNull()
                if (x != null && y != null) {
                    if (x >= columns) err("$ip.x", "$x is outside the grid ($columns columns)")
                    if (y >= rows) err("$ip.y", "$y is outside the grid ($rows rows)")
                    cells.put(x to y, ip)?.let { other -> err(ip, "overlaps $other at cell ($x, $y)") }
                }
            }
        }

        fun item(e: JsonElement, path: String, ctx: Ctx) {
            val o = obj(e, path) ?: return
            itemCount++
            val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val common = setOf("id", "type", "label", "x", "y")
            when (type) {
                "app" -> {
                    keys(o, path, common + setOf("package", "activity", "profile", "tags"), setOf("id", "type", "package"))
                    str(o, "package", path, re = PKG)
                    str(o, "activity", path, re = CLS)
                    profile(o, path)
                    arr(o, "tags", path, max = Limits.MAX_TAGS)?.let { tags ->
                        val seen = mutableSetOf<String>()
                        tags.forEachIndexed { i, t ->
                            val s = (t as? JsonPrimitive)?.takeIf { it.isString }?.content
                            if (s == null) err("$path.tags[$i]", "must be a string")
                            else { if (s.length > 40) err("$path.tags[$i]", "longer than 40 characters"); if (!seen.add(s)) err("$path.tags", "duplicate tag \"$s\"") }
                        }
                    }
                }
                "shortcut" -> {
                    keys(o, path, common + setOf("package", "shortcutId", "profile"), setOf("id", "type", "package", "shortcutId"))
                    str(o, "package", path, re = PKG)
                    str(o, "shortcutId", path, max = 256, min = 1)
                    profile(o, path)
                }
                "folder" -> {
                    keys(o, path, common + "items", setOf("id", "type", "items"))
                    if (ctx == Ctx.FOLDER) err(path, "folders cannot be nested")
                    else arr(o, "items", path, max = 48)?.forEachIndexed { i, it -> item(it, "$path.items[$i]", Ctx.FOLDER) }
                }
                null -> { err("$path.type", if ("type" in o) "must be a string" else "missing required \"type\""); return }
                else -> { err("$path.type", "unknown type \"$type\" (app, shortcut or folder)"); return }
            }
            if ("id" in o) id(o, path)
            str(o, "label", path, max = 60)
            int(o, "x", path, 0, 11); int(o, "y", path, 0, 11)
            when (ctx) {
                Ctx.PAGE -> { if ("x" !in o) err(path, "page items need \"x\""); if ("y" !in o) err(path, "page items need \"y\"") }
                Ctx.FOLDER, Ctx.DOCK -> if ("x" in o || "y" in o) err(path, "x/y are not allowed in ${if (ctx == Ctx.DOCK) "the dock" else "folders"}")
            }
        }

        fun profile(o: JsonObject, path: String) {
            str(o, "profile", path)?.let { if (it != "personal" && it != "work") err("$path.profile", "must be personal or work") }
        }

        fun drawer(e: JsonElement) {
            val o = obj(e, "drawer") ?: return
            keys(o, "drawer", setOf("sort", "hidden", "rules"), emptySet(), xKeys = false)
            str(o, "sort", "drawer")?.let { if (it != "label" && it != "installTime") err("drawer.sort", "must be label or installTime") }
            arr(o, "hidden", "drawer")?.let { h ->
                val seen = mutableSetOf<String>()
                h.forEachIndexed { i, p ->
                    val s = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (s == null || !PKG.matches(s) || s.length > Limits.MAX_STRING) err("drawer.hidden[$i]", "must be a package name (at most ${Limits.MAX_STRING} characters)")
                    else if (!seen.add(s)) err("drawer.hidden", "duplicate \"$s\"")
                }
            }
            arr(o, "rules", "drawer", max = 64)?.forEachIndexed { i, r ->
                val rp = "drawer.rules[$i]"
                val ro = obj(r, rp) ?: return@forEachIndexed
                keys(ro, rp, setOf("id", "match", "group"), setOf("id", "match", "group"), xKeys = false)
                if ("id" in ro) id(ro, rp)
                str(ro, "group", rp, max = 40, min = 1)
                ro["match"]?.let { m ->
                    val mo = obj(m, "$rp.match") ?: return@let
                    keys(mo, "$rp.match", setOf("packages", "packagePrefix", "tag", "category"), emptySet(), xKeys = false)
                    if (mo.isEmpty()) err("$rp.match", "needs at least one condition")
                    arr(mo, "packages", "$rp.match")?.forEachIndexed { j, p ->
                        val s = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s == null || !PKG.matches(s) || s.length > Limits.MAX_STRING) err("$rp.match.packages[$j]", "must be a package name (at most ${Limits.MAX_STRING} characters)")
                    }
                    str(mo, "packagePrefix", "$rp.match", max = 128)
                    str(mo, "tag", "$rp.match", max = 40)
                    str(mo, "category", "$rp.match")?.let { if (it !in CATEGORIES) err("$rp.match.category", "unknown category \"$it\"") }
                }
            }
        }
    }
}
