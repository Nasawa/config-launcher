package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.consent.ConsentStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #159 (ux.md §15 interim Warnings list): the phone's Warnings list shows "the same text as status.json". HomeScreen
 * reads [ConfigEngine.warnings], which the engine sets from the same list it writes to status.json `warnings`,
 * so count, order and text agree: load warnings, then phone-only theme ones, then consent, then widget data.
 */
class WarningsListTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixture = File("../test-fixtures/configs/valid/card-example.json").takeIf { it.isFile }
        ?: File("test-fixtures/configs/valid/card-example.json")

    /** card-example (a `source` + `ui.openUrl` card, so it needs your OK) plus a title bar coloured by a theme token. */
    private fun launcher(): String {
        val o = Json.parseToJsonElement(fixture.readText()).jsonObject
        val bar = Json.parseToJsonElement("""{"id":"t-main","type":"titleBar","style":{"color":"primary","backdrop":"scrim"}}""")
        val elements = JsonArray(o["elements"]!!.jsonArray + bar)
        return JsonObject(o + ("elements" to elements)).toString()
    }

    private fun statusWarnings(dir: File) =
        Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun assertSame(dir: File, e: ConfigEngine): List<String> {
        val status = statusWarnings(dir)
        assertEquals("count", status.size, e.warnings.value.size)
        assertEquals("order and text", status, e.warnings.value)
        return status
    }

    @Test fun phoneListAndStatusJsonAgree() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val consent = ConsentStore(tmp.newFolder())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = consent)
        e.poll(force = true)

        // Grace's repro: the consent warning is in status.json, so it's on the phone list too.
        val loaded = assertSame(dir, e)
        val ok = loaded.indexOfFirst { it.startsWith("elements[0]") && "waiting for approval" in it }
        assertTrue("consent warning listed: $loaded", ok >= 0)

        // Phone-only theme contrast warning: listed, and before the consent one (status.json order).
        e.setPhoneTheme(false, mapOf("primary" to "#303030"))
        val themed = assertSame(dir, e)
        val contrast = themed.indexOfFirst { "checked on this phone" in it }
        val ok2 = themed.indexOfFirst { "waiting for approval" in it }
        assertTrue("contrast warning listed: $themed", contrast >= 0)
        assertTrue("theme warnings come before consent ones: $themed", contrast < ok2)
        assertEquals(loaded.size + 1, themed.size)

        // Allowing the card: the consent warning leaves both lists.
        consent.allow("w-anigeek", e.consentReport().first { it.id == "w-anigeek" }.lines, e.state.value.config); e.consentChanged()
        val allowed = assertSame(dir, e)
        assertTrue(allowed.none { "waiting for approval" in it })

        // Theme fixed: the contrast line clears from both.
        e.setPhoneTheme(false, mapOf("primary" to "#FFFFFF"))
        assertTrue(assertSame(dir, e).none { "checked on this phone" in it })
    }

    @Test fun titleBarFitWarningIsListedOnce() = runBlocking {
        // #167 (ux.md §15.1): the bar's overflow-ladder warning goes through the engine, so it's in status.json and
        // on the phone list exactly once (HomeScreen no longer appends it a second time), and it clears from both.
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = ConsentStore(tmp.newFolder()))
        e.poll(force = true)
        val before = assertSame(dir, e)
        val fit = "elements[1].content.slots.start: the title bar doesn't fit (test)"
        e.setTitleBarFit(fit)
        val with = assertSame(dir, e)
        assertEquals(1, with.count { it == fit })
        assertEquals(before.size + 1, with.size)
        e.setTitleBarFit(null)
        assertEquals(before, assertSame(dir, e))
    }

    @Test fun folderSwitchClearsTheList() = runBlocking {
        // #171 (Grace #168 L1): switching to a folder with no launcher.json yet leaves no warnings from the old one.
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = ConsentStore(tmp.newFolder()))
        e.poll(force = true)
        assertTrue(e.warnings.value.isNotEmpty())
        e.switchSource(LocalFileSource(tmp.newFolder()))
        assertEquals(emptyList<String>(), e.warnings.value)
    }

    @Test fun failedFolderMoveBringsTheListStraightBack() = runBlocking {
        // #173 L3: the write fails, the engine rolls back, and the old folder's list is back at once (no poll).
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = ConsentStore(tmp.newFolder()))
        e.poll(force = true)
        val before = assertSame(dir, e)
        assertTrue(before.isNotEmpty())
        val broken = object : ConfigSource by LocalFileSource(tmp.newFolder()) {
            override fun write(bytes: ByteArray) { throw java.io.IOException("read-only") }
        }
        assertFalse(e.moveTo(broken, useMine = true))
        assertSame(src, e.source)
        assertEquals(before, e.warnings.value)
        assertEquals(before, assertSame(dir, e))
        // And the list keeps following status.json afterwards.
        e.setPhoneTheme(false, mapOf("primary" to "#303030"))
        assertEquals(before.size + 1, assertSame(dir, e).size)
    }

    @Test fun folderMoveListsTheNewFoldersWarnings() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = ConsentStore(tmp.newFolder()))
        e.poll(force = true)
        val to = tmp.newFolder()
        assertTrue(e.moveTo(LocalFileSource(to), useMine = true))
        // Same layout, new folder: the list matches the new folder's status.json.
        e.poll(force = true)
        assertTrue(assertSame(to, e).isNotEmpty())
    }

    @Test fun rejectedFileHasNoWarningsOnEitherSide() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, consent = ConsentStore(tmp.newFolder()))
        e.poll(force = true)
        assertTrue(e.warnings.value.isNotEmpty())
        src.file.writeText("""{"schemaVersion":1,"pages":"""); e.poll(force = true)
        assertEquals(emptyList<String>(), assertSame(dir, e))
    }
}
