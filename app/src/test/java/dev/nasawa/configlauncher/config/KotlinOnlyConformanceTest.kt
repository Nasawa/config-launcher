package dev.nasawa.configlauncher.config

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.*
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * ARCHITECTURE.md §17 Q2: Kotlin rejects ⊇ schema rejects, both accept valid/, and every fixture
 * tagged with a Kotlin-only rule in manifest.json must be ACCEPTED by the JSON Schema validator,
 * so the list of Kotlin-only rules stays honest.
 */
class KotlinOnlyConformanceTest {
    private val kotlinOnlyRules = setOf(
        "duplicate-ids", "overlapping-cells", "cell-outside-grid", "item-total",
        "duplicate-keys", "parser-depth", "byte-cap", "nul-or-encoding", "template-grammar", "single-title-bar", "number-range", "card-capability-ref",
    )
    private val dir = File(javaClass.classLoader!!.getResource("configs/manifest.json")!!.toURI()).parentFile
    private val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonArray.map { it.jsonObject }
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
        File(System.getProperty("repoRoot"), "schema/launcher-config.v1.schema.json").readText(), InputFormat.JSON)

    /** null = the schema side can't parse it (e.g. Jackson's nesting limit). */
    private fun schemaAccepts(raw: ByteArray): Boolean? {
        val text = when {
            raw.size >= 2 && ((raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) || (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())) -> String(raw, Charsets.UTF_16)
            else -> String(raw, Charsets.UTF_8).removePrefix("\uFEFF")
        }
        return try { schema.validate(text, InputFormat.JSON).isEmpty() } catch (e: Exception) { null }
    }

    @Test fun kotlinOnlyListIsHonestAndKotlinIsStricter() {
        val problems = mutableListOf<String>()
        for (e in manifest) {
            val file = e["file"]!!.jsonPrimitive.content
            val rule = e["kotlinOnly"]?.jsonPrimitive?.content
            val layer = e["layer"]!!.jsonPrimitive.content
            val raw = File(dir, file).readBytes()
            val kotlinOk = runCatching { ConfigCodec.decode(raw) is ParseResult.Ok }.getOrDefault(false)
            if (rule != null && rule !in kotlinOnlyRules) { problems += "$file: unknown Kotlin-only rule $rule"; continue }
            when {
                file.startsWith("valid/") -> {
                    if (!kotlinOk) problems += "$file: Kotlin rejects a valid fixture"
                    if (schemaAccepts(raw) == false) problems += "$file: schema rejects a valid fixture"
                }
                rule != null -> {
                    if (kotlinOk) problems += "$file: Kotlin accepts (rule $rule)"
                    if (schemaAccepts(raw) == false) problems += "$file: schema rejects, so '$rule' isn't Kotlin-only"
                }
                layer == "both" -> {
                    if (schemaAccepts(raw) != false) problems += "$file: schema should reject"
                    if (kotlinOk) problems += "$file: Kotlin accepts what the schema rejects"
                }
                else -> if (kotlinOk) problems += "$file: Kotlin accepts a $layer fixture"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
