package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.consent.Consent.Event.Kind
import dev.nasawa.configlauncher.consent.ConsentUi.Chip
import dev.nasawa.configlauncher.consent.ConsentUi.Noun
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.ui.HomeAction
import dev.nasawa.configlauncher.ui.HomeMenu
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #142 S3-C.2: review list, widget detail, history, B9 and menu entry (ux.md §17.4, §17.6). */
class ConsentReviewTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]},{"id":"two","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun card(id: String = "w-1", caps: String = """{"kind":"data.file"}""", extra: String = "", y: Int = 0, page: String = "home", w: Int = 2, text: String = "hi") =
        """{"id":"$id","type":"card","requires":4,"page":"$page","x":0,"y":$y,"w":$w,"h":2,$extra"capabilities":[$caps],"layouts":{"2x2":[{"type":"text","text":"$text"}]}}"""
    private fun cfg(s: String): LauncherConfig = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private val open = """{"kind":"ui.openUrl"}"""
    private val src = """"title":"Anigeek LLC","source":"blog.anigeek.com","""
    private var now = 1_000L
    private fun store(dir: java.io.File = tmp.newFolder()) = ConsentStore(dir, clock = { now })

    @Test fun reviewListHasOneSectionPerStateAndNoAllowAll() {
        val s = store()
        val c = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = """"title":"Anigeek LLC","author":"Claw","source":"blog.anigeek.com","""),
            card(id = "w-2", caps = """{"kind":"data.file"},{"kind":"ui.openApp","packages":["org.a"]}""", y = 2, extra = """"title":"Two","""),
            card(id = "w-3", page = "two", w = 4, extra = """"title":"Three",""")))
        s.settle(c)
        s.turnOff("w-3")
        val r = ConsentUi.review(c, s.report(c), s.entries())
        assertEquals(listOf("w-1"), r.needs.map { it.id })
        assertEquals(listOf("w-2"), r.allowed.map { it.id })
        assertEquals(listOf("w-3"), r.off.map { it.id })
        val needs = r.needs.single()
        assertEquals(ConsentUi.Where(1, 2, 2), needs.where)
        assertEquals(listOf(Noun.Links), needs.news)   // only what's waiting: the source link
        assertEquals("Claw", needs.author)
        assertEquals(1_000L, needs.changed)
        assertEquals(listOf(Chip.Show, Chip.Open), r.allowed.single().chips)
        assertEquals(ConsentUi.Where(2, 4, 2), r.off.single().where)
        assertFalse(r.empty)
        assertTrue(ConsentUi.review(cfg(doc()), emptyList(), emptyMap()).empty)
    }

    @Test fun detailShowsLinesCeilingAndSites() {
        val s = store()
        val a = cfg(doc(card(caps = """{"kind":"data.file"},$open""", extra = src)))
        s.settle(a)
        assertTrue(s.allow("w-1", s.report(a).single().lines, a))
        s.alwaysAllow("w-1", "http://news.example.org")
        // The file drops data.file: the ceiling still lists it, as not used now.
        val b = cfg(doc(card(caps = open, extra = src)))
        s.settle(b)
        val d = ConsentUi.detail(b, s.report(b).single(), s.entry("w-1"))!!
        assertEquals(Consent.State.Allowed, d.state)
        assertEquals(listOf(ConsentUi.Line.Kind.OpenSource), d.lines.map { it.first.kind })
        assertTrue(d.lines.all { it.second })
        assertEquals(listOf(ConsentUi.Line.Kind.ShowFile to true, ConsentUi.Line.Kind.OpenSource to false), d.approved.map { it.first.kind to it.second })
        assertEquals(listOf("http://news.example.org"), d.always)
        assertEquals("http://news.example.org", ConsentUi.host(d.always.single()))
        assertEquals("blog.anigeek.com", ConsentUi.host("https://blog.anigeek.com"))
        assertTrue(d.canForget)
        assertTrue(d.allowNeedsNoPrompt)
        // Newest first; the narrowing is logged quietly.
        assertEquals(listOf(Kind.NoLonger, Kind.Always, Kind.Allowed), d.history.map { it.kind })
        assertEquals("data.file", d.history.first().arg)
    }

    @Test fun turnOffUndoAndForget() {
        val s = store()
        val c = cfg(doc(card(caps = open, extra = src)))
        s.settle(c)
        s.allow("w-1", s.report(c).single().lines, c)
        s.alwaysAllow("w-1", "https://x.org")
        val before = s.entry("w-1")!!
        s.turnOff("w-1")
        assertEquals(Consent.State.Off, s.report(c).single().state)
        s.undo("w-1", before, Kind.TurnedOff)
        assertEquals(Consent.State.Allowed, s.report(c).single().state)
        assertEquals(listOf(Kind.Allowed, Kind.Always), s.entry("w-1")!!.history.map { it.kind })  // the undone row is gone
        // Remove a site, then Undo puts it back.
        val b2 = s.entry("w-1")!!
        s.removeAlways("w-1", "https://x.org")
        assertFalse(s.isAlwaysAllowed("w-1", "https://x.org"))
        s.undo("w-1", b2, Kind.SiteRemoved)
        assertTrue(s.isAlwaysAllowed("w-1", "https://x.org"))
        // Forget: off, ceiling and sites empty, history kept and logged; the next prompt is first-time.
        s.forget("w-1")
        val e = s.entry("w-1")!!
        assertTrue(e.off); assertTrue(e.ceiling.isEmpty()); assertTrue(e.alwaysAllow.isEmpty())
        assertEquals(Kind.Forgotten, e.history.last().kind)
        val d = ConsentUi.detail(c, s.report(c).single(), e)!!
        assertEquals(Consent.State.Off, d.state)
        assertFalse(d.canForget)
        assertFalse(d.allowNeedsNoPrompt)  // Allow opens the first-time prompt
        assertFalse(ConsentUi.prompt(c.elements!![0], s.report(c).single(), e).broadened)
        // Off with nothing missing: Allow turns it back on without a prompt.
        s.allow("w-1", s.report(c).single().lines, c)
        s.turnOff("w-1")
        assertTrue(ConsentUi.detail(c, s.report(c).single(), s.entry("w-1"))!!.allowNeedsNoPrompt)
        s.turnOn("w-1")
        assertEquals(Kind.TurnedOn, s.entry("w-1")!!.history.last().kind)
    }

    @Test fun historyKeepsTheLastFiveAndPersists() {
        val dir = tmp.newFolder()
        val s = store(dir)
        val c = cfg(doc(card(caps = open, extra = src)))
        s.settle(c)
        s.allow("w-1", s.report(c).single().lines, c)
        repeat(3) { now += 1; s.turnOff("w-1"); now += 1; s.turnOn("w-1") }
        val h = s.entry("w-1")!!.history
        assertEquals(Consent.HISTORY_MAX, h.size)
        assertEquals(Kind.TurnedOn, h.last().kind)
        // A restyle with the same capabilities is a quiet layout row; moving the card isn't.
        now = 50
        s.settle(cfg(doc(card(caps = open, extra = src, y = 3))))
        assertEquals(h, s.entry("w-1")!!.history)
        s.settle(cfg(doc(card(caps = open, extra = src, y = 3, text = "changed"))))
        assertEquals(Kind.Layout, s.entry("w-1")!!.history.last().kind)
        assertEquals(50L, s.entry("w-1")!!.changed)
        assertEquals(s.entry("w-1"), ConsentStore(dir).entry("w-1"))
    }

    @Test fun b9ShowsOnARiseAndMenuEntryOnlyWhileReviewing() {
        assertTrue(ConsentUi.B9.visible(2, 0, otherBanner = false))
        assertFalse(ConsentUi.B9.visible(2, 0, otherBanner = true))   // never above B1–B8
        assertFalse(ConsentUi.B9.visible(2, 2, otherBanner = false))  // dismissed at 2
        assertTrue(ConsentUi.B9.visible(3, 2, otherBanner = false))   // went up
        assertEquals(1, ConsentUi.B9.follow(1, 2))                     // follows the count down...
        assertTrue(ConsentUi.B9.visible(2, ConsentUi.B9.follow(1, 2), otherBanner = false)) // ...so the next rise shows
        assertFalse(ConsentUi.B9.visible(0, 0, otherBanner = false))
        assertFalse(HomeAction.WIDGETS_REVIEW in HomeMenu.actions(2, 0))
        assertEquals(listOf(HomeAction.EDIT_LAYOUT, HomeAction.PAGES, HomeAction.WIDGETS_REVIEW, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER),
            HomeMenu.actions(2, 3))
    }

    @Test fun liveCardsGetATargetAndTheSheetPicksPermissionsOrReview() {
        val s = store()
        val c = cfg(doc(card(extra = """"title":"Live","""), card(id = "w-2", caps = open, extra = src, y = 2), card(id = "w-3", page = "two")))
        s.settle(c)
        val live = ConsentUi.live(c, s.report(c), "home")
        assertEquals(listOf("w-1"), live.map { it.id })
        assertEquals("Live", live.single().title)
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), live.single().placement.cells())
        assertEquals(listOf("w-2"), ConsentUi.tiles(c, s.report(c), "home").map { it.id })  // no overlap with the tiles
        assertEquals(listOf(ConsentUi.SheetAction.PERMISSIONS, ConsentUi.SheetAction.EDIT_LAYOUT), ConsentUi.sheetActions(live = true))
        assertEquals(listOf(ConsentUi.SheetAction.REVIEW, ConsentUi.SheetAction.EDIT_LAYOUT), ConsentUi.sheetActions(live = false))
        assertEquals("w-3", dev.nasawa.configlauncher.ui.WidgetSheetFor(null, ConsentUi.live(c, s.report(c), "two").single()).name)
    }
}
