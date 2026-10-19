package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Profile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #54: .configlauncher/apps.json for agents. */
class AppsJsonTest {
    @get:Rule val tmp = TemporaryFolder()
    private val maps = InstalledApp("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", "Maps", installTime = 1_700_000_000_000)
    private val calc = InstalledApp("com.android.calculator2", "com.android.calculator2.Calculator", "Calculator")
    private val teams = InstalledApp("com.microsoft.teams", "com.microsoft.teams.Main", "Teams", Profile.WORK)

    @Test fun entriesSortedWithFieldsAndWorkOnlyWhenIncluded() {
        val e = AppsJson.entries(listOf(maps, teams, calc), includeWork = false)
        assertEquals(listOf("Calculator", "Maps"), e.map { it["label"]!!.jsonPrimitive.content })
        assertEquals("com.google.android.maps.MapsActivity", e[1]["activity"]!!.jsonPrimitive.content)
        assertEquals("personal", e[1]["profile"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20Z", e[1]["installTime"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, e[0]["installTime"])
        assertTrue(AppsJson.entries(listOf(teams), includeWork = true).single()["profile"]!!.jsonPrimitive.content == "work")
    }

    @Test fun hiddenAppsLeftOutUnlessIncluded() {   // #72
        val hidden = setOf(maps.packageName)
        assertEquals(listOf("Calculator"), AppsJson.entries(listOf(maps, calc), false, hidden).map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("Calculator", "Maps"), AppsJson.entries(listOf(maps, calc), false, hidden, includeHidden = true).map { it["label"]!!.jsonPrimitive.content })
    }

    @Test fun engineRewritesAppsJsonWhenHiddenListOrSettingChanges() = runBlocking {   // #72
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir)
        src.file.writeText("""{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}""")
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0); e.poll(force = true)
        val f = File(dir, ".configlauncher/apps.json")
        fun labels() = Json.parseToJsonElement(f.readText()).jsonObject["apps"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assertTrue(e.writeApps(listOf(maps, calc)))
        assertEquals(listOf("Calculator", "Maps"), labels())
        e.edit(dev.nasawa.configlauncher.model.Edit.Hide(maps.packageName))
        assertTrue("hidden list changed", e.writeApps(listOf(maps, calc)))
        assertEquals(listOf("Calculator"), labels())
        assertTrue("setting on", e.writeApps(listOf(maps, calc), includeHidden = true))
        assertEquals(listOf("Calculator", "Maps"), labels())
        assertFalse("nothing changed", e.writeApps(listOf(maps, calc), includeHidden = true))
    }

    @Test fun engineWritesSidecarOnlyOnChange() = runBlocking {
        val dir = tmp.newFolder()
        val e = ConfigEngine(LocalFileSource(dir), LastGoodStore(tmp.newFolder()), stabilityMs = 0)
        assertTrue(e.writeApps(listOf(maps)))
        val f = File(dir, ".configlauncher/apps.json")
        val doc = Json.parseToJsonElement(f.readText()).jsonObject
        assertTrue(doc.containsKey("generated"))
        assertEquals("Maps", doc["apps"]!!.jsonArray.single().jsonObject["label"]!!.jsonPrimitive.content)
        assertFalse("unchanged list: no rewrite", e.writeApps(listOf(maps)))
        assertTrue(e.writeApps(listOf(maps, calc)))
        assertEquals(2, Json.parseToJsonElement(f.readText()).jsonObject["apps"]!!.jsonArray.size)
        assertFalse("never touches launcher.json", File(dir, "launcher.json").exists())
    }

    @Test fun appOnlyModeWritesNothing() = runBlocking {
        val dir = tmp.newFolder()
        val e = ConfigEngine(LocalFileSource(dir, sidecars = false), LastGoodStore(tmp.newFolder()), stabilityMs = 0)
        assertFalse(e.writeApps(listOf(maps)))
        assertFalse(File(dir, ".configlauncher").exists())
    }
}
