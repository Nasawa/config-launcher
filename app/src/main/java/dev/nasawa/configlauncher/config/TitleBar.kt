package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*
import kotlin.math.floor
import kotlin.math.pow

/**
 * #79 part 2: the `titleBar` element (feature level 2), docs/design/title-bar.md §4–§8 and ux.md §15 / §15.1.
 *
 * One bar per file, anchored `top`, three slots (`start`, `center`, `end`) of templates, and a bounded style block.
 * Strict: unknown keys are errors (unlike a skipped element, which is kept verbatim). `scripts/validate.py` mirrors this.
 */
object TitleBar : Elements.ElementType {
    override val name = "titleBar"
    override val level = 2
    override val single = true

    val KEYS = listOf("id", "type", "requires", "enabled", "anchor", "content", "style")
    val SLOTS = listOf("start", "center", "end")
    val STYLE_KEYS = listOf("size", "weight", "color", "align", "padding", "backdrop")
    val COLOR_TOKENS = listOf("light", "dark", "onWallpaper", "primary", "secondary", "onSurface", "surface")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    val SIZES = mapOf("small" to 14.0, "medium" to 18.0, "large" to 24.0)
    val WEIGHTS = mapOf("regular" to 400, "medium" to 500, "bold" to 700)

    const val MAX_TEMPLATE = 120
    const val MAX_TOKENS = 8
    const val MAX_PATTERN = 32

    override fun validate(o: JsonObject, path: String, c: Validator.Checks) {
        c.keys(o, path, KEYS.toSet(), emptySet())
        c.bool(o, "enabled", path)
        c.str(o, "anchor", path)?.let { if (it != "top") c.err("", "$path.anchor must be \"top\".") }
        o["content"]?.let { content(it, "$path.content", c) }
        o["style"]?.let { style(it, "$path.style", c) }
    }

    /** `pages[].overrides[<titleBar id>]`: `content` and `style` follow the same rules as the element's own. */
    override fun validateOverride(o: JsonObject, path: String, c: Validator.Checks) {
        o["content"]?.let { content(it, "$path.content", c) }
        o["style"]?.let { style(it, "$path.style", c) }
    }

    fun content(e: JsonElement, path: String, c: Validator.Checks) {
        val o = c.obj(e, path) ?: return
        c.keys(o, path, setOf("slots"), emptySet(), xKeys = false)
        val slots = o["slots"]?.let { c.obj(it, "$path.slots") } ?: return
        c.keys(slots, "$path.slots", SLOTS.toSet(), emptySet(), xKeys = false)
        for (s in SLOTS) {
            val v = slots[s] ?: continue
            val t = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (t == null) { c.err("$path.slots.$s", "must be a string"); continue }
            Template.check(t)?.let { c.err("", Template.message(it, "$path.slots.$s")) }
        }
    }

    fun style(e: JsonElement, path: String, c: Validator.Checks) {
        val o = c.obj(e, path) ?: return
        c.keys(o, path, STYLE_KEYS.toSet(), emptySet(), xKeys = false)
        o["size"]?.let { v ->
            val p = v as? JsonPrimitive
            val ok = if (p?.isString == true) p.content in SIZES else p != null && p !is JsonNull && p.content.toDoubleOrNull()?.let { it in 10.0..40.0 } == true
            if (!ok) c.err("", "$path.size must be small, medium or large, or a number from 10 to 40.")
        }
        o["weight"]?.let { v ->
            val p = v as? JsonPrimitive
            val ok = if (p?.isString == true) p.content in WEIGHTS else p?.content?.toLongOrNull()?.let { it in 300..800 && it % 100 == 0L } == true
            if (!ok) c.err("", "$path.weight must be regular, medium or bold, or 300 to 800 in steps of 100.")
        }
        o["color"]?.let { v ->
            if (v is JsonObject) {
                c.keys(v, "$path.color", setOf("light", "dark"), emptySet(), xKeys = false)
                if (v.isEmpty()) c.err("", "$path.color needs light or dark.")
                for (k in listOf("light", "dark")) v[k]?.let { color(it, "$path.color.$k", c) }
            } else color(v, "$path.color", c)
        }
        o["align"]?.let { v ->
            val a = c.obj(v, "$path.align") ?: return@let
            c.keys(a, "$path.align", SLOTS.toSet(), emptySet(), xKeys = false)
            for (s in SLOTS) c.str(a, s, "$path.align")?.let { if (it !in SLOTS) c.err("", "$path.align.$s must be start, center or end.") }
        }
        o["padding"]?.let { v ->
            val p = c.obj(v, "$path.padding") ?: return@let
            c.keys(p, "$path.padding", setOf("horizontal", "vertical"), emptySet(), xKeys = false)
            c.int(p, "horizontal", "$path.padding", 0, 32); c.int(p, "vertical", "$path.padding", 0, 24)
        }
        o["backdrop"]?.let { v ->
            if (v is JsonObject) {
                c.keys(v, "$path.backdrop", setOf("color", "opacity", "radius"), setOf("color"), xKeys = false)
                v["color"]?.let { color(it, "$path.backdrop.color", c) }
                c.int(v, "opacity", "$path.backdrop", 0, 100); c.int(v, "radius", "$path.backdrop", 0, 24)
            } else {
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (s !in setOf("auto", "none", "scrim")) c.err("", "$path.backdrop must be auto, none or scrim, or an object with color.")
            }
        }
    }

    private fun color(v: JsonElement, path: String, c: Validator.Checks) {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null || (s !in COLOR_TOKENS && !HEX.matches(s)))
            c.err("", "$path must be #RRGGBB or one of ${COLOR_TOKENS.joinToString(", ")}.")
    }

    // ---- canonical order (title-bar.md §4): id, type, requires, enabled, anchor, content, style, then x- keys

    override fun canonical(o: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (k in KEYS) o[k]?.let { v ->
            out[k] = when (k) {
                "content" -> (v as JsonObject).let { co -> order(co, listOf("slots")) { _, s -> order(s as JsonObject, SLOTS) } }
                "style" -> style(v as JsonObject)
                else -> v
            }
        }
        o.filterKeys { it.startsWith("x-") }.toSortedMap().forEach { (k, v) -> out[k] = v }
        return JsonObject(out)
    }

    private fun style(o: JsonObject) = order(o, STYLE_KEYS) { k, v ->
        when {
            v !is JsonObject -> v
            k == "color" -> order(v, listOf("light", "dark"))
            k == "align" -> order(v, SLOTS)
            k == "padding" -> order(v, listOf("horizontal", "vertical"))
            k == "backdrop" -> order(v, listOf("color", "opacity", "radius"))
            else -> v
        }
    }

    private fun order(o: JsonObject, keys: List<String>, inner: (String, JsonElement) -> JsonElement = { _, v -> v }) =
        JsonObject(LinkedHashMap<String, JsonElement>().apply { for (k in keys) o[k]?.let { put(k, inner(k, it)) } })

    // ---- contrast (title-bar.md §7, ux.md §15): a warning, never an error

    /** Text colours the launcher can know without a theme. Theme tokens (primary, …) aren't measured. */
    val STATIC = mapOf("light" to "#FFFFFF", "onWallpaper" to "#FFFFFF", "dark" to "#1B1B1F")
    const val MIN_CONTRAST = 4.5

    override fun warnings(o: JsonObject, path: String): List<String> =
        (o["style"] as? JsonObject)?.let { styleWarnings(it, path) }.orEmpty()

    /**
     * #118 (Archimedes): the style a page override shows (the element's style with the override's keys on top, as
     * [dev.nasawa.configlauncher.model.TitleBarModel.forPage] merges it), or null when the override doesn't touch
     * colours (`color`, `backdrop`) or hides the bar on that page (`enabled: false`). Then the element's own check already covers that page.
     */
    fun overrideStyle(base: JsonObject, ov: JsonObject): JsonObject? {
        if ((ov["enabled"] as? JsonPrimitive)?.content == "false") return null
        val over = ov["style"] as? JsonObject ?: return null
        if ("color" !in over && "backdrop" !in over) return null
        return JsonObject(((base["style"] as? JsonObject) ?: JsonObject(emptyMap())) + over)
    }

    override fun overrideWarnings(base: JsonObject, ov: JsonObject, path: String): List<String> =
        overrideStyle(base, ov)?.let { styleWarnings(it, path) }.orEmpty()

    private fun styleWarnings(style: JsonObject, path: String): List<String> {
        val ratio = contrast(style) ?: return emptyList()
        if (ratio >= MIN_CONTRAST) return emptyList()
        val tenths = floor(ratio * 10).toLong()
        return listOf("$path.style.color: contrast ${tenths / 10}.${tenths % 10}:1 on the backdrop (4.5:1 recommended). Shown anyway.")
    }

    /** The system-theme colour tokens: known only on the phone (#125). */
    val THEME = setOf("primary", "secondary", "onSurface", "surface")

    /** #125: a phone-only measurement: the ratio and the key holding the theme token (`color`, `color.dark`, `backdrop.color`). */
    data class PhoneContrast(val ratio: Double, val key: String)

    /**
     * #125 (Pragma, ux.md §15): contrast with the theme colours this phone shows right now, for a bar whose text colour
     * (in the current light or dark variant) or custom backdrop colour is a theme token. [theme] maps a token to
     * `#RRGGBB`. Same worst-case-wallpaper rule as [contrast]. Null when no theme token is involved or nothing is
     * measurable. The key names the text colour's key when that's a token, else `backdrop.color`.
     */
    fun phoneContrast(style: JsonObject, dark: Boolean, theme: (String) -> String?): PhoneContrast? {
        val variant = if (dark) "dark" else "light"
        val (text, textKey) = when (val col = style["color"]) {
            null -> "light" to "color"
            is JsonObject -> ((col[variant] as? JsonPrimitive)?.content ?: "light") to "color.$variant"
            else -> (col as JsonPrimitive).content to "color"
        }
        val (bg, opacity) = when (val b = style["backdrop"]) {
            is JsonPrimitive -> if (b.isString && b.content == "scrim") "#000000" to 60L else return null
            is JsonObject -> ((b["color"] as? JsonPrimitive)?.content ?: "#000000") to ((b["opacity"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 100L)
            else -> return null
        }
        val key = when { text in THEME -> textKey; bg in THEME -> "backdrop.color"; else -> return null }
        val resolve = { t: String -> hex(t) ?: if (t in THEME) theme(t)?.takeIf { HEX.matches(it) } else null }
        return PhoneContrast(worst(resolve(bg) ?: return null, opacity, listOf(resolve(text) ?: return null)), key)
    }

    /** #125: the phone-only warning for [phoneContrast]; `checked on this phone` is the marker (no separate key). */
    fun phoneWarnings(o: JsonObject, path: String, dark: Boolean, theme: (String) -> String?): List<String> =
        (o["style"] as? JsonObject)?.let { phoneStyleWarnings(it, path, dark, theme) }.orEmpty()

    fun phoneStyleWarnings(style: JsonObject, path: String, dark: Boolean, theme: (String) -> String?): List<String> {
        val m = phoneContrast(style, dark, theme) ?: return emptyList()
        if (m.ratio >= MIN_CONTRAST) return emptyList()
        val tenths = floor(m.ratio * 10).toLong()
        return listOf("$path.style.${m.key}: contrast ${tenths / 10}.${tenths % 10}:1 on the backdrop (4.5:1 recommended), checked on this phone in ${if (dark) "dark" else "light"} theme. Shown anyway.")
    }

    private fun hex(s: String?): String? = when {
        s == null -> null
        HEX.matches(s) -> s
        else -> STATIC[s]
    }

    /**
     * Lowest text-versus-backdrop ratio over the measurable text colours (both theme variants) and, for a translucent
     * backdrop, both a white and a black wallpaper under it. Null when nothing is measurable (`auto`, `none`, tokens).
     */
    fun contrast(style: JsonObject): Double? {
        val (bg, opacity) = when (val b = style["backdrop"]) {
            is JsonPrimitive -> if (b.isString && b.content == "scrim") "#000000" to 60L else return null
            is JsonObject -> ((b["color"] as? JsonPrimitive)?.content.let(::hex) ?: return null) to
                ((b["opacity"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 100L)
            else -> return null
        }
        val texts = when (val col = style["color"]) {
            null -> listOf("light")
            is JsonObject -> listOf((col["light"] as? JsonPrimitive)?.content ?: "light", (col["dark"] as? JsonPrimitive)?.content ?: "light")
            else -> listOf((col as JsonPrimitive).content)
        }.mapNotNull(::hex)
        if (texts.isEmpty()) return null
        return worst(bg, opacity, texts)
    }

    private fun worst(bg: String, opacity: Long, texts: List<String>): Double {
        val a = opacity / 100.0
        val walls = if (opacity >= 100) listOf("#FFFFFF") else listOf("#FFFFFF", "#000000")
        var min = Double.MAX_VALUE
        for (w in walls) {
            val back = rgb(bg).zip(rgb(w)) { b, wl -> b * a + wl * (1 - a) }
            for (t in texts) min = minOf(min, ratio(luminance(rgb(t)), luminance(back)))
        }
        return min
    }

    private fun rgb(h: String) = listOf(1, 3, 5).map { h.substring(it, it + 2).toInt(16).toDouble() }

    private fun luminance(c: List<Double>): Double {
        val l = c.map { v -> (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) } }
        return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
    }

    private fun ratio(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
