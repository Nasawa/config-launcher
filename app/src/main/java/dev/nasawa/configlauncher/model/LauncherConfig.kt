package dev.nasawa.configlauncher.model

import kotlinx.serialization.json.JsonElement

/** Pure, immutable config model mirroring schema/launcher-config.v1.schema.json. No Android imports. */
typealias Extras = Map<String, JsonElement>

enum class Profile(val wire: String) {
    PERSONAL("personal"), WORK("work");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

data class Grid(val columns: Int = 4, val rows: Int = 5)

sealed interface Item {
    val id: String
    val label: String?
    val x: Int?
    val y: Int?
    val extras: Extras
    fun at(x: Int?, y: Int?): Item = when (this) {
        is AppItem -> copy(x = x, y = y)
        is ShortcutItem -> copy(x = x, y = y)
        is FolderItem -> copy(x = x, y = y)
    }
}

data class AppItem(
    override val id: String,
    val packageName: String,
    val activity: String? = null,
    val profile: Profile? = null,
    override val label: String? = null,
    val tags: List<String>? = null,
    override val x: Int? = null,
    override val y: Int? = null,
    override val extras: Extras = emptyMap(),
) : Item

data class ShortcutItem(
    override val id: String,
    val packageName: String,
    val shortcutId: String,
    val profile: Profile? = null,
    override val label: String? = null,
    override val x: Int? = null,
    override val y: Int? = null,
    override val extras: Extras = emptyMap(),
) : Item

data class FolderItem(
    override val id: String,
    override val label: String? = null,
    val items: List<Item> = emptyList(),
    override val x: Int? = null,
    override val y: Int? = null,
    override val extras: Extras = emptyMap(),
) : Item

data class Page(
    val id: String,
    val title: String? = null,
    val items: List<Item> = emptyList(),
    val extras: Extras = emptyMap(),
)

/** Drawer rules are kept as raw JSON for v1 (not yet interpreted by the UI). */
data class Drawer(
    val sort: String? = null,
    val hidden: List<String>? = null,
    val rules: List<JsonElement>? = null,
)

// AF-2 (§18.5): `unsorted` is type-checked by the Validator and otherwise ignored; it has no model
// field, so the canonical serializer never writes it. Unplaced apps always live in the drawer.

data class LauncherConfig(
    val schemaRef: String? = null,
    val schemaVersion: Int = 1,
    val revision: Long? = null,
    val grid: Grid? = null,
    val pages: List<Page>,
    val dock: List<Item>? = null,
    val drawer: Drawer? = null,
    val extras: Extras = emptyMap(),
) {
    val effectiveGrid: Grid get() = grid ?: Grid()

    /** Every item in the file, including folder children. */
    fun allItems(): Sequence<Item> = sequence {
        fun walk(items: List<Item>): Sequence<Item> = sequence {
            for (i in items) { yield(i); if (i is FolderItem) yieldAll(walk(i.items)) }
        }
        pages.forEach { yieldAll(walk(it.items)) }
        yieldAll(walk(dock.orEmpty()))
    }
}
