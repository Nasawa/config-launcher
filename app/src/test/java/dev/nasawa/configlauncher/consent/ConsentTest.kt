package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.consent.Consent.State
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #138 S3-B: approval ceiling, Turn off / Forget approval, Q-D5 source rules, status.json consent map. */
class ConsentTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg cards: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]}],"elements":[${cards.joinToString(",")}]}"""
    private fun card(id: String = "w-1", caps: String = """{"kind":"data.file"}""", extra: String = "", y: Int = 0, requires: Int = 4) =
        """{"id":"$id","type":"card","requires":$requires,"page":"home","x":0,"y":$y,"w":2,"h":2,"title":"Anigeek LLC",$extra"capabilities":[$caps],"layouts":{"2x2":[{"type":"text","text":"hi"}]}}"""
    private fun cfg(s: String): LauncherConfig = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun store() = ConsentStore(tmp.newFolder())
    private fun ConsentStore.state(c: LauncherConfig, id: String = "w-1") = report(c).single { it.id == id }.state

    private val open = """{"kind":"ui.openUrl"}"""
    private val src = """"source":"blog.anigeek.com","""

    @Test fun linesAreTheFingerprint() {
        val c = cfg(doc(card(caps = """$open,{"kind":"ui.openApp","packages":["org.b","org.a"]},{"kind":"ui.reload"},{"kind":"data.file"}""", extra = src)))
        assertEquals(listOf("data.file", "ui.openApp org.a", "ui.openApp org.b", "ui.openUrl https://blog.anigeek.com", "ui.reload"),
            Consent.lines(c.elements!![0]))
        // Bare ui.openUrl without source adds no line; http:// is kept, so it never matches the https form.
        assertEquals(emptyList<String>(), Consent.lines(cfg(doc(card(caps = open))).elements!![0]))
        assertEquals(listOf("ui.openUrl http://nas.lan"), Consent.lines(cfg(doc(card(caps = open, extra = """"source":"http://nas.lan","""))).elements!![0]))
    }

    @Test fun silentKindsGoLiveWithNoPrompt() {
        val s = store()
        val c = cfg(doc(card(caps = """{"kind":"data.file"},{"kind":"ui.reload"},{"kind":"ui.openApp","packages":["org.a"]}""")))
        assertEquals(State.Allowed, s.state(c))
        // Q-D5 follow-up: bare ui.openUrl without source needs no Allow either (every tap confirms).
        assertEquals(State.Allowed, s.state(cfg(doc(card(caps = open)))))
    }

    @Test fun sourceNeedsOkAndOnlyANewOrChangedSourceReprompts() {
        val s = store()
        val v1 = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = src)))
        s.settle(v1)
        val st = s.report(v1).single()
        assertEquals(State.NeedsApproval, st.state)
        assertEquals(listOf("ui.openUrl https://blog.anigeek.com"), st.missing)
        assertEquals("elements[0]: widget \"Anigeek LLC\" is waiting for approval on this phone. It isn't shown until then.", st.warning)
        s.allow("w-1", st.lines)
        assertEquals(State.Allowed, s.state(v1))
        // Removing source: stricter (every tap confirms), stays live.
        assertEquals(State.Allowed, s.state(cfg(doc(card(caps = """{"kind":"data.file"},$open""")))))
        // Changed host, or https -> http (no silent downgrade): needs OK again; only the new line is missing.
        for (other in listOf("anigeek.com", "http://blog.anigeek.com")) {
            val v2 = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = """"source":"$other",""")))
            val r = s.report(v2).single()
            assertEquals(other, State.NeedsApprovalAgain, r.state)
            assertEquals(1, r.missing.size)
            assertEquals("elements[0]: widget \"Anigeek LLC\" asks for more access and is waiting for approval on this phone. It isn't shown until then.", r.warning)
        }
        // Bare and https:// spellings of the same source are one line.
        assertEquals(State.Allowed, s.state(cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = """"source":"https://blog.anigeek.com",""")))))
    }

    @Test fun ceilingIsAUnionThatNeverShrinks() {
        val s = store()
        val a = cfg(doc(card(caps = open, extra = """"source":"a.org",""")))
        s.allow("w-1", s.report(a).single().lines)
        val b = cfg(doc(card(caps = open, extra = """"source":"b.org",""")))
        s.allow("w-1", s.report(b).single().lines)
        // Back to a.org (an earlier version): live with no prompt.
        s.settle(a)
        assertEquals(State.Allowed, s.state(a))
        assertEquals(setOf("ui.openUrl https://a.org", "ui.openUrl https://b.org"), s.entry("w-1")!!.ceiling)
    }

    @Test fun broadeningASilentlyLiveCardReadsAgain() {
        val s = store()
        s.settle(cfg(doc(card())))
        assertEquals(setOf("data.file"), s.entry("w-1")!!.ceiling)
        assertEquals(State.NeedsApprovalAgain, s.state(cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = src)))))
    }

    @Test fun turnOffKeepsTheCeilingAndForgetClearsIt() {
        val s = store()
        val c = cfg(doc(card(caps = open, extra = src)))
        s.allow("w-1", s.report(c).single().lines)
        s.turnOff("w-1")
        val off = s.report(c).single()
        assertEquals(State.Off, off.state)
        assertEquals("elements[0]: widget \"Anigeek LLC\" is turned off on this phone.", off.warning)
        assertTrue(off.missing.isEmpty()) // Allow restores with no prompt
        s.turnOn("w-1")
        assertEquals(State.Allowed, s.state(c))

        s.alwaysAllow("w-1", "https://anigeek.com")
        s.forget("w-1")
        val f = s.report(c).single()
        assertEquals(State.Off, f.state) // stays off
        assertEquals(listOf("ui.openUrl https://blog.anigeek.com"), f.missing) // next Allow is a fresh full prompt
        assertFalse(s.isAlwaysAllowed("w-1", "https://anigeek.com"))
        assertTrue(s.entry("w-1")!!.ceiling.isEmpty())
        // A silently-live card stays off after Forget too (the switch is sticky).
        s.settle(cfg(doc(card())))
        assertEquals(State.Off, s.state(cfg(doc(card()))))
    }

    @Test fun approvalsPersistAndAreGcdWhenTheElementLeaves() {
        val dir = tmp.newFolder()
        val s = ConsentStore(dir)
        val c = cfg(doc(card(caps = open, extra = src), card(id = "w-2", caps = open, extra = src, y = 2)))
        s.allow("w-1", s.report(c)[0].lines); s.allow("w-2", s.report(c)[1].lines); s.alwaysAllow("w-2", "https://x.org")
        val again = ConsentStore(dir)
        assertEquals(State.Allowed, again.state(c, "w-2"))
        assertTrue(again.isAlwaysAllowed("w-2", "https://x.org"))
        // w-2 skipped for now (newer requires): its approval is kept. Gone from the file: dropped.
        again.settle(cfg(doc(card(caps = open, extra = src), card(id = "w-2", caps = open, extra = src, y = 2, requires = 9))))
        assertNotNull(again.entry("w-2"))
        again.settle(cfg(doc(card(caps = open, extra = src))))
        assertNull(again.entry("w-2"))
        assertNull(ConsentStore(dir).entry("w-2"))
        // An id change is a new widget.
        assertEquals(State.NeedsApproval, again.state(cfg(doc(card(id = "w-3", caps = open, extra = src))), "w-3"))
    }

    @Test fun unreadableStoreAsksAgain() {
        val dir = tmp.newFolder()
        File(dir, "consent.json").writeText("{nope")
        assertEquals(State.NeedsApproval, ConsentStore(dir).state(cfg(doc(card(caps = open, extra = src)))))
    }

    @Test fun skippedCardsHaveNoConsentState() {
        val c = cfg(doc(card(caps = open, extra = src, requires = 9)))
        assertTrue(store().report(c).isEmpty())
    }

    @Test fun statusJsonHasTheConsentMapButNeverTheApprovals() = runBlocking {
        val dir = tmp.newFolder("src")
        val src0 = LocalFileSource(dir)
        src0.file.writeText(doc(card(caps = open, extra = src), card(id = "w-2", y = 2)))
        val store = ConsentStore(tmp.newFolder("consent"))
        val e = ConfigEngine(src0, LastGoodStore(tmp.newFolder()), 0, consent = store)
        e.poll(force = true)
        fun status() = Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject
        val s = status()
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        val m = s["consent"]!!.jsonObject
        assertEquals("needsApproval", m["w-1"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("allowed", m["w-2"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertNull(m["w-2"]!!.jsonObject["warning"])
        val w = "elements[0]: widget \"Anigeek LLC\" is waiting for approval on this phone. It isn't shown until then."
        assertEquals(w, m["w-1"]!!.jsonObject["warning"]!!.jsonPrimitive.content)
        assertTrue(s["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(w))
        assertFalse(File(dir, ".configlauncher/status.json").readText().contains("ceiling"))
        assertFalse(dir.walk().any { it.name == "consent.json" })

        store.allow("w-1", e.consentReport().single { it.id == "w-1" }.lines)
        e.consentChanged()
        assertEquals("allowed", status()["consent"]!!.jsonObject["w-1"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        store.turnOff("w-1"); e.consentChanged()
        assertEquals("off", status()["consent"]!!.jsonObject["w-1"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }
}
