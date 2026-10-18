package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/**
 * Hand-written validator mirroring schema/launcher-config.v1.schema.json (ARCHITECTURE.md §2),
 * plus semantic rules the schema cannot express (AQ-3): unique IDs, cells inside the grid,
 * no overlapping cells. Errors are path-based, e.g. "pages[0].items[2].x: 5 is outside the grid".
 */
object Validator {
    private val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val CLS = Regex("^[A-Za-z0-9_.$]+$")
    private val CATEGORIES = setOf("game", "audio", "video", "image", "social", "news", "maps", "productivity", "accessibility", "undefined")

    fun validate(root: JsonElement): List<String> = Run().apply { root(root) }.errors

    private enum class Ctx { PAGE, FOLDER, DOCK }

    private class Run {
        val errors = mutableListOf<String>()
        val ids = mutableMapOf<String, String>()
        var columns = 4
        var rows = 5

        fun err(path: String, msg: String) { errors += if (path.isEmpty()) msg else "$path: $msg" }

        fun obj(e: JsonElement?, path: String): JsonObject? =
            e as? JsonObject ?: null.also { err(path, "must be an object") }

        fun keys(o: JsonObject, path: String, allowed: Set<String>, required: Set<String>, xKeys: Boolean = true) {
            for (k in o.keys) if (k !in allowed && !(xKeys && k.startsWith("x-"))) err(path, "unknown key \"$k\"")
            for (k in required) if (k !in o) err(path, "missing required \"$k\"")
        }

        fun str(o: JsonObject, k: String, path: String, max: Int? = null, min: Int = 0, re: Regex? = null): String? {
            val v = o[k] ?: return null
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (s == null) { err("$path.$k", "must be a string"); return null }
            if (max != null && s.length > max) err("$path.$k", "longer than $max characters")
            if (s.length < min) err("$path.$k", "must not be empty")
            if (re != null && !re.matches(s)) err("$path.$k", "\"$s\" is not valid")
            return s
        }

        fun int(o: JsonObject, k: String, path: String, min: Long, max: Long? = null): Long? {
            val v = o[k] ?: return null
            val p = v as? JsonPrimitive
            val n = if (p != null && !p.isString) p.content.toLongOrNull() else null
            if (n == null) { err("$path.$k", "must be an integer"); return null }
            if (n < min || (max != null && n > max)) err("$path.$k", "$n is out of range ${min}..${max ?: "∞"}")
            return n
        }

        fun arr(o: JsonObject, k: String, path: String, max: Int? = null, min: Int = 0): JsonArray? {
            val v = o[k] ?: return null
            val a = v as? JsonArray ?: run { err("$path.$k", "must be an array"); return null }
            if (max != null && a.size > max) err("$path.$k", "has ${a.size} entries, at most $max allowed")
            if (a.size < min) err("$path.$k", "needs at least $min entries")
            return a
        }

        fun root(e: JsonElement) {
            val o = obj(e, "") ?: return
            keys(o, "", setOf("\$schema", "schemaVersion", "revision", "grid", "pages", "dock", "drawer", "unsorted"), setOf("schemaVersion", "pages"))
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

        fun page(e: JsonElement, path: String) {
            val o = obj(e, path) ?: return
            keys(o, path, setOf("id", "title", "items"), setOf("id", "items"))
            id(o, path)
            str(o, "title", path, max = 40)
            val cells = mutableMapOf<Pair<Long, Long>, String>()
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
            val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val common = setOf("id", "type", "label", "x", "y")
            when (type) {
                "app" -> {
                    keys(o, path, common + setOf("package", "activity", "profile", "tags"), setOf("id", "type", "package"))
                    str(o, "package", path, re = PKG)
                    str(o, "activity", path, re = CLS)
                    profile(o, path)
                    arr(o, "tags", path)?.let { tags ->
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
                    if (s == null || !PKG.matches(s)) err("drawer.hidden[$i]", "must be a package name")
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
                        if (s == null || !PKG.matches(s)) err("$rp.match.packages[$j]", "must be a package name")
                    }
                    str(mo, "packagePrefix", "$rp.match", max = 128)
                    str(mo, "tag", "$rp.match", max = 40)
                    str(mo, "category", "$rp.match")?.let { if (it !in CATEGORIES) err("$rp.match.category", "unknown category \"$it\"") }
                }
            }
        }
    }
}
