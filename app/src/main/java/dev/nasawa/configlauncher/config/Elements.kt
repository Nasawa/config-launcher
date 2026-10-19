package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #79 generic element plumbing (docs/design/title-bar.md §3, §6): root `elements[]`, `pages[].overrides`, and the
 * forward-compatibility rules that keep schemaVersion 1.
 *
 * - Every element has `id` (item ID pattern, unique file-wide), `type` and optional `requires` (feature level).
 * - An element whose `requires` is above [FEATURE_LEVEL], or whose `type` isn't in [types], is **skipped with a warning**:
 *   not validated further, not shown, kept verbatim on every write.
 * - A known type validates strictly (unknown keys are errors) through its [ElementType].
 *
 * Feature level 1 was the container release (no element type). Level 2 adds the title bar ([TitleBar]). Level 3 is
 * reserved for the hosted `appWidget` (stage 2, not built). Level 4 adds the dashboard `card` ([Card], #137).
 */
object Elements {
    const val FEATURE_LEVEL = 4
    val TYPE = Regex("^[a-zA-Z][a-zA-Z0-9]{0,31}$")
    /** Grid-placement base block (docs/research/widgets.md §5.1). An element has these or `anchor`, never both. */
    val PLACEMENT = listOf("page", "x", "y", "w", "h")

    data class Placement(val page: String, val x: Int, val y: Int, val w: Int, val h: Int) {
        fun cells(): Set<Pair<Int, Int>> = (y until y + h).flatMap { cy -> (x until x + w).map { it to cy } }.toSet()
    }

    /** The element's grid placement, or null for an anchored (or malformed, already rejected) one. */
    fun placement(o: JsonObject): Placement? {
        fun i(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
        return Placement(o.str("page") ?: return null, i("x") ?: return null, i("y") ?: return null, i("w") ?: return null, i("h") ?: return null)
    }

    /**
     * Stage 1 cell reservation: cells on [pageId] taken by grid-placed elements, **shown or skipped** (unknown type,
     * `requires` too high, `enabled: false`), so no edit, drop or merge puts an app there.
     */
    fun reserved(c: LauncherConfig, pageId: String): Set<Pair<Int, Int>> =
        c.elements.orEmpty().mapNotNull(::placement).filter { it.page == pageId }.flatMap { it.cells() }.toSet()

    /** Pages that hold a grid-placed element: never "empty", so leaving edit mode doesn't drop them. */
    fun pagesWithElements(c: LauncherConfig): Set<String> = c.elements.orEmpty().mapNotNull { placement(it)?.page }.toSet()

    /** One element type. [validate] runs only for elements this launcher can show (strict: unknown keys are errors). */
    interface ElementType {
        val name: String
        /** Feature level that introduced the type. */
        val level: Int
        fun validate(o: JsonObject, path: String, c: Validator.Checks)
        /** At most one shown element of this type per file. */
        val single: Boolean get() = false
        /** `pages[].overrides[<id>]` for an element of this type (enabled is already checked). */
        fun validateOverride(o: JsonObject, path: String, c: Validator.Checks) {}
        /** Load warnings for a shown, valid element (e.g. low contrast). */
        fun warnings(o: JsonObject, path: String): List<String> = emptyList()
        /** #137: a capability kind this launcher doesn't know: the element is skipped (cells kept) with a warning. */
        fun unknownCapability(o: JsonObject): String? = null
        /** Canonical key order for writing; default verbatim. */
        fun canonical(o: JsonObject): JsonObject = o
    }

    /** Known types by name. */
    val types: Map<String, ElementType> = listOf<ElementType>(TitleBar, Card).associateBy { it.name }

    sealed interface Skip {
        data class UnknownType(val type: String) : Skip
        data class TooNew(val requires: Long) : Skip
        /** #137: a `capabilities[].kind` this launcher doesn't know (dynamic-widgets.md §4 "Enforcement"). */
        data class UnknownCapability(val kind: String) : Skip
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.requires() = (this["requires"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /** Why [o] is skipped, or null when this launcher shows it. `requires` is checked first (it says "update the app"). */
    fun skip(o: JsonObject): Skip? {
        o.requires()?.let { if (it > FEATURE_LEVEL) return Skip.TooNew(it) }
        val type = o.str("type") ?: return null
        val t = types[type] ?: return Skip.UnknownType(type)
        return t.unknownCapability(o)?.let { Skip.UnknownCapability(it) }
    }

    /** The known type for [o], if it is shown. */
    fun typeOf(o: JsonObject): ElementType? = if (skip(o) == null) o.str("type")?.let { types[it] } else null

    /** Elements this launcher shows, in file order. */
    fun shown(c: LauncherConfig): List<JsonObject> = c.elements.orEmpty().filter { skip(it) == null }

    fun canonical(o: JsonObject): JsonObject = typeOf(o)?.canonical(o) ?: o

    /**
     * #125: warnings only the phone can check (theme-colour contrast with the current theme). [theme] maps a theme
     * token to `#RRGGBB`. Not mirrored by validate.py, which says so instead.
     */
    fun phoneWarnings(c: LauncherConfig, dark: Boolean, theme: (String) -> String?): List<String> =
        c.elements.orEmpty().flatMapIndexed { i, e ->
            if (skip(e) == null && typeOf(e) == TitleBar) TitleBar.phoneWarnings(e, "elements[$i]", dark, theme) else emptyList()
        }

    /**
     * Load warnings (ux.md §15.1): skipped elements and overrides naming no element. They never reject the file;
     * they go to status.json `warnings` and the error detail view's Warnings section.
     */
    fun warnings(c: LauncherConfig): List<String> {
        val out = mutableListOf<String>()
        c.elements.orEmpty().forEachIndexed { i, e ->
            when (val s = skip(e)) {
                is Skip.TooNew -> out += "elements[$i] needs feature level ${s.requires}, but this Config Launcher supports up to $FEATURE_LEVEL, so it was skipped. It stays in the file. Update the app to show it."
                is Skip.UnknownType -> out += "elements[$i]: type \"${s.type}\" isn't supported by this Config Launcher, so it was skipped. It stays in the file."
                is Skip.UnknownCapability -> out += "elements[$i]: capability kind \"${s.kind}\" isn't supported by this Config Launcher, so the widget was skipped. It stays in the file. Update the app, or check the kind for a typo."
                null -> typeOf(e)?.let { out += it.warnings(e, "elements[$i]") }
            }
        }
        val ids = c.elements.orEmpty().mapNotNull { it.str("id") }.toSet()
        c.pages.forEachIndexed { j, p ->
            p.overrides?.keys?.forEach { k -> if (k !in ids) out += "pages[$j].overrides: \"$k\" doesn't match any element, so it was ignored." }
        }
        return out
    }
}
