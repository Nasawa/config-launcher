package dev.nasawa.configlauncher.config

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Keeps the Kotlin [Validator] in lockstep with schema/launcher-config.v1.schema.json
 * (ARCHITECTURE.md §2): every example must pass both, every structural fixture must fail both.
 * Semantic-only rules (unique IDs, grid bounds, overlaps) are rejected by Kotlin alone.
 */
class SchemaAgreementTest {
    private val root = File(System.getProperty("repoRoot"))
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(File(root, "schema/launcher-config.v1.schema.json").readText(), InputFormat.JSON)

    private fun schemaErrors(s: String) = schema.validate(s, InputFormat.JSON)
    private fun kotlinErrors(s: String) = Validator.validate(Json.parseToJsonElement(s))

    private fun wrap(item: String) = """{"schemaVersion":1,"pages":[{"id":"p","items":[$item]}]}"""
    private val structural = listOf(
        "{}",
        """{"schemaVersion":2,"pages":[{"id":"p","items":[]}]}""",
        """{"schemaVersion":1,"pages":[]}""",
        """{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"bogus":true}""",
        """{"schemaVersion":1,"grid":{"columns":13},"pages":[{"id":"p","items":[]}]}""",
        wrap("""{"id":"a","type":"app","package":"a.b"}"""),
        wrap("""{"id":"a","type":"app","package":"nodot","x":0,"y":0}"""),
        wrap("""{"id":"Bad","type":"app","package":"a.b","x":0,"y":0}"""),
        wrap("""{"id":"a","type":"widget","x":0,"y":0}"""),
        wrap("""{"id":"a","type":"app","package":"a.b","profile":"private","x":0,"y":0}"""),
        wrap("""{"id":"a","type":"shortcut","package":"a.b","x":0,"y":0}"""),
        wrap("""{"id":"f","type":"folder","x":0,"y":0,"items":[{"id":"g","type":"folder","items":[]}]}"""),
        wrap("""{"id":"f","type":"folder","x":0,"y":0,"items":[{"id":"a","type":"app","package":"a.b","x":0,"y":0}]}"""),
        """{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"dock":[{"id":"a","type":"app","package":"a.b","x":0,"y":0}]}""",
        """{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"unsorted":{"mode":"sideways"}}""",
        """{"schemaVersion":1,"pages":[{"id":"p","items":[]}],"drawer":{"rules":[{"id":"r","group":"G","match":{}}]}}""",
    )
    private val semanticOnly = listOf(
        wrap("""{"id":"a","type":"app","package":"a.b","x":0,"y":0},{"id":"a","type":"app","package":"a.c","x":1,"y":0}"""),
        wrap("""{"id":"a","type":"app","package":"a.b","x":0,"y":0},{"id":"b","type":"app","package":"a.c","x":0,"y":0}"""),
        wrap("""{"id":"a","type":"app","package":"a.b","x":9,"y":0}"""),
    )

    @Test fun examplesPassBoth() {
        val examples = File(root, "schema/examples").listFiles { f -> f.extension == "json" }!!
        assertTrue(examples.isNotEmpty())
        for (f in examples) {
            val t = f.readText()
            assertEquals(f.name, emptyList<Any>(), schemaErrors(t))
            assertEquals(f.name, emptyList<String>(), kotlinErrors(t))
        }
    }

    @Test fun structuralFixturesFailBoth() {
        for (s in structural) {
            assertTrue("schema accepted $s", schemaErrors(s).isNotEmpty())
            assertTrue("kotlin accepted $s", kotlinErrors(s).isNotEmpty())
        }
    }

    @Test fun semanticFixturesFailKotlinOnly() {
        for (s in semanticOnly) {
            assertEquals("schema should accept $s", emptyList<Any>(), schemaErrors(s))
            assertTrue("kotlin accepted $s", kotlinErrors(s).isNotEmpty())
        }
    }
}
