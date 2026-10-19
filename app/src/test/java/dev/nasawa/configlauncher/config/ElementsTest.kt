package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.model.*
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

/** #79 elements container (feature level 1) and Stage 1 cell reservation (docs/research/widgets.md §5.1). */
class ElementsTest {
    @get:Rule val tmp = TemporaryFolder()

    // A 2x2 skipped widget at (0,0)-(1,1) on "home"; one app at (2,0). Grid 4x5.
    private val widget = """{"id":"w-weather","type":"appWidget","page":"home","x":0,"y":0,"w":2,"h":2,"provider":"com.example.w/.W","x-note":{"a":[1,true,null,"s"]}}"""
    private fun doc(elements: String = "[$widget]", extra: String = "") =
        """{"schemaVersion":1,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":2,"y":0}]$extra},{"id":"empty","items":[]}],"elements":$elements}"""
    private fun ok(s: String) = (ConfigCodec.decode(s) as? ParseResult.Ok)?.config ?: error("invalid: ${(ConfigCodec.decode(s) as ParseResult.Invalid).errors}")
    private fun errors(s: String) = (ConfigCodec.decode(s) as ParseResult.Invalid).errors
    private val app = AppItem("b", "p.b")

    @Test fun featureLevelTwoKnowsTheTitleBar() {
        assertEquals(2, Elements.FEATURE_LEVEL)
        assertEquals(setOf("titleBar"), Elements.types.keys)
    }

    @Test fun unknownElementIsSkippedWithWarningAndKeptVerbatim() {
        val c = ok(doc())
        assertTrue(Elements.shown(c).isEmpty())
        assertEquals(listOf("elements[0]: type \"appWidget\" isn't supported by this Config Launcher, so it was skipped. It stays in the file."), Elements.warnings(c))
        val out = ConfigCodec.encode(c)
        // Verbatim: unknown keys, x- keys and nested values survive a phone write.
        val back = Json.parseToJsonElement(out).jsonObject["elements"]!!.jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement(widget), back)
        assertEquals(out, ConfigCodec.encode(ok(out)))
    }

    @Test fun requiresAboveFeatureLevelIsSkippedBeforeStrictChecks() {
        val c = ok(doc("""[{"id":"t-x","type":"titleBar","requires":3,"anchor":"top","whatever":true}]"""))
        assertEquals(listOf("elements[0] needs feature level 3, but this Config Launcher supports up to 2, so it was skipped. It stays in the file. Update the app to show it."), Elements.warnings(c))
    }

    @Test fun overrideForMissingElementIsAWarning() {
        val c = ok(doc(extra = ""","overrides":{"w-weather":{"enabled":false},"t-gone":{"style":{}}}"""))
        assertEquals("pages[0].overrides: \"t-gone\" doesn't match any element, so it was ignored.", Elements.warnings(c).last())
        assertTrue(ConfigCodec.encode(c).contains("\"t-gone\""))
    }

    @Test fun baseBlockErrors() {
        assertEquals(listOf("elements[0].page: no page with id \"nope\""), errors(doc("""[{"id":"w","type":"x","page":"nope","x":0,"y":0,"w":1,"h":1}]""")))
        assertTrue(errors(doc("""[{"id":"w","type":"x","page":"home","x":2,"y":0,"w":1,"h":1}]""")).single().startsWith("elements[0]: overlaps pages[0].items[0]"))
        assertTrue(errors(doc("""[{"id":"w","type":"x","page":"home","x":0,"y":4,"w":1,"h":2}]""")).single().contains("outside the grid (5 rows)"))
        assertTrue(errors(doc("""[{"id":"w","type":"x","requires":0}]""")).single().startsWith("elements[0].requires"))
        assertTrue(errors(doc("""[{"id":"w","type":"x","page":"home","x":0,"y":3,"w":0,"h":1}]""")).single().startsWith("elements[0].w"))
    }

    // ---- Stage 1: skipped grid elements still reserve their cells ----

    @Test fun reservedCellsComeFromSkippedElements() {
        val c = ok(doc())
        assertEquals(setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), Elements.reserved(c, "home"))
        assertTrue(Elements.reserved(c, "empty").isEmpty())
    }

    @Test fun moveOntoReservedCellIsRefused() {
        val c = ok(doc())
        assertFalse(Edits.apply(c, Edit.MoveToCell("a", "home", 1, 1)).applied)
        assertTrue(Edits.apply(c, Edit.MoveToCell("a", "home", 3, 3)).applied)
    }

    @Test fun addAndAddToHomeSkipReservedCells() {
        val c = ok(doc())
        val r = Edits.apply(c, Edit.Add(app, "home", 0, 0)).config
        val placed = r.pages[0].items.single { it.id == "b" }
        assertFalse((placed.x!! to placed.y!!) in Elements.reserved(c, "home"))
        assertEquals(3 to 0, placed.x to placed.y)   // first free cell after (0,0) in reading order
        assertEquals(null, Edits.freeCell(c, c.pages[0], 0, 0)?.takeIf { it in Elements.reserved(c, "home") })
    }

    @Test fun pageHoldingOnlyAnElementIsNotDroppedAsEmpty() {
        val c = ok("""{"schemaVersion":1,"pages":[{"id":"home","items":[]},{"id":"w","items":[]}],"elements":[{"id":"w-1","type":"clock","page":"w","x":0,"y":0,"w":1,"h":1}]}""")
        assertEquals(listOf("home"), Edits.emptyPageIds(c))
        val r = Edits.apply(c, Edit.DropPages(listOf("home", "w"))).config
        assertEquals(listOf("w"), r.pages.map { it.id })
        assertEquals(1, r.elements!!.size)
    }

    @Test fun everyPhoneWriteStillValidatesSoNoOverlapCanBeWritten() {
        // Whatever edit path runs, the canonical output must decode (the validator rejects element overlaps).
        val c = ok(doc())
        for (e in listOf(Edit.MoveToCell("a", "home", 0, 0), Edit.Add(app, "home", 1, 1), Edit.AddToHome(app))) {
            val r = Edits.apply(c, e)
            assertTrue("$e", ConfigCodec.decode(ConfigCodec.encode(r.config)) is ParseResult.Ok)
        }
    }

    @Test fun statusJsonCarriesWarnings() = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(doc())
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0); e.poll(force = true)
        val s = Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(1, s["warnings"]!!.jsonArray.size)
    }

    @Test fun statusJsonAddsThemeContrastCheckedOnThisPhone() = runBlocking {
        // #125: the UI hands the engine its theme colours; the phone-only warning lands in status.json and follows changes.
        val src = LocalFileSource(tmp.newFolder())
        src.file.writeText(doc("""[{"id":"t-main","type":"titleBar","style":{"color":"primary","backdrop":"scrim"}}]"""))
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0); e.poll(force = true)
        fun warnings() = Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(emptyList<String>(), warnings())
        e.setPhoneTheme(false, mapOf("primary" to "#303030"))
        assertEquals(listOf("elements[0].style.color: contrast 1.5:1 on the backdrop (4.5:1 recommended), checked on this phone in light theme. Shown anyway."), warnings())
        e.setPhoneTheme(false, mapOf("primary" to "#FFFFFF"))
        assertEquals(emptyList<String>(), warnings())
    }
}
