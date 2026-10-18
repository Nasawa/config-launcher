package dev.nasawa.configlauncher.model

/** A launchable activity on the device, Android-free so reconciliation is JVM-testable. */
data class InstalledApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val profile: Profile = Profile.PERSONAL,
    /** Work profile is paused (quiet mode): shown greyed; tapping asks the system to unpause (OQ-30). */
    val paused: Boolean = false,
) {
    val key: String get() = "${profile.wire}:$packageName/$activityName"
}

/** An item with install state attached. [app] is null when the target is not installed (greyed out, never deleted). */
data class ResolvedItem(
    val item: Item,
    val app: InstalledApp?,
    val children: List<ResolvedItem> = emptyList(),
    /** Work item while "Include work apps in config" is off (§17 Q12): greyed, kept, not launchable. */
    val workDisabled: Boolean = false,
) {
    val missing: Boolean get() = item !is FolderItem && (app == null || workDisabled)
    val title: String get() = item.label ?: app?.label ?: (item as? AppItem)?.packageName ?: (item as? ShortcutItem)?.shortcutId ?: "Folder"
}

data class ResolvedPage(val id: String, val title: String?, val items: List<ResolvedItem>, val synthetic: Boolean = false)

data class ResolvedLayout(
    val grid: Grid,
    val pages: List<ResolvedPage>,
    val dock: List<ResolvedItem>,
    /** Installed apps placed nowhere in the config (minus drawer.hidden). */
    val unsorted: List<InstalledApp>,
    /** Drawer: every installed app, including placed ones, minus drawer.hidden (§17 b). */
    val drawer: List<InstalledApp>,
    val mode: UnsortedMode,
)

object Reconcile {
    const val UNSORTED_PAGE_ID = "unsorted"

    fun resolve(config: LauncherConfig, installed: List<InstalledApp>, includeWork: Boolean = false): ResolvedLayout {
        val byPkg = installed.groupBy { it.packageName to it.profile }

        fun match(pkg: String, activity: String?, profile: Profile?): InstalledApp? {
            val list = byPkg[pkg to (profile ?: Profile.PERSONAL)] ?: return null
            return if (activity == null) list.first() else list.firstOrNull { it.activityName == activity }
        }

        val placed = mutableSetOf<String>()
        fun res(i: Item): ResolvedItem = when (i) {
            is AppItem -> ResolvedItem(i, match(i.packageName, i.activity, i.profile)?.also { placed += it.key },
                workDisabled = i.profile == Profile.WORK && !includeWork)
            // Shortcuts resolve to their owning app for the icon; the app itself still counts as unplaced.
            is ShortcutItem -> ResolvedItem(i, byPkg[i.packageName to (i.profile ?: Profile.PERSONAL)]?.first(),
                workDisabled = i.profile == Profile.WORK && !includeWork)
            is FolderItem -> ResolvedItem(i, null, i.items.map { res(it) })
        }

        val pages = config.pages.map { p -> ResolvedPage(p.id, p.title, p.items.map { res(it) }) }
        val dock = config.dock.orEmpty().map { res(it) }
        val hidden = config.drawer?.hidden.orEmpty().toSet()
        val drawer = installed.filter { it.packageName !in hidden }.sortedBy { it.label.lowercase() }
        val unsorted = drawer.filter { it.key !in placed }

        val mode = config.unsortedMode
        val allPages = if (mode == UnsortedMode.PAGE && unsorted.isNotEmpty()) {
            val g = config.effectiveGrid
            val items = unsorted.mapIndexed { n, app ->
                ResolvedItem(AppItem("u-$n", app.packageName, app.activityName, app.profile.takeIf { it != Profile.PERSONAL },
                    x = n % g.columns, y = n / g.columns), app)
            }
            pages + ResolvedPage(UNSORTED_PAGE_ID, config.unsorted?.pageTitle ?: "Unsorted", items, synthetic = true)
        } else pages
        return ResolvedLayout(config.effectiveGrid, allPages, dock, unsorted, drawer, mode)
    }
}
