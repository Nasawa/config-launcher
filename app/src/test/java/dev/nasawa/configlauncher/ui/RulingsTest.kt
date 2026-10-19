package dev.nasawa.configlauncher.ui

import dev.nasawa.configlauncher.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** ux.md §14 copy (R3, R8, R9, R10) and the Hidden apps rows. */
class RulingsTest {
    private val strings: Map<String, String> by lazy {
        val f = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent.replace("\\\"", "\"").replace("\\'", "'") }
    }

    @Test fun copyMatchesTheRulings() {
        assertEquals("Removed folder \"%1\$s\" from home.", strings["removed_folder"])
        assertEquals("%1\$s hidden from the app list.", strings["app_hidden"])
        assertEquals("Hidden apps", strings["hidden_apps"])
        assertEquals("No hidden apps.", strings["no_hidden_apps"])
        assertEquals("Unhide", strings["action_unhide"])
        assertEquals("Undo", strings["merge_undo"])
        assertEquals("No room on home: all 20 pages are full.", strings["home_full"])
        assertEquals("Couldn't restore \"%1\$s\": all 20 pages are full.", strings["undo_no_room"])
        assertEquals("Choose folder", strings["menu_pick_folder"])
        assertEquals("Reload layout", strings["menu_reload"])
    }

    @Test fun hiddenRowsLabelInstalledAppsAndKeepFileOrder() {
        val c = LauncherConfig(pages = listOf(Page("home")), drawer = Drawer(hidden = listOf("com.gone", "com.cam", "com.cam")))
        val apps = listOf(InstalledApp("com.cam", "M", "Camera", Profile.WORK), InstalledApp("com.cam", "M", "Camera ", Profile.PERSONAL))
        val rows = HiddenApps.rows(c, apps)
        assertEquals(listOf("com.gone", "com.cam"), rows.map { it.packageName })
        assertEquals(listOf("com.gone", "Camera"), rows.map { it.label })
        assertEquals(Profile.PERSONAL, rows[1].app!!.profile)
        assertEquals(emptyList<HiddenRow>(), HiddenApps.rows(c.copy(drawer = null), apps))
    }
}
