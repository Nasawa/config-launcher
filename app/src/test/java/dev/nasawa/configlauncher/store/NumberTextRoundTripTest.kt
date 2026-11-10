package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR #260 review: a number the app didn't edit keeps its exact text when the phone rewrites launcher.json.
 * Before, kotlinx's JsonLiteralSerializer re-encoded every number as Long, ULong or Double, so
 * -12345678901234567890 became -1.2345678901234567E19, 1E+05 became 100000.0, 0.10 became 0.1 and -0 became 0.
 */
class NumberTextRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()

    private val nums = listOf("-12345678901234567890", "12345678901234567890", "1E+05", "2.50E2", "1e-7", "0.10", "-0", "-0.0", "9007199254740993", "1.0")
    private val arr = nums.joinToString(", ")

    /**
     * Numbers in unknown fields (x- keys at every level, an element type this build skips) and in known fields the
     * app keeps as a tree (a title bar's style.size and x- key, a page override).
     */
    private val file = """{
  "schemaVersion": 1,
  "revision": 1,
  "grid": {"columns": 4, "rows": 5},
  "pages": [
    {
      "id": "home",
      "items": [
        {"id": "a", "type": "app", "package": "p.a", "x": 0, "y": 0, "x-n": [$arr]},
        {"id": "b", "type": "app", "package": "p.b", "x": 1, "y": 0}
      ],
      "overrides": {"t-main": {"style": {"size": 2.0E1}}},
      "x-page": -0
    }
  ],
  "elements": [
    {"id": "t-main", "type": "titleBar", "style": {"size": 1.250E1}, "x-n": [$arr]},
    {"id": "w-future", "type": "gauge", "page": "home", "x": 0, "y": 2, "w": 2, "h": 1, "max": 1E+05, "data": {"n": [$arr]}}
  ],
  "x-top": {"n": [$arr], "big": -12345678901234567890}
}
"""

    private fun JsonElement.at(vararg path: Any): JsonPrimitive = path.fold(this) { e, k ->
        if (k is Int) e.jsonArray[k] else e.jsonObject[k as String]!!
    }.jsonPrimitive

    private fun numbersOf(e: JsonElement) = e.jsonArray.map { it.jsonPrimitive.also { p -> assertFalse(p.isString) }.content }

    private fun assertKept(written: String) {
        val t = Json.parseToJsonElement(written)
        assertEquals(nums, numbersOf(t.jsonObject["pages"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray[0].jsonObject["x-n"]!!))
        assertEquals("-0", t.at("pages", 0, "x-page").content)
        assertEquals("2.0E1", t.at("pages", 0, "overrides", "t-main", "style", "size").content)
        val els = t.jsonObject["elements"]!!.jsonArray
        assertEquals("1.250E1", els.at(0, "style", "size").content)
        assertEquals(nums, numbersOf(els[0].jsonObject["x-n"]!!))
        assertEquals("1E+05", els.at(1, "max").content)
        assertEquals(nums, numbersOf(els[1].jsonObject["data"]!!.jsonObject["n"]!!))
        assertEquals(nums, numbersOf(t.jsonObject["x-top"]!!.jsonObject["n"]!!))
        assertTrue(written, "\"big\": -12345678901234567890\n" in written)
        assertFalse(written, "E19" in written || "100000.0" in written || "0.1," in written)
    }

    @Test fun encodeKeepsNumberText() {
        val c = (ConfigCodec.decode(file) as ParseResult.Ok).config
        val out = ConfigCodec.encode(c)
        assertKept(out)
        assertEquals("the rewrite is canonical", out, ConfigCodec.encode((ConfigCodec.decode(out) as ParseResult.Ok).config))
    }

    @Test fun saveAfterAnEditKeepsUneditedNumberText() = runBlocking {
        val src = LocalFileSource(tmp.newFolder("src"))
        src.file.writeText(file); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0, clock = { 1_700_000_000_000L })
        e.poll()
        assertTrue(e.edit(Edit.MoveToCell("b", "home", 3, 4)))
        assertTrue(e.flush())
        val written = src.file.readText()
        assertKept(written)
        val c = (ConfigCodec.decode(written) as ParseResult.Ok).config
        assertEquals(3 to 4, c.pages[0].items.first { it.id == "b" }.let { it.x to it.y })   // the edited field, normal form
        assertEquals(2L, c.revision)
    }

    @Test fun anEditedNumberIsWrittenInNormalForm() {
        val c = (ConfigCodec.decode(file) as ParseResult.Ok).config
        val r = Edits.apply(c, Edit.SetElement("t-main", listOf("style", "size"), JsonPrimitive(18)))
        assertTrue(r.applied)
        val out = ConfigCodec.encode(r.config)
        assertEquals("18", Json.parseToJsonElement(out).at("elements", 0, "style", "size").content)
    }

    /** decodeLastGood still turns a pre-#120 bare token into strict JSON on write-back (00 -> 0, - -> "-"). */
    @Test fun lastGoodBareTokensStillWriteStrictJson() {
        val old = file.replace("\"x-page\": -0", "\"x-page\": [00, -, 1E+05]")
        val c = (ConfigCodec.decodeLastGood(old.toByteArray()) as ParseResult.Ok).config
        val out = ConfigCodec.encode(c)
        assertTrue(ConfigCodec.decode(out) is ParseResult.Ok)
        val xs = Json.parseToJsonElement(out).jsonObject["pages"]!!.jsonArray[0].jsonObject["x-page"]!!.jsonArray
        assertEquals(listOf("0", "-", "1E+05"), xs.map { it.jsonPrimitive.content })
        assertEquals(listOf(false, true, false), xs.map { it.jsonPrimitive.isString })
    }
}
