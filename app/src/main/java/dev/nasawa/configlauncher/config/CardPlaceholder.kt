package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.consent.Consent
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #257 (N5): in a release build, a shown card keeps its cells but isn't drawn. Debug still shows the consent
 * tile and live target ([dev.nasawa.configlauncher.BuildConfig.CARDS_LIVE]). 1.1 removes the flag when #140
 * and #141 land.
 */
object CardPlaceholder {
    data class Tile(val id: String, val placement: Elements.Placement, val title: String?)

    /** Same hide rules as [dev.nasawa.configlauncher.ui.ConsentTile]: no title at 1×1 or 2×1, no body at 1×1. */
    fun showTitle(w: Int, h: Int) = !(h == 1 && w <= 2)
    fun showMessage(w: Int, h: Int) = !(w == 1 && h == 1)


    /** status.json / Warnings list, same family as the "can't show" lines in [Elements.warnings]. */
    fun warning(path: String, id: String) =
        "$path: card \"$id\" isn't shown in this version of Reflow Launcher. It stays in the file."

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Shown, non-skipped cards on [pageId], in file order. Skipped cards (unknown kind, `requires` too high)
     * keep the "Update Reflow Launcher to show this" tile.
     */
    fun tiles(c: LauncherConfig, pageId: String): List<Tile> = c.elements.orEmpty().mapNotNull { e ->
        if (Elements.typeOf(e) != Card) return@mapNotNull null
        val id = e.str("id") ?: return@mapNotNull null
        val p = Elements.placement(e)?.takeIf { it.page == pageId } ?: return@mapNotNull null
        Tile(id, p, e.str("title")?.takeIf { it.isNotBlank() })
    }

    fun warnings(c: LauncherConfig): List<String> = Consent.cards(c).map { (path, e) -> warning(path, e.str("id")!!) }
}
