package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import dev.nasawa.configlauncher.model.*
import dev.nasawa.configlauncher.ui.LongPress.Step
import org.junit.Assert.*
import org.junit.Test

/** #53: a stationary hold opens the item sheet; hold + move drags; never both, never a launch. */
class LongPressTest {
    private val o = Offset(100f, 100f)

    @Test fun stationaryHoldThenReleaseOpensMenu() {
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.WAIT, lp.next(Offset(103f, 102f), pressed = true))   // jitter under the slop
        assertEquals(Step.MENU, lp.next(Offset(103f, 102f), pressed = false))
        assertFalse(lp.dragging)
    }

    @Test fun holdThenMoveDragsAndDrops() {
        val lp = LongPress.track(o, 8f, canDrag = true)
        assertEquals(Step.START_DRAG, lp.next(Offset(120f, 100f), pressed = true))
        assertEquals(Step.DRAG, lp.next(Offset(100f, 100f), pressed = true))     // moving back doesn't turn it into a menu
        assertEquals(Step.DROP, lp.next(Offset(100f, 100f), pressed = false))
    }

    @Test fun noDragWhereDragIsOff() {   // folder children / drawer tiles
        val lp = LongPress.track(o, 8f, canDrag = false)
        assertEquals(Step.WAIT, lp.next(Offset(300f, 300f), pressed = true))
        assertEquals(Step.MENU, lp.next(Offset(300f, 300f), pressed = false))
    }
}

class ItemSheetModelTest {
    private val maps = InstalledApp("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", "Maps")

    @Test fun showsRealNameAndPackageEvenWithLabelOverride() {
        val m = ItemSheetModel.of(ResolvedItem(AppItem("a", maps.packageName, label = "Bank"), maps))
        assertEquals("Bank", m.title)
        assertEquals("Maps", m.realName)
        assertEquals("com.google.android.apps.maps", m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.APP_INFO, ItemAction.REMOVE), m.actions)
    }

    @Test fun notInstalledHasNoAppInfo() {
        val m = ItemSheetModel.of(ResolvedItem(AppItem("a", "gone.app"), null))
        assertNull(m.realName); assertEquals("gone.app", m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.REMOVE), m.actions)
    }

    @Test fun folderShowsCountAndNoAppInfo() {
        val m = ItemSheetModel.of(ResolvedItem(FolderItem("f", "Work", listOf(AppItem("x", "p.x"), AppItem("y", "p.y"))), null))
        assertEquals(2, m.folderSize); assertNull(m.packageName)
        assertEquals(listOf(ItemAction.EDIT_LAYOUT, ItemAction.REMOVE), m.actions)
    }

    @Test fun shortcutShowsItsPackage() {
        val m = ItemSheetModel.of(ResolvedItem(ShortcutItem("s", maps.packageName, "nav_home", label = "Home"), maps))
        assertEquals(maps.packageName, m.packageName)
        assertTrue(ItemAction.APP_INFO in m.actions)
    }
}

class HomeMenuTest {
    @Test fun emptySpaceMenuMatchesUx() {
        assertEquals(listOf(HomeAction.EDIT_LAYOUT, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER), HomeMenu.actions(1))
        // #99: `Pages` right after `Edit layout`, only with 2 or more pages.
        assertEquals(listOf(HomeAction.EDIT_LAYOUT, HomeAction.PAGES, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER), HomeMenu.actions(2))
        assertEquals(HomeMenu.actions(1), HomeMenu.actions(0))
    }
}

class DrawerMenuTest {
    private val personal = InstalledApp("p.a", "p.a.M", "A")
    private val work = InstalledApp("p.a", "p.a.M", "A", Profile.WORK)

    @Test fun actionsPerUx() {
        assertEquals(listOf(DrawerAction.ADD_TO_HOME, DrawerAction.HIDE, DrawerAction.APP_INFO), DrawerMenu.actions(personal, includeWork = false))
        assertEquals("work apps: Add to home only when included", listOf(DrawerAction.HIDE, DrawerAction.APP_INFO), DrawerMenu.actions(work, includeWork = false))
        assertTrue(DrawerAction.ADD_TO_HOME in DrawerMenu.actions(work, includeWork = true))
    }
}
