package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.*

/** Built-in fallback: one empty home page, everything else on the Unsorted page so the prototype shows apps. */
object DefaultLayout {
    val config = LauncherConfig(
        schemaRef = "https://github.com/Nasawa/config-launcher/schema/launcher-config.v1.schema.json",
        revision = 0,
        grid = Grid(4, 5),
        pages = listOf(Page("home", "Home", emptyList())),
        dock = emptyList(),
        unsorted = Unsorted(UnsortedMode.PAGE, "Unsorted"),
    )
}
