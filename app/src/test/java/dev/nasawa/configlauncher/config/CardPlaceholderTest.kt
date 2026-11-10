package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.consent.Consent
import dev.nasawa.configlauncher.consent.ConsentStore
import dev.nasawa.configlauncher.consent.ConsentUi
import dev.nasawa.configlauncher.consent.ConsentUi.Reason
import dev.nasawa.configlauncher.model.LauncherConfig
import dev.nasawa.configlauncher.ui.HomeMenu
import dev.nasawa.configlauncher.ui.HomeAction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #257: release-build placeholder tiles and warnings, without changing debug consent behaviour. */
class CardPlaceholderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun doc(vararg els: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":6},"pages":[{"id":"home","items":[]},{"id":"two","items":[]}],"elements":[${els.joinToString(",")}]}"""
    private fun card(id: String = "w-1", caps: String = """{"kind":"data.file"}""", extra: String = "", y: Int = 0, page: String = "home", requires: Int = 4) =
        """{"id":"$id","type":"card","requires":$requires,"page":"$page","x":0,"y":$y,"w":2,"h":2,$extra"capabilities":[$caps],"layouts":{"2x2":[{"type":"text","text":"hi"}]}}"""
    private fun cfg(s: String): LauncherConfig = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private val open = """{"kind":"ui.openUrl"}"""
    private val src = """"title":"Anigeek LLC","source":"blog.anigeek.com","""

    @Test fun placeholdersCoverShownCardsAndSkipTooNew() {
        val c = cfg(doc(
            card(caps = "$open,{\"kind\":\"data.file\"}", extra = src),
            card(id = "w-live", y = 2),
            card(id = "w-new", y = 4, requires = 9, extra = """"future":1,"""),
            card(id = "w-other", page = "two", caps = open, extra = src),
        ))
        val home = CardPlaceholder.tiles(c, "home")
        assertEquals(listOf("w-1", "w-live"), home.map { it.id })
        assertEquals("Anigeek LLC", home[0].title)
        assertNull(home[1].title)
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), home[0].placement.cells())
        assertEquals(listOf("w-other"), CardPlaceholder.tiles(c, "two").map { it.id })
        // FEATURE_LEVEL is unchanged, so 1.1 files still validate; the too-new card is not a placeholder.
        assertEquals(4, Elements.FEATURE_LEVEL)
        assertTrue(Elements.skip(c.elements!![2]) is Elements.Skip.TooNew)
    }

    @Test fun warningsNameEachShownCardIdAndNotSkippedOnes() {
        val c = cfg(doc(
            card(caps = "$open,{\"kind\":\"data.file\"}", extra = src),
            card(id = "w-live", y = 2),
            card(id = "w-new", y = 4, requires = 9, extra = """"future":1,"""),
        ))
        assertEquals(
            listOf(
                """elements[0]: widget "w-1" isn't drawn in this version of Reflow Launcher. It stays in the file.""",
                """elements[1]: widget "w-live" isn't drawn in this version of Reflow Launcher. It stays in the file.""",
            ),
            CardPlaceholder.warnings(c),
        )
        assertTrue(Elements.warnings(c).any { "needs feature level 9" in it })
        assertTrue(CardPlaceholder.warnings(c).none { "w-new" in it })
    }

    @Test fun debugConsentPathIsUnchanged() {
        val s = ConsentStore(tmp.newFolder(), clock = { 42L })
        val c = cfg(doc(card(caps = "$open,{\"kind\":\"data.file\"}", extra = src), card(id = "w-live", y = 2)))
        s.settle(c)
        val report = s.report(c)
        val tiles = ConsentUi.tiles(c, report, "home")
        assertEquals(listOf("w-1" to Reason.NeedsOk), tiles.map { it.id to it.reason })
        assertEquals(listOf("w-live"), ConsentUi.live(c, report, "home").map { it.id })
        assertEquals(1, ConsentUi.count(report))
        assertEquals("elements[0]: widget \"Anigeek LLC\" is waiting for approval on this phone. It isn't shown until then.", report[0].warning)
        assertNull(report[1].warning)
        assertEquals(Consent.State.NeedsApproval, report[0].state)
        assertEquals(Consent.State.Allowed, report[1].state)
    }

    @Test fun storedApprovalsAreLeftAlone() {
        val s = ConsentStore(tmp.newFolder(), clock = { 42L })
        val c = cfg(doc(card(caps = "$open,{\"kind\":\"data.file\"}", extra = src)))
        s.settle(c)
        val lines = s.report(c).single().lines
        assertTrue(s.allow("w-1", lines, c))
        assertEquals(Consent.State.Allowed, s.report(c).single().state)
        val ceiling = s.entry("w-1")!!.ceiling
        s.settle(c)
        assertEquals(Consent.State.Allowed, s.report(c).single().state)
        assertEquals(ceiling, s.entry("w-1")!!.ceiling)
        assertFalse(s.entry("w-1")!!.off)
    }

    @Test fun hideRulesMatchConsentTile() {
        assertFalse(CardPlaceholder.showTitle(1, 1))
        assertFalse(CardPlaceholder.showTitle(2, 1))
        assertTrue(CardPlaceholder.showTitle(3, 1))
        assertTrue(CardPlaceholder.showTitle(2, 2))
        assertFalse(CardPlaceholder.showMessage(1, 1))
        assertTrue(CardPlaceholder.showMessage(2, 1))
        assertTrue(CardPlaceholder.showMessage(2, 2))
    }

    @Test fun releaseMenuHidesWidgetPermissions() {
        assertEquals(
            listOf(HomeAction.EDIT_LAYOUT, HomeAction.PAGES, HomeAction.RELOAD, HomeAction.SETTINGS, HomeAction.WALLPAPER),
            HomeMenu.actions(2, 3, cardsLive = false),
        )
        assertTrue(HomeAction.WIDGETS_REVIEW in HomeMenu.actions(2, 3, cardsLive = true))
    }
}
