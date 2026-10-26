package dev.nasawa.configlauncher.consent

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.consent.Consent.Event.Kind
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Grace's #164 review: quiet history for cosmetic edits (§17.2, §17.6), GC, B9 at a cold start, and no launcher.json writes. */
class ConsentHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]},{"id":"two","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun card(id: String = "w-1", x: Int = 0, y: Int = 0, page: String = "home", w: Int = 2, text: String = "hi") =
        """{"id":"$id","type":"card","requires":4,"page":"$page","x":$x,"y":$y,"w":$w,"h":2,"title":"T","capabilities":[{"kind":"data.file"}],"layouts":{"2x2":[{"type":"text","text":"$text"}],"4x2":[{"type":"text","text":"$text"}]}}"""
    /** [card] with every object's keys in the opposite order, nested ones too. */
    private fun reordered(id: String = "w-1") =
        """{"layouts":{"4x2":[{"text":"hi","type":"text"}],"2x2":[{"text":"hi","type":"text"}]},"capabilities":[{"kind":"data.file"}],"title":"T","h":2,"w":2,"y":0,"x":0,"page":"home","requires":4,"type":"card","id":"$id"}"""
    private fun cfg(s: String): LauncherConfig = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private var now = 1_000L
    private fun store() = ConsentStore(tmp.newFolder(), clock = { now })

    @Test fun keyOrderIsNotAChange() {
        val s = store()
        s.settle(cfg(doc(card())))
        val before = s.entry("w-1")!!
        now = 2_000L
        s.settle(cfg(doc(reordered())))
        assertEquals(before, s.entry("w-1"))
        assertTrue(s.entry("w-1")!!.history.isEmpty())
        assertNull(s.entry("w-1")!!.changed)
    }

    @Test fun resizeIsAQuietLayoutRowButAMoveIsNothing() {
        val s = store()
        s.settle(cfg(doc(card())))
        now = 2_000L
        // Moving (page, x, y) isn't a change of the card.
        s.settle(cfg(doc(card(x = 2, y = 3, page = "two"))))
        assertTrue(s.entry("w-1")!!.history.isEmpty())
        assertNull(s.entry("w-1")!!.changed)
        // A span change is cosmetic: one `Layout changed in launcher.json` row, and `changed` moves (§17.2).
        now = 3_000L
        s.settle(cfg(doc(card(w = 4))))
        val e = s.entry("w-1")!!
        assertEquals(listOf(Consent.Event(3_000L, Kind.Layout)), e.history)
        assertEquals(3_000L, e.changed)
        // Same span again: nothing more.
        now = 4_000L
        s.settle(cfg(doc(card(w = 4))))
        assertEquals(1, s.entry("w-1")!!.history.size)
    }

    @Test fun aCardThatLeavesAndComesBackStartsFresh() {
        // Today's GC (Grace's open question for Pragma/Archimedes): an id that leaves the file loses its approval and history.
        val s = store()
        val c = cfg(doc(card()))
        s.settle(c)
        assertTrue(s.allow("w-1", s.report(c).single().lines, c))
        s.turnOff("w-1")
        assertEquals(listOf(Kind.Allowed, Kind.TurnedOff), s.entry("w-1")!!.history.map { it.kind })
        s.settle(cfg(doc()))
        assertNull(s.entry("w-1"))
        now = 5_000L
        s.settle(c)
        val back = s.entry("w-1")!!
        assertTrue(back.history.isEmpty())
        assertFalse(back.off)
        assertEquals(5_000L, back.seen)
    }

    @Test fun b9DismissalSurvivesAColdStartFromTheLastGoodLayout() {
        // Dismissed at 2. A cold start shows the default/last-good layout first (count 0): that mustn't reset it...
        assertEquals(2, ConsentUi.B9.follow(0, 2, fromFile = false))
        assertFalse(ConsentUi.B9.visible(2, ConsentUi.B9.follow(0, 2, fromFile = false), otherBanner = false))
        // ...but the file itself going down does, so the next rise shows B9.
        assertEquals(1, ConsentUi.B9.follow(1, 2, fromFile = true))
    }

    @Test fun consentActionsNeverWriteLauncherJson() = runBlocking {
        val dir = tmp.newFolder()
        val src = LocalFileSource(dir)
        val text = doc(card(), card(id = "w-2", y = 2))
        src.file.writeText(text)
        src.file.setLastModified(1_000_000L)
        val consent = ConsentStore(tmp.newFolder())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { now }, consent = consent)
        e.poll(force = true)
        val lines = e.consentReport().first { it.id == "w-1" }.lines
        val before = consent.entry("w-1")!!
        assertTrue(e.allowConsent("w-1", lines)); e.consentChanged()
        consent.turnOff("w-1"); e.consentChanged()
        consent.undo("w-1", before, Kind.TurnedOff); e.consentChanged()
        consent.turnOff("w-1"); consent.turnOn("w-1"); e.consentChanged()
        consent.alwaysAllow("w-1", "https://example.org"); consent.removeAlways("w-1", "https://example.org"); e.consentChanged()
        consent.forget("w-2"); e.consentChanged()
        e.poll(force = true)
        assertEquals(text, src.file.readText())
        assertEquals(1_000_000L, src.file.lastModified())
        // Nothing new next to launcher.json except the app's own .configlauncher folder (status.json).
        assertEquals(setOf("launcher.json", ".configlauncher"), dir.list()!!.toSet())
        assertFalse(File(dir, "consent.json").exists())
    }
}
