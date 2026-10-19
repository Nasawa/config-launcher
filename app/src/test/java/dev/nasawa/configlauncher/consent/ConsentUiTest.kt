package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.consent.ConsentUi.Line.Kind
import dev.nasawa.configlauncher.consent.ConsentUi.Reason
import dev.nasawa.configlauncher.model.LauncherConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #142 S3-C: consent tiles on the grid and the prompt sheet's lines (ux.md §17.3, §17.5). */
class ConsentUiTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]},{"id":"two","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun card(id: String = "w-1", caps: String = """{"kind":"data.file"}""", extra: String = "", y: Int = 0, page: String = "home", requires: Int = 4) =
        """{"id":"$id","type":"card","requires":$requires,"page":"$page","x":0,"y":$y,"w":2,"h":2,$extra"capabilities":[$caps],"layouts":{"2x2":[{"type":"text","text":"hi"}]}}"""
    private fun cfg(s: String): LauncherConfig = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private val open = """{"kind":"ui.openUrl"}"""
    private val src = """"title":"Anigeek LLC","source":"blog.anigeek.com","""

    @Test fun tilesForWaitingOffAndSkippedCardsOnly() {
        val s = ConsentStore(tmp.newFolder(), clock = { 42L })
        val c = cfg(doc(card(caps = "$open,{\"kind\":\"data.file\"}", extra = src),
            card(id = "w-live", y = 2),
            card(id = "w-new", y = 4, requires = 9, extra = """"future":1,"""),
            card(id = "w-other", page = "two", caps = open, extra = src)))
        s.settle(c)
        val tiles = ConsentUi.tiles(c, s.report(c), "home")
        assertEquals(listOf("w-1" to Reason.NeedsOk, "w-new" to Reason.Update), tiles.map { it.id to it.reason })
        assertEquals("Anigeek LLC", tiles[0].title)
        assertEquals(setOf(0 to 4, 1 to 4, 0 to 5, 1 to 5), tiles[1].placement.cells())
        assertEquals(1, ConsentUi.tiles(c, s.report(c), "two").size)
        assertEquals(2, ConsentUi.count(s.report(c)))
        s.turnOff("w-1")
        assertEquals(Reason.Off, ConsentUi.tiles(c, s.report(c), "home")[0].reason)
        assertEquals(1, ConsentUi.count(s.report(c))) // Turned off isn't counted
    }

    @Test fun firstTimePromptListsEveryLineInTierOrder() {
        val s = ConsentStore(tmp.newFolder(), clock = { 42L })
        val c = cfg(doc(card(caps = """$open,{"kind":"ui.openApp","packages":["org.a"]},{"kind":"ui.reload"},{"kind":"data.file"}""",
            extra = """"title":"Anigeek LLC","author":"Claw","source":"http://nas.lan",""")))
        s.settle(c)
        val st = s.report(c).single()
        val p = ConsentUi.prompt(c.elements!![0], st, s.entry("w-1"))
        assertFalse(p.broadened)
        assertEquals(42L, p.seen)
        assertEquals("Claw", p.author)
        assertEquals(listOf(Kind.ShowFile, Kind.Reload, Kind.OpenApp, Kind.OpenSource), p.lines.map { it.kind })
        assertEquals("widgets-data/w-1.json", p.lines[0].arg)
        assertEquals("org.a", p.lines[2].arg)
        // An http source is written in full and carries the not-private note; https is shown bare.
        assertEquals("http://nas.lan", p.lines[3].arg); assertTrue(p.lines[3].http)
        assertTrue(p.lines.none { it.isNew })
        val https = cfg(doc(card(caps = open, extra = src)))
        assertEquals(ConsentUi.Line(Kind.OpenSource, "blog.anigeek.com"), ConsentUi.prompt(https.elements!![0], s.report(https).single(), null).lines.single())
        // No author: no author line (Q-C4).
        assertNull(ConsentUi.prompt(https.elements!![0], s.report(https).single(), null).author)
    }

    @Test fun broadenedPromptSplitsNewFromAlreadyAllowed() {
        val s = ConsentStore(tmp.newFolder())
        val v1 = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = src)))
        s.allow("w-1", s.report(v1).single().lines)
        val v2 = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = """"title":"Anigeek LLC","source":"anigeek.com",""")))
        val st = s.report(v2).single()
        val p = ConsentUi.prompt(v2.elements!![0], st, s.entry("w-1"))
        assertTrue(p.broadened)
        assertEquals(listOf(ConsentUi.Line(Kind.OpenSource, "anigeek.com", isNew = true)), p.newLines)
        assertEquals(listOf(Kind.ShowFile), p.already.map { it.kind })
        // After Forget approval the next Allow is a fresh first-time prompt with every line.
        s.forget("w-1")
        val f = ConsentUi.prompt(v2.elements!![0], s.report(v2).single(), s.entry("w-1"))
        assertFalse(f.broadened); assertTrue(f.lines.none { it.isNew })
    }

    @Test fun bareOpenUrlIsListedButNeedsNoPrompt() {
        val s = ConsentStore(tmp.newFolder())
        val c = cfg(doc(card(caps = """{"kind":"data.file"},$open""")))
        val st = s.report(c).single()
        assertEquals(Consent.State.Allowed, st.state)
        assertTrue(ConsentUi.tiles(c, s.report(c), "home").isEmpty())
        assertEquals(listOf(Kind.ShowFile, Kind.OpenAsk), ConsentUi.prompt(c.elements!![0], st, null).lines.map { it.kind })
    }
}
