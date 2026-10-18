package dev.nasawa.configlauncher.model

import org.junit.Assert.*
import org.junit.Test

class ReconcileTest {
    private val chrome = InstalledApp("com.android.chrome", "com.google.Main", "Chrome")
    private val calc = InstalledApp("com.calc", "com.calc.Main", "Calculator")
    private val zed = InstalledApp("com.zed", "com.zed.Main", "zed")
    private val workChrome = chrome.copy(profile = Profile.WORK)

    private fun cfg(hidden: List<String>? = null) = LauncherConfig(
        pages = listOf(Page("home", items = listOf(
            AppItem("a", "com.android.chrome", x = 0, y = 0),
            AppItem("gone", "com.uninstalled", x = 1, y = 0),
            FolderItem("f", "F", listOf(AppItem("c", "com.calc", "com.calc.Main")), x = 2, y = 0),
        ))),
        drawer = hidden?.let { Drawer(hidden = it) },
    )

    @Test fun matchesPlacedAndMarksMissing() {
        val r = Reconcile.resolve(cfg(), listOf(chrome, calc, zed))
        val items = r.pages[0].items
        assertEquals(chrome, items[0].app)
        assertTrue(items[1].missing)
        assertEquals(calc, items[2].children[0].app)
        assertEquals(listOf(zed), r.unsorted)
    }

    @Test fun workProfileIsSeparate() {
        val r = Reconcile.resolve(cfg(), listOf(chrome, workChrome))
        assertEquals(listOf(workChrome), r.unsorted)
    }

    @Test fun hiddenExcluded() {
        assertEquals(emptyList<InstalledApp>(), Reconcile.resolve(cfg(hidden = listOf("com.zed")), listOf(zed)).unsorted)
    }

    @Test fun drawerListsAllAppsIncludingPlacedMinusHidden() {
        val r = Reconcile.resolve(cfg(hidden = listOf("com.zed")), listOf(chrome, calc, zed))
        assertEquals(listOf(calc, chrome), r.drawer)
    }

    @Test fun workItemsGreyedWhenOptInOff() {
        val c = LauncherConfig(pages = listOf(Page("p", items = listOf(AppItem("w", "com.android.chrome", profile = Profile.WORK, x = 0, y = 0)))))
        assertTrue(Reconcile.resolve(c, listOf(workChrome)).pages[0].items[0].missing)
        assertFalse(Reconcile.resolve(c, listOf(workChrome), includeWork = true).pages[0].items[0].missing)
    }

    @Test fun unplacedAppsNeverGetAPage() = assertEquals(1, Reconcile.resolve(cfg(), listOf(zed)).pages.size)
}
