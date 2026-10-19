package dev.nasawa.configlauncher.model

import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.Template
import dev.nasawa.configlauncher.config.TitleBar
import kotlinx.serialization.json.*

/**
 * #79: what the title bar shows on one page (title-bar.md §4, §7; ux.md §15). Pure, so it's unit-tested.
 * The file is already valid here. A page override replaces the slots and style keys it names; `""` empties a slot.
 * Null means no bar on that page (none in the file, skipped, or `enabled: false`): the §1.3 page label shows instead.
 */
object TitleBarModel {
    enum class Slot(val key: String) { START("start"), CENTER("center"), END("end") }
    enum class Align { START, CENTER, END }

    sealed interface Backdrop {
        data object Auto : Backdrop
        data object None : Backdrop
        data object Scrim : Backdrop
        data class Custom(val color: String, val opacity: Int, val radius: Int) : Backdrop
    }

    /** [light] / [dark]: the colour (token or #RRGGBB) used in the light and dark system theme. */
    data class Colors(val light: String, val dark: String)

    data class Style(
        val sizeSp: Float = 14f,
        val weight: Int = 400,
        val color: Colors = Colors("light", "light"),
        val align: Map<Slot, Align> = mapOf(Slot.START to Align.START, Slot.CENTER to Align.CENTER, Slot.END to Align.END),
        val paddingH: Int = 16,
        val paddingV: Int = 4,
        val backdrop: Backdrop = Backdrop.Auto,
    )

    data class Bar(val id: String, val slots: Map<Slot, List<Template.Part>>, val style: Style)

    fun forPage(c: LauncherConfig, pageId: String?): Bar? {
        val el = Elements.shown(c).firstOrNull { Elements.typeOf(it) === TitleBar } ?: return null
        val id = el.str("id") ?: return null
        val ov = c.pages.firstOrNull { it.id == pageId }?.overrides?.get(id) as? JsonObject
        val enabled = ov?.bool("enabled") ?: el.bool("enabled") ?: true
        if (!enabled) return null
        val slotsJson = merge(el.obj("content")?.obj("slots"), ov?.obj("content")?.obj("slots"))
        val slots = Slot.entries.mapNotNull { s ->
            val t = slotsJson?.str(s.key)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            (Template.parse(t) as? Template.Ok)?.let { s to it.parts }
        }.toMap()
        return Bar(id, slots, style(merge(el.obj("style"), ov?.obj("style"))))
    }

    private fun merge(base: JsonObject?, over: JsonObject?): JsonObject? =
        if (over == null) base else JsonObject((base ?: JsonObject(emptyMap())) + over)

    fun style(o: JsonObject?): Style {
        if (o == null) return Style()
        val d = Style()
        val size = (o["size"] as? JsonPrimitive)?.let { p -> if (p.isString) TitleBar.SIZES[p.content] else p.content.toDoubleOrNull() }
        val weight = (o["weight"] as? JsonPrimitive)?.let { p -> if (p.isString) TitleBar.WEIGHTS[p.content] else p.content.toIntOrNull() }
        val color = when (val v = o["color"]) {
            is JsonObject -> Colors(v.str("light") ?: "light", v.str("dark") ?: "light")
            is JsonPrimitive -> Colors(v.content, v.content)
            else -> d.color
        }
        val align = o.obj("align")?.let { a ->
            d.align + Slot.entries.mapNotNull { s -> a.str(s.key)?.let { v -> s to Align.valueOf(v.uppercase()) } }
        } ?: d.align
        val pad = o.obj("padding")
        val backdrop = when (val b = o["backdrop"]) {
            is JsonObject -> Backdrop.Custom(b.str("color") ?: "#000000", b.int("opacity") ?: 100, b.int("radius") ?: 0)
            is JsonPrimitive -> when (b.content) { "none" -> Backdrop.None; "scrim" -> Backdrop.Scrim; else -> Backdrop.Auto }
            else -> Backdrop.Auto
        }
        return Style(size?.toFloat() ?: d.sizeSp, weight ?: d.weight, color, align,
            pad?.int("horizontal") ?: d.paddingH, pad?.int("vertical") ?: d.paddingV, backdrop)
    }

    /** Token values for one page; time and date are formatted by the caller (locale, 12/24 h, spoken form). */
    data class Values(val pageName: String, val pageNumber: Int, val pageCount: Int, val time: (String?) -> String, val date: (String?) -> String)

    fun text(parts: List<Template.Part>, v: Values): String = Template.expand(parts) { t ->
        when (t.name) {
            "page" -> v.pageName
            "pageNumber" -> v.pageNumber.toString()
            "pageCount" -> v.pageCount.toString()
            "time" -> v.time(t.pattern)
            else -> v.date(t.pattern)
        }
    }

    fun hasPage(parts: List<Template.Part>) = parts.any { it is Template.Tok && it.name == "page" }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
}
