package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.*

/** Built-in starter: one empty home page and an empty dock; every app is in the drawer (no `unsorted` key, AF-2). */
object DefaultLayout {
    val config = LauncherConfig(
        schemaRef = "https://github.com/Nasawa/config-launcher/schema/launcher-config.v1.schema.json",
        revision = 0,
        grid = Grid(4, 5),
        pages = listOf(Page("home", "Home", emptyList())),
        dock = emptyList(),
    )
}
