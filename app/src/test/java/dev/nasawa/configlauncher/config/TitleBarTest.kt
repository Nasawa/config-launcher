package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.config.Template.Lit
import dev.nasawa.configlauncher.config.Template.Tok
import dev.nasawa.configlauncher.model.TitleBarModel
import dev.nasawa.configlauncher.model.TitleBarModel.Slot
import dev.nasawa.configlauncher.ui.TitleBarLayout
import kotlinx.serialization.json.*
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** #79 part 2: title bar element (feature level 2), title-bar.md §4–§9, ux.md §15 / §15.1. */
class TitleBarTest {
    private fun doc(bar: String, overrides: String = "") =
        """{"schemaVersion":1,"pages":[{"id":"home","title":"Home","items":[]$overrides},{"id":"two","items":[]}],"elements":[$bar]}"""
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun errors(s: String) = (ConfigCodec.decode(s) as ParseResult.Invalid).errors
    private val preset = """{"id":"t-main","type":"titleBar","anchor":"top","content":{"slots":{"center":"{page}"}}}"""

    @Test fun templateGrammar() {
        assertEquals(Template.Ok(listOf(Lit("{"), Tok("page"), Lit(" · "), Tok("time", "HH:mm"), Lit("}"))), Template.parse("{{{page} · {time:HH:mm}}}"))
        assertEquals(Template.Ok(listOf(Tok("time"))), Template.parse("clock"))
        assertEquals(Template.Ok(listOf(Tok("date", "EEE d MMM"))), Template.parse("{date:EEE d MMM}"))
        assertEquals(Template.Ok(listOf(Tok("time", "h 'h' mm"))), Template.parse("{time:h 'h' mm}"))
        assertEquals(Template.Problem.Unknown("wether"), Template.check("{wether}"))
        assertEquals(Template.Problem.Letter("s", "{time:HH:mm:ss}"), Template.check("{time:HH:mm:ss}"))
        assertEquals(Template.Problem.Letter("z", "{time:HH z}"), Template.check("{time:HH z}"))
        assertEquals(Template.Problem.RunTooLong("MMMMMM", 5), Template.check("{date:MMMMMM}"))
        assertEquals(Template.Problem.RunTooLong("aa", 1), Template.check("{time:h aa}"))
        assertEquals(Template.Problem.QuoteUnclosed, Template.check("{date:d 'x}"))
        assertEquals(Template.Problem.Unclosed(5), Template.check("Day {date"))
        assertEquals(Template.Problem.Stray(9), Template.check("abcdefgh}"))
        assertEquals(Template.Problem.TooMany(9), Template.check("{page}".repeat(9)))
        assertEquals(Template.Problem.TooLong(130), Template.check("x".repeat(130)))
        assertEquals(Template.Problem.MultiLine, Template.check("a\nb"))
        assertEquals(Template.Problem.NoPattern("{page:x}"), Template.check("{page:x}"))
        assertEquals(Template.Problem.EmptyPattern("time"), Template.check("{time:}"))
        // Pragma's copy, docs/ux.md §15.1 (PR #122), exactly.
        assertEquals(listOf("elements[0].content.slots.center: {page:x} doesn't take a pattern. Only {time} and {date} do."),
            errors(doc("""{"id":"t-main","type":"titleBar","content":{"slots":{"center":"{page:x}"}}}""")))
        assertEquals(listOf("elements[0].style.size must be small, medium or large, or a number from 10 to 40."),
            errors(doc("""{"id":"t-main","type":"titleBar","style":{"size":"huge"}}""")))
        assertEquals(listOf("elements[0].content.slots.start: MMMMMM is too long. Use at most 5, like MMMMM."),
            errors(doc("""{"id":"t-main","type":"titleBar","content":{"slots":{"start":"{date:MMMMMM}"}}}""")))
        // Pragma's copy (#115 review).
        val p = "elements[0].content.slots.end"
        assertEquals("$p: {page:x} doesn't take a pattern. Only {time} and {date} do.", Template.message(Template.check("{page:x}")!!, p))
        assertEquals("$p: {time:} has an empty pattern. Write {time} for the default format.", Template.message(Template.check("{time:}")!!, p))
        assertEquals("$p: A template is one line. Remove the line break.", Template.message(Template.check("a\nb")!!, p))
        assertEquals("$p: MMMMMM is too long. Use at most 5, like MMMMM.", Template.message(Template.check("{date:MMMMMM}")!!, p))
        assertEquals("$p: aa is too long. Use at most 1, like a.", Template.message(Template.check("{time:h aa}")!!, p))
        assertEquals("$p: A ' quote isn't closed. Add a closing ' after the text.", Template.message(Template.check("{date:d 'x}")!!, p))
        // Positions count code points, like the length limit.
        assertEquals(Template.Problem.Unclosed(3), Template.check("😀a{x"))
    }

    @Test fun everyAllowedPatternFormatsWithoutThrowing() {
        val now = ZonedDateTime.parse("2026-10-18T22:42:00-06:00")
        for (p in listOf("yyyy-MM-dd", "EEEE d MMMM", "EEEEE", "MMMMM", "h:mm a", "HH:mm", "kk:mm", "KK:mm", "d/M/yy", "'week' d", "MMM ''yy", "y".repeat(19))) {
            assertNull(p, Template.badInPattern(p))
            DateTimeFormatter.ofPattern(p, Locale.US).format(now)
        }
        assertEquals("10:42 PM", DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(now))
        // The run limits are the formatter's real maxima: max letters format, one more throws.
        for ((c, max) in Template.LETTERS) {
            DateTimeFormatter.ofPattern(c.toString().repeat(max), Locale.US).format(now)
            assertThrows(IllegalArgumentException::class.java) { DateTimeFormatter.ofPattern(c.toString().repeat(max + 1), Locale.US) }
        }
    }

    @Test fun validatesStrictlyAndOnlyOnce() {
        ok(doc(preset))
        assertEquals(listOf("elements[0]: unknown key \"colour\""), errors(doc(preset.dropLast(1) + ""","colour":"red"}""")))
        assertEquals(listOf("elements[1]: only one titleBar is allowed (elements[0] is already one)."),
            errors(doc("$preset,${preset.replace("t-main", "t-two")}")))
        assertEquals(listOf("pages[0].overrides.t-main.style.size must be small, medium or large, or a number from 10 to 40."),
            errors(doc(preset, ""","overrides":{"t-main":{"style":{"size":9}}}""")))
        // A skipped title bar (requires too high) is not checked strictly and doesn't count as the one bar.
        ok(doc("""{"id":"t-new","type":"titleBar","requires":5,"anything":1},$preset"""))
    }

    @Test fun canonicalOrderAndRoundTrip() {
        val c = ok(doc("""{"x-a":1,"style":{"backdrop":{"radius":2,"color":"#000000"},"size":"large"},"content":{"slots":{"end":"{time}","start":"{date}"}},"anchor":"top","type":"titleBar","id":"t-main"}"""))
        val out = ConfigCodec.encode(c)
        val el = Json.parseToJsonElement(out).jsonObject["elements"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("id", "type", "anchor", "content", "style", "x-a"), el.keys.toList())
        assertEquals(listOf("start", "end"), el["content"]!!.jsonObject["slots"]!!.jsonObject.keys.toList())
        assertEquals(listOf("size", "backdrop"), el["style"]!!.jsonObject.keys.toList())
        assertEquals(listOf("color", "radius"), el["style"]!!.jsonObject["backdrop"]!!.jsonObject.keys.toList())
        assertEquals(out, ConfigCodec.encode(ok(out)))
    }

    @Test fun contrastIsAWarning() {
        val c = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"dark","backdrop":"scrim"}}"""))
        assertEquals(listOf("elements[0].style.color: contrast 1.2:1 on the backdrop (4.5:1 recommended). Shown anyway."), Elements.warnings(c))
        // White on the 60 % scrim is ≥ 5.7:1 even over a white wallpaper (ux.md §12).
        assertEquals(5.7, TitleBar.contrast(buildJsonObject { put("backdrop", "scrim") })!!, 0.05)
        assertNull(TitleBar.contrast(buildJsonObject { put("color", "primary"); put("backdrop", "scrim") }))
        assertNull(TitleBar.contrast(buildJsonObject { put("backdrop", "auto") }))
        assertTrue(Elements.warnings(ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"#FFFFFF","backdrop":{"color":"#000000"}}}"""))).isEmpty())
    }

    @Test fun themeColourContrastIsCheckedOnThePhone() {
        // #125 (Pragma): theme tokens are measured on the phone with its current colours; validate.py can't.
        val dim = mapOf("primary" to "#202020", "surface" to "#FFFFFF", "onSurface" to "#1B1B1F", "secondary" to "#FFFFFF")
        val c = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":{"light":"primary","dark":"#FFFFFF"},"backdrop":"scrim"}}"""))
        assertTrue(Elements.warnings(c).isEmpty())   // nothing the script could measure is low
        assertEquals(listOf("elements[0].style.color.light: contrast 1.2:1 on the backdrop (4.5:1 recommended), checked on this phone in light theme. Shown anyway."),
            Elements.phoneWarnings(c, dark = false) { dim[it] })
        assertTrue(Elements.phoneWarnings(c, dark = true) { dim[it] }.isEmpty())   // the dark variant is a hex: the static check covers it
        assertTrue(Elements.phoneWarnings(c, dark = false) { "#FFFFFF" }.isEmpty())  // a light primary passes
        // A theme-coloured custom backdrop, opaque: one wallpaper.
        val b = buildJsonObject { put("color", "onSurface"); putJsonObject("backdrop") { put("color", "surface") } }
        assertEquals(17.17, TitleBar.phoneContrast(b, false) { dim[it] }!!.ratio, 0.01)
        assertEquals("color", TitleBar.phoneContrast(b, false) { dim[it] }!!.key)
        // The path names the key holding the token: a theme backdrop under hex text → style.backdrop.color; dark theme says so.
        val bd = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"#000000","backdrop":{"color":"secondary","opacity":100}}}"""))
        assertEquals(listOf("elements[0].style.backdrop.color: contrast 1.0:1 on the backdrop (4.5:1 recommended), checked on this phone in dark theme. Shown anyway."),
            Elements.phoneWarnings(bd, dark = true) { mapOf("secondary" to "#000000")[it] })
        // No theme token, `auto`/`none`, or an unknown colour: nothing for the phone to add.
        assertNull(TitleBar.phoneContrast(buildJsonObject { put("backdrop", "scrim") }, false) { dim[it] })
        assertNull(TitleBar.phoneContrast(buildJsonObject { put("color", "primary"); put("backdrop", "auto") }, false) { dim[it] })
        assertNull(TitleBar.phoneContrast(buildJsonObject { put("color", "primary"); put("backdrop", "scrim") }, false) { null })
        // A skipped bar isn't measured.
        val skipped = ok(doc("""{"id":"t-main","type":"titleBar","requires":99,"style":{"color":"primary","backdrop":"scrim"}}"""))
        assertTrue(Elements.phoneWarnings(skipped, false) { dim[it] }.isEmpty())
    }

    @Test fun modelAppliesOverridesAndDefaults() {
        val c = ok(doc("""{"id":"t-main","type":"titleBar","content":{"slots":{"start":"{date}","center":"{page}","end":"clock"}},"style":{"size":"medium","weight":"bold"}}""",
            ""","overrides":{"t-main":{"content":{"slots":{"end":""}},"style":{"size":20}}}"""))
        val home = TitleBarModel.forPage(c, "home")!!
        assertEquals(setOf(Slot.START, Slot.CENTER), home.slots.keys)
        assertEquals(20f, home.style.sizeSp)
        assertEquals(700, home.style.weight)
        val two = TitleBarModel.forPage(c, "two")!!
        assertEquals(listOf(Tok("time")), two.slots[Slot.END])
        assertEquals(18f, two.style.sizeSp)
        assertEquals(TitleBarModel.Backdrop.Auto, two.style.backdrop)
        val v = TitleBarModel.Values("Home", 1, 2, { "10:42 PM" }, { "Oct 18, 2026" })
        assertEquals("Oct 18, 2026", TitleBarModel.text(home.slots.getValue(Slot.START), v))
        assertEquals("Home", TitleBarModel.text(home.slots.getValue(Slot.CENTER), v))
    }

    @Test fun disabledOrMissingBarShowsThePageLabel() {
        assertNull(TitleBarModel.forPage(ok(doc(preset, ""","overrides":{"t-main":{"enabled":false}}""")), "home"))
        assertNotNull(TitleBarModel.forPage(ok(doc(preset, ""","overrides":{"t-main":{"enabled":false}}""")), "two"))
        assertNull(TitleBarModel.forPage(ok(doc("""{"id":"t-main","type":"titleBar","enabled":false}""")), "home"))
        assertNull(TitleBarModel.forPage(ok(doc("""{"id":"t-main","type":"titleBar","requires":5}""")), "home"))
        assertNull(TitleBarModel.forPage(ok("""{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}"""), "home"))
    }

    @Test fun layoutKeepsHalfForTheCentre() {
        // Short sides: everyone fits, centre centred.
        assertEquals(Triple(300, 400, 300), TitleBarLayout.widths(1000, 100, 400, 200))
        // Long sides: centre keeps its width up to half, sides ellipsize.
        assertEquals(Triple(250, 500, 250), TitleBarLayout.widths(1000, 900, 800, 900))
        // Long centre with short sides: centre grows past half while the sides fit.
        assertEquals(Triple(100, 800, 100), TitleBarLayout.widths(1000, 100, 900, 50))
        // No centre: the sides split the bar.
        assertEquals(Triple(500, 0, 500), TitleBarLayout.widths(1000, 900, 0, 900))
    }

    @Test fun fixedSidesGiveUpCentringBeforeTruncating() {
        // #149 (Grace, 320 dp bar): date 170 + clock 60 fit in 272, so neither is cut and the centre takes what's between.
        val (s, c, e) = TitleBarLayout.widths(272, 170, 120, 60, fixed = setOf(0, 2))
        assertTrue("start $s", s >= 170); assertTrue("end $e", e >= 60); assertEquals(272, s + c + e)
        assertEquals(Triple(170, 42, 60), TitleBarLayout.widths(272, 170, 120, 60, fixed = setOf(0, 2), page = 1, pageMin = 32))
        // A non-fixed side still ellipsizes first (ux.md §15), here all the way, while the centre keeps its width.
        assertEquals(Triple(170, 102, 0), TitleBarLayout.widths(272, 170, 120, 300, fixed = setOf(0), page = 1, pageMin = 32))
        // The {page} button never collapses: before #149 this gave the centre 0 (2 × 140 > 272).
        assertEquals(Triple(140, 32, 100), TitleBarLayout.widths(272, 140, 120, 100, fixed = setOf(0, 2), page = 1, pageMin = 32))
        // ...or its own width, if that's less.
        assertEquals(20, TitleBarLayout.widths(272, 300, 20, 300, page = 1, pageMin = 32).second)
        // Page button on a side: it keeps its minimum there too.
        assertEquals(Triple(32, 0, 240), TitleBarLayout.widths(272, 200, 0, 240, fixed = setOf(2), page = 0, pageMin = 32))
        // Too narrow for everything: shared by need, nothing at zero.
        val (a, b, d) = TitleBarLayout.widths(200, 170, 120, 60, fixed = setOf(0, 2), page = 1, pageMin = 32)
        assertTrue(a > 0 && b > 0 && d > 0); assertEquals(200, a + b + d)
        // Still centred whenever the fixed sides allow it.
        assertEquals(Triple(100, 800, 100), TitleBarLayout.widths(1000, 100, 900, 50, fixed = setOf(0, 2), page = 1, pageMin = 32))
    }

    @Test fun clockAndDateNeverTruncate() {
        // #123: a long date on the start side takes its width from the centre, which still stays centred.
        assertEquals(Triple(300, 400, 300), TitleBarLayout.widths(1000, 300, 800, 100, fixed = setOf(0)))
        // Without the rule the centre would keep half and the date would ellipsize.
        assertEquals(Triple(250, 500, 250), TitleBarLayout.widths(1000, 300, 800, 100))
        // A clock in the centre keeps its full width even past half; the sides ellipsize.
        assertEquals(Triple(150, 700, 150), TitleBarLayout.widths(1000, 900, 700, 900, fixed = setOf(1)))
        // No centre: a fixed end slot can take more than half.
        assertEquals(Triple(300, 0, 700), TitleBarLayout.widths(1000, 900, 0, 700, fixed = setOf(2)))
        // Never wider than the bar.
        assertEquals(Triple(0, 0, 1000), TitleBarLayout.widths(1000, 10, 0, 1500, fixed = setOf(2)))
        val parts = { t: String -> (Template.parse(t) as Template.Ok).parts }
        assertTrue(TitleBarModel.clockOnly(parts("{time:HH:mm}")))
        assertTrue(TitleBarModel.clockOnly(parts("{date} · {time}")))
        assertTrue(TitleBarModel.clockOnly(parts("clock")))
        assertFalse(TitleBarModel.clockOnly(parts("{page} {time}")))
        assertFalse(TitleBarModel.clockOnly(parts("Hello")))
        assertEquals(30f, TitleBarLayout.cappedSp(30f, 1f))
        assertEquals(26f, TitleBarLayout.cappedSp(40f, 2f))
    }

    @Test fun overrideColoursAreContrastChecked() {
        // #118: a page override that darkens the text gets the same §15 warning, at the override's path.
        val ov = ""","overrides":{"t-main":{"style":{"color":"dark"}}}"""
        val c = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"light","backdrop":"scrim"}}""", ov))
        assertEquals(listOf("pages[0].overrides.t-main.style.color: contrast 1.2:1 on the backdrop (4.5:1 recommended). Shown anyway."), Elements.warnings(c))
        // Hidden on that page, or not touching colours: nothing extra.
        val hidden = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"light","backdrop":"scrim"}}""", ""","overrides":{"t-main":{"enabled":false,"style":{"color":"dark"}}}"""))
        assertTrue(Elements.warnings(hidden).isEmpty())
        val size = ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"dark","backdrop":"scrim"}}""", ""","overrides":{"t-main":{"style":{"size":"large"}}}"""))
        assertEquals(1, Elements.warnings(size).size)   // only the element's own warning
        // The phone-only theme check covers overrides too.
        val theme = ok(doc("""{"id":"t-main","type":"titleBar","style":{"backdrop":"scrim"}}""", ""","overrides":{"t-main":{"style":{"color":"primary"}}}"""))
        assertEquals(listOf("pages[0].overrides.t-main.style.color: contrast 1.2:1 on the backdrop (4.5:1 recommended), checked on this phone in light theme. Shown anyway."),
            Elements.phoneWarnings(theme, false) { mapOf("primary" to "#202020")[it] })
    }

    @Test fun spokenLabelExpandsEachTokenOnce() {
        // #133: a page titled "1" next to {pageNumber}/{pageCount} was read "1, page 1 of 2 1, page 1 of 2/2".
        val parts = (Template.parse("{page} {pageNumber}/{pageCount}") as Template.Ok).parts
        val pages = { t: String -> "$t, Pages" }
        val one = TitleBarModel.Values("1", 1, 2, { "" }, { "" })
        assertEquals("1 1/2, Pages", TitleBarModel.spoken(parts, one, button = true, pages = pages))
        assertEquals("Home 1/2, Pages", TitleBarModel.spoken(parts, one.copy(pageName = "Home"), button = true, pages = pages))
        // A page name that's also in literal text or another token's output stays as written.
        val oct = (Template.parse("{page} · Oct {date:d MMM}") as Template.Ok).parts
        assertEquals("Oct · Oct 18 Oct, Pages", TitleBarModel.spoken(oct, one.copy(pageName = "Oct", date = { "18 Oct" }), button = true, pages = pages))
        // One page: not a button, so no ", Pages".
        assertEquals("Home 1/1", TitleBarModel.spoken(parts, TitleBarModel.Values("Home", 1, 1, { "" }, { "" }), button = false, pages = pages))
    }

    @Test fun themeCheckMeasuresTheSchemeHomeDraws() {
        // #150 (Grace): the check measured the default light scheme while the bar draws home's dark one.
        val scheme = dev.nasawa.configlauncher.ui.HomeTheme.scheme()
        val hex = TitleBarLayout.themeHex(scheme)
        for (t in TitleBarLayout.THEME_TOKENS) {
            val drawn = TitleBarLayout.color(t) { TitleBarLayout.themeColor(scheme, it) }
            assertEquals(t, String.format(java.util.Locale.ROOT, "#%06X", drawn.toArgb() and 0xFFFFFF), hex[t])
        }
        assertEquals("#141218", hex["surface"])  // the colour Grace sampled on screen
        val bar = { bg: String -> ok(doc("""{"id":"t-main","type":"titleBar","style":{"color":"surface","backdrop":{"color":"$bg","opacity":100}}}""")) }
        // Repro 1: dark surface text on white is readable, so no warning.
        assertTrue(Elements.phoneWarnings(bar("#FFFFFF"), false) { hex[it] }.isEmpty())
        // Repro 2: the same text on black is nearly invisible, so it's warned.
        assertEquals(1, Elements.phoneWarnings(bar("#000000"), false) { hex[it] }.size)
    }
}
