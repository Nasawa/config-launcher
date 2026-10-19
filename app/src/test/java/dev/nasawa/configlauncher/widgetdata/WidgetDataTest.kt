package dev.nasawa.configlauncher.widgetdata

import dev.nasawa.configlauncher.consent.ConsentStore
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import dev.nasawa.configlauncher.widgetdata.WidgetDataReader.Reload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #139 S3-D: local data files (dynamic-widgets.md §3), gated on S3-B consent; ux.md §17.9 data states. */
class WidgetDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg cards: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]}],"elements":[${cards.joinToString(",")}]}"""
    private fun card(id: String = "w-1", caps: String = """{"kind":"data.file"},{"kind":"ui.reload"}""", extra: String = "", y: Int = 0) =
        """{"id":"$id","type":"card","requires":4,"page":"home","x":0,"y":$y,"w":2,"h":2,"title":"Anigeek",$extra"capabilities":[$caps],"layouts":{"2x2":[{"type":"text","text":"{data:a}"}]}}"""

    private fun root(s: String) = (WidgetData.parse(s.toByteArray()) as WidgetData.Parsed.Ok).data.root

    @Test fun parseIsStrictAndCapped() {
        assertEquals(WidgetData.Parsed.Bad("the root must be an object"), WidgetData.parse("[1]".toByteArray()))
        assertTrue(WidgetData.parse(byteArrayOf(0xC3.toByte(), 0x28)) is WidgetData.Parsed.Bad)
        assertTrue((WidgetData.parse("""{"a":1,"a":2}""".toByteArray()) as WidgetData.Parsed.Bad).reason.contains("duplicate"))
        assertTrue(WidgetData.parse("[".repeat(100_000).toByteArray()) is WidgetData.Parsed.Bad) // no stack overflow
        val big = ("""{"a":"""" + "x".repeat(WidgetData.MAX_BYTES) + "\"}").toByteArray()
        assertEquals(WidgetData.Parsed.Bad("larger than 256 KiB"), WidgetData.parse(big))
        assertEquals("w-1.json", WidgetData.fileName("w-1"))
        assertNull(WidgetData.fileName("../x"))
    }

    @Test fun lookupAndText() {
        val r = root("""{"monitors":{"summary":"3/3 up","n":3,"ok":true,"none":null},"unhealthy":[{"name":"nas"}],"empty":""}""")
        assertEquals("3/3 up", WidgetData.text(r, "monitors.summary"))
        assertEquals("3", WidgetData.text(r, "monitors.n"))
        assertEquals("true", WidgetData.text(r, "monitors.ok"))
        assertEquals("nas", WidgetData.text(r, "unhealthy[0].name"))
        for (p in listOf("monitors.none", "monitors", "unhealthy[1].name", "nope.x", "empty", "a..b", "unhealthy[x]")) assertEquals(p, "—", WidgetData.text(r, p))
        assertEquals("—", WidgetData.text(null, "a"))
        assertTrue(WidgetData.lookup(r, "unhealthy") is JsonArray)
    }

    @Test fun updatedAtAndStale() {
        val t = WidgetData.updatedAt(root("""{"updatedAt":"2026-10-19T08:38:00-06:00"}"""))!!
        assertEquals(java.time.Instant.parse("2026-10-19T14:38:00Z").toEpochMilli(), t)
        assertNull(WidgetData.updatedAt(root("""{"updatedAt":"yesterday"}""")))
        val c = Json.parseToJsonElement(card(extra = """"data":{"staleAfter":"P1DT2H30M5S"},""")).jsonObject
        assertEquals(((24 + 2) * 3600 + 30 * 60 + 5) * 1000L, WidgetData.staleAfterMs(c))
        assertEquals(WidgetData.DEFAULT_STALE_MS, WidgetData.staleAfterMs(Json.parseToJsonElement(card()).jsonObject))
        assertFalse(WidgetData.isStale(t, 30 * 60_000, t + 30 * 60_000))
        assertTrue(WidgetData.isStale(t, 30 * 60_000, t + 30 * 60_000 + 1))
        assertFalse(WidgetData.isStale(null, 1, Long.MAX_VALUE)) // no updatedAt: never claims stale
    }

    private class Env(val dir: File, val e: ConfigEngine, val consent: ConsentStore, val now: LongArray) {
        fun data(id: String, s: String?) {
            val f = File(dir, ".configlauncher/widgets-data/$id.json")
            if (s == null) f.delete() else { f.parentFile.mkdirs(); f.writeText(s) }
        }
        fun status() = Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject
        fun warnings() = status()["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun env(launcher: String): Env = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir); src.file.writeText(launcher)
        val now = longArrayOf(1_000_000)
        val consent = ConsentStore(tmp.newFolder())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { now[0] }, consent = consent)
        e.poll(force = true)
        Env(dir, e, consent, now)
    }

    @Test fun allowedCardReadsItsOwnFileOnly() = runBlocking {
        val v = env(doc(card(), card(id = "w-2", y = 2)))
        v.data("w-1", """{"a":"one"}"""); v.data("w-2", """{"a":"two"}""")
        v.e.pollWidgets(force = true)
        assertEquals("one", WidgetData.text(v.e.widgetData.value["w-1"]!!.data!!.root, "a"))
        assertEquals("two", WidgetData.text(v.e.widgetData.value["w-2"]!!.data!!.root, "a"))
        // Same size within the same mtime second isn't seen by the stamp; every 5th poll re-reads anyway.
        v.data("w-1", """{"a":"uno"}""")
        v.e.pollWidgets()
        repeat(4) { v.e.pollWidgets() }
        assertEquals("uno", WidgetData.text(v.e.widgetData.value["w-1"]!!.data!!.root, "a"))
    }

    @Test fun noReadsWithoutConsentOrDataFile() = runBlocking {
        // Waiting for OK (source + ui.openUrl), and a card without data.file: neither file is read.
        val v = env(doc(card(caps = """{"kind":"data.file"},{"kind":"ui.openUrl"}""", extra = """"source":"blog.anigeek.com","""),
            card(id = "w-2", caps = """{"kind":"ui.reload"}""", y = 2)))
        v.data("w-1", """{"a":"one"}"""); v.data("w-2", """{"a":"two"}""")
        v.e.pollWidgets(force = true)
        assertTrue(v.e.widgetData.value.isEmpty())
        assertEquals(Reload.Ignored, v.e.reloadWidget("w-1"))
        // Allowed: read. Turned off: forgotten from memory, not read again.
        v.consent.allow("w-1", v.e.consentReport().first { it.id == "w-1" }.lines); v.e.consentChanged()
        assertNotNull(v.e.widgetData.value["w-1"]!!.data)
        v.consent.turnOff("w-1"); v.e.consentChanged()
        assertNull(v.e.widgetData.value["w-1"])
    }

    @Test fun missingFileKeepsLastValuesAndWarns() = runBlocking {
        val v = env(doc(card()))
        v.e.pollWidgets(force = true)
        val first = v.e.widgetData.value["w-1"]!!
        assertNull(first.data); assertTrue(first.problem)
        val w = "elements[0]: widgets-data/w-1.json is missing or unreadable, so the last values are shown."
        assertTrue(v.warnings().contains(w))
        v.data("w-1", """{"a":"one"}"""); v.e.pollWidgets(force = true)
        assertFalse(v.e.widgetData.value["w-1"]!!.problem)
        assertFalse(v.warnings().contains(w))
        v.data("w-1", """{"a":"""); v.e.pollWidgets(force = true)
        assertEquals("one", WidgetData.text(v.e.widgetData.value["w-1"]!!.data!!.root, "a"))
        assertTrue(v.e.widgetData.value["w-1"]!!.problem)
        assertTrue(v.warnings().contains(w))
        v.data("w-1", null); v.e.pollWidgets(force = true)
        assertEquals("one", WidgetData.text(v.e.widgetData.value["w-1"]!!.data!!.root, "a"))
    }

    @Test fun reloadRereadsNowAtMostEveryTwoSeconds() = runBlocking {
        val v = env(doc(card()))
        v.data("w-1", """{"a":"one"}"""); v.e.pollWidgets(force = true)
        v.data("w-1", """{"a":"two"}""")
        assertEquals(Reload.Ignored, v.e.reloadWidget("w-1")) // same instant as the poll
        v.now[0] += 2_000
        assertEquals(Reload.Updated, v.e.reloadWidget("w-1"))
        assertEquals("two", WidgetData.text(v.e.widgetData.value["w-1"]!!.data!!.root, "a"))
        v.now[0] += 2_000
        assertEquals(Reload.Same, v.e.reloadWidget("w-1"))
        // A card without ui.reload can't be reloaded by a tap.
        val n = env(doc(card(caps = """{"kind":"data.file"}""")))
        n.now[0] += 5_000
        assertEquals(Reload.Ignored, n.e.reloadWidget("w-1"))
    }
}
