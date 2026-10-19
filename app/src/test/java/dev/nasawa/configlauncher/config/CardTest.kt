package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** #137 S3-A: the `card` element (feature level 4), docs/design/dynamic-widgets.md §1–§4, §11 with Q-D5 (#146). */
class CardTest {
    private fun doc(card: String) =
        """{"schemaVersion":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]}],"elements":[$card]}"""
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun errors(s: String) = (ConfigCodec.decode(s) as ParseResult.Invalid).errors
    private fun card(extra: String = "", layouts: String = """{"1x1":[{"type":"text","text":"hi"}]}""") =
        """{"id":"w-1","type":"card","requires":4,"page":"home","x":0,"y":1,"w":2,"h":2,"layouts":$layouts$extra}"""

    @Test fun minimalCardIsValidAndReservesItsCells() {
        val c = ok(doc(card()))
        assertTrue(Elements.warnings(c).isEmpty())
        assertEquals(setOf(0 to 1, 1 to 1, 0 to 2, 1 to 2), Elements.reserved(c, "home"))
    }

    @Test fun requiresFourAndGridPlacement() {
        assertTrue(errors(doc(card().replace("\"requires\":4,", ""))).contains("elements[0]: missing required \"requires\""))
        assertTrue(errors(doc(card().replace("\"requires\":4", "\"requires\":3"))).contains("elements[0].requires: a card needs feature level 4 or higher"))
        // Above the level: skipped like any element, cells kept.
        assertEquals(1, Elements.warnings(ok(doc(card().replace("\"requires\":4", "\"requires\":5") .replace("\"layouts\"", "\"future\":1,\"layouts\"")))).size)
    }

    @Test fun unknownCapabilityKindSkipsTheCardWithAWarning() {
        val c = ok(doc(card(""","capabilities":[{"kind":"data.file"},{"kind":"net.fetch","origins":["https://x.org"]}]""")))
        assertEquals(listOf("elements[0]: capability \"net.fetch\" isn't supported by this Config Launcher, so the card can't be shown. It stays in the file. Update the app to show it."), Elements.warnings(c))
        assertTrue(Elements.shown(c).isEmpty())
        assertEquals(4, Elements.reserved(c, "home").size)   // cells kept
        assertTrue("net.fetch" in ConfigCodec.encode(c))      // kept verbatim
    }

    @Test fun qd5BareOpenUrlAndSource() {
        ok(doc(card(""","source":"https://blog.anigeek.com","capabilities":[{"kind":"ui.openUrl"}]""",
            """{"1x1":[{"type":"text","text":"go","tap":{"kind":"ui.openUrl","url":"https://elsewhere.org/x"}}]}""")))
        ok(doc(card(""","source":"nas.lan","capabilities":[{"kind":"ui.openUrl"}]""")))
        assertTrue(errors(doc(card(""","capabilities":[{"kind":"ui.openUrl","hosts":["a.org"]}]"""))).contains("elements[0].capabilities[0]: unknown key \"hosts\""))
        for (bad in listOf("https://a.org/path", "ftp://a.org", "A.org", "user@a.org", "a.org:8080"))
            assertTrue(bad, errors(doc(card(""","source":"$bad""""))).contains("elements[0].source must be a lowercase hostname, optionally with http:// or https://."))
    }

    @Test fun tapsNeedTheirCapability() {
        val tap = { t: String -> """{"1x1":[{"type":"button","label":"Go","tap":$t}]}""" }
        assertTrue(errors(doc(card(layouts = tap("""{"kind":"ui.reload"}""")))).contains("elements[0].layouts.1x1[0].tap: ui.reload isn't in this card's capabilities"))
        assertTrue(errors(doc(card(""","capabilities":[{"kind":"ui.openApp","packages":["p.a"]}]""", tap("""{"kind":"ui.openApp","package":"p.b"}"""))))
            .contains("elements[0].layouts.1x1[0].tap.package: \"p.b\" isn't in the ui.openApp packages"))
        assertTrue(errors(doc(card(""","capabilities":[{"kind":"ui.openUrl"}]""", tap("""{"kind":"ui.openUrl","url":"javascript:alert(1)"}"""))))
            .contains("elements[0].layouts.1x1[0].tap.url: must be an http or https URL"))
        assertTrue(errors(doc(card(""","capabilities":[{"kind":"ui.openUrl"}]""", tap("""{"kind":"ui.openUrl","url":"https://me@a.org/"}"""))))
            .contains("elements[0].layouts.1x1[0].tap.url: must not contain user info"))
        ok(doc(card(""","capabilities":[{"kind":"ui.openUrl"}]""", tap("""{"kind":"ui.openUrl","url":"{data:blog.url}"}"""))))
    }

    @Test fun layoutsAndNodes() {
        assertTrue(errors(doc(card(layouts = """{"4X3":[{"type":"text","text":"x"}]}"""))).contains("elements[0].layouts: \"4X3\" is not a size like \"4x3\" (lowercase x, 1 to 12)"))
        assertTrue(errors(doc(card(layouts = """{"1x1":[],"2x1":[{"type":"text","text":"x"}],"3x1":[{"type":"text","text":"x"}],"4x1":[{"type":"text","text":"x"}]}""")))
            .containsAll(listOf("elements[0].layouts: has 4 entries, at most 3 allowed", "elements[0].layouts.1x1: needs at least 1 entries")))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"chart"}]}"""))).single().startsWith("elements[0].layouts.1x1[0].type: unknown node type \"chart\""))
        val deep = (1..5).fold("""{"type":"text","text":"x"}""") { acc, _ -> """{"type":"column","children":[$acc]}""" }
        assertTrue(errors(doc(card(layouts = """{"1x1":[$deep]}"""))).contains("elements[0].layouts.1x1[0].children[0].children[0].children[0]: is nested too deep (at most 4 levels)"))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"icon","name":"cloud","package":"p.a"}]}"""))).contains("elements[0].layouts.1x1[0]: needs exactly one of \"name\" or \"package\""))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"progress","value":2}]}"""))).contains("elements[0].layouts.1x1[0].value: must be a template or a number from 0 to 1"))
        assertTrue(errors(doc(card(layouts = """{"1x1":[{"type":"text","text":"x","x":1}]}"""))).contains("elements[0].layouts.1x1[0]: unknown key \"x\""))   // no coordinates inside a card
    }

    @Test fun canonicalOrderAndRoundTrip() {
        val c = ok(doc("""{"x-a":1,"layouts":{"1x1":[{"type":"text","text":"hi"}]},"capabilities":[{"packages":["p.a"],"kind":"ui.openApp"}],"h":1,"w":1,"y":1,"x":0,"page":"home","requires":4,"type":"card","id":"w-1"}"""))
        val el = Json.parseToJsonElement(ConfigCodec.encode(c)).jsonObject["elements"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("id", "type", "requires", "page", "x", "y", "w", "h", "capabilities", "layouts", "x-a"), el.keys.toList())
        assertEquals(listOf("kind", "packages"), el["capabilities"]!!.jsonArray[0].jsonObject.keys.toList())
    }
}
