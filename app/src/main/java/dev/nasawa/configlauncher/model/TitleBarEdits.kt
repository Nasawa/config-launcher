package dev.nasawa.configlauncher.model

import dev.nasawa.configlauncher.config.Elements
import dev.nasawa.configlauncher.config.TitleBar
import kotlinx.serialization.json.*

/**
 * #79 phone-side title bar edits (title-bar.md §8, ux.md §15): on/off and the slot preset only. Everything else in the
 * element (style, templates the picker can't represent, overrides, x- keys) stays as it is in the file.
 */
object TitleBarEdits {
    /** ux.md §15: what turning the bar on writes when the file has none. */
    fun preset(id: String = "t-main") = buildJsonObject {
        put("id", id); put("type", TitleBar.name); put("anchor", "top")
        putJsonObject("content") { putJsonObject("slots") { put("center", "{page}") } }
    }

    enum class Choice(val template: String?) { PAGE("{page}"), CLOCK("{time}"), DATE("{date}"), NONE(null), CUSTOM(null) }

    data class SlotState(val choice: Choice, val raw: String? = null)

    sealed interface State {
        /** No title bar in the file: turning it on adds the preset. */
        data object Absent : State
        /** A title bar this launcher can't show (`requires` too high): the phone leaves it alone. */
        data object Unavailable : State
        data class Present(val id: String, val on: Boolean, val slots: Map<TitleBarModel.Slot, SlotState>) : State
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun state(c: LauncherConfig): State {
        val els = c.elements.orEmpty().filter { it.str("type") == TitleBar.name }
        val shown = els.firstOrNull { Elements.typeOf(it) === TitleBar }
        if (shown == null) return if (els.isEmpty()) State.Absent else State.Unavailable
        val on = (shown["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: true
        val slots = (shown["content"] as? JsonObject)?.get("slots") as? JsonObject
        return State.Present(shown.str("id")!!, on, TitleBarModel.Slot.entries.associateWith { s -> slotState(slots?.str(s.key)) })
    }

    fun slotState(t: String?): SlotState = when (t) {
        null, "" -> SlotState(Choice.NONE)
        "{page}", "pageTitle" -> SlotState(Choice.PAGE)
        "{time}", "clock" -> SlotState(Choice.CLOCK)
        "{date}", "date" -> SlotState(Choice.DATE)
        else -> SlotState(Choice.CUSTOM, t)
    }

    /** The edit for the on/off switch, or null when there's nothing the phone may do. */
    fun toggle(c: LauncherConfig, on: Boolean): Edit? = when (val s = state(c)) {
        State.Absent -> if (on) Edit.AddElement(preset(freeId(c))) else null
        State.Unavailable -> null
        is State.Present -> when {
            s.on == on -> null
            on -> Edit.SetElement(s.id, listOf("enabled"), null)            // default true is omitted
            else -> Edit.SetElement(s.id, listOf("enabled"), JsonPrimitive(false))
        }
    }

    /** Picking [choice] for [slot] (one write; `None` removes the slot). [Choice.CUSTOM] can't be picked. */
    fun pick(id: String, slot: TitleBarModel.Slot, choice: Choice): Edit? =
        if (choice == Choice.CUSTOM) null
        else Edit.SetElement(id, listOf("content", "slots", slot.key), choice.template?.let(::JsonPrimitive))

    private fun freeId(c: LauncherConfig): String {
        val taken = c.elements.orEmpty().mapNotNull { it.str("id") }.toSet() + c.allItems().map { it.id } + c.pages.map { it.id } +
            c.drawer?.rules.orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }
        return generateSequence(1) { it + 1 }.map { if (it == 1) "t-main" else "t-main-$it" }.first { it !in taken }
    }
}
