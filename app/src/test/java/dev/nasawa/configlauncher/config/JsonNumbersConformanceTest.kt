package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.widgetdata.WidgetData
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #120: the phone accepts exactly the RFC 8259 numbers and literals that scripts/validate.py accepts.
 * The case list is shared with scripts/test_validate.py (test-fixtures/json-numbers.json).
 */
class JsonNumbersConformanceTest {
    private val cases = (File("../test-fixtures/json-numbers.json").takeIf { it.isFile } ?: File("test-fixtures/json-numbers.json"))
        .readText().let { Json.parseToJsonElement(it).jsonObject }
    private val template = cases["template"]!!.jsonPrimitive.content
    private fun list(k: String) = cases[k]!!.jsonArray.map { it.jsonPrimitive.content }
    private fun doc(token: String) = template.replace("@", token)

    /** The message both sides give: the first bad token is in the x-a array on line 4. */
    private fun expected(token: String) =
        "not valid JSON: invalid ${if (token[0] in "+-.0123456789") "number" else "value"} \"$token\" (line 4)"

    @Test fun acceptsEveryRfcNumberAndLiteral() {
        val problems = list("accept").mapNotNull { t ->
            when (val r = ConfigCodec.decode(doc(t))) {
                is ParseResult.Ok -> {
                    // The canonical write-back must itself load (no token is passed through unparsed).
                    val again = ConfigCodec.decode(ConfigCodec.encode(r.config))
                    if (again is ParseResult.Ok) null else "$t: write-back does not reload: ${(again as ParseResult.Invalid).errors}"
                }
                is ParseResult.Invalid -> "$t: rejected ${r.errors}"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun rejectsEveryNonRfcToken() {
        val problems = list("reject").mapNotNull { t ->
            val r = ConfigCodec.decode(doc(t))
            val got = (r as? ParseResult.Invalid)?.errors
            if (got == listOf(expected(t))) null else "$t: got ${got ?: "Ok"}, want ${expected(t)}"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun widgetDataUsesTheSameRule() {
        val bad = list("reject").filter { WidgetData.parse(doc(it).toByteArray()) !is WidgetData.Parsed.Bad }
        val good = list("accept").filter { WidgetData.parse(doc(it).toByteArray()) !is WidgetData.Parsed.Ok }
        assertEquals("accepted", emptyList<String>(), bad)
        assertEquals("rejected", emptyList<String>(), good)
    }

    @Test fun longTokenIsCutLikeTheScript() {
        val t = "0" + "1".repeat(60)
        assertEquals("invalid number \"${t.take(40)}…\" (line 1)", JsonPreScan.check(t))
        val e = "\uD83D\uDE00".repeat(50)   // cut by code points, never inside a surrogate pair
        assertEquals("invalid value \"${"\uD83D\uDE00".repeat(40)}…\" (line 2)", JsonPreScan.check("\n" + e))
    }

    @Test fun errorLocatorPointsAtTheLine() {
        val text = doc("00")
        val err = (ConfigCodec.decode(text) as ParseResult.Invalid).errors
        assertEquals(4, ErrorLocator.locate(text, err).single()!!.line)
    }
}
