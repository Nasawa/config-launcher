package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #56: pins what the phone says about every fixture (error lines, or the canonical rewrite's SHA-256)
 * in test-fixtures/phone-output.json. scripts/test_validate.py checks scripts/validate.py against the
 * same file, so the script and the phone can't drift apart.
 * Regenerate after an intentional change: UPDATE_PHONE_OUTPUT=1 ./gradlew testDebugUnitTest --tests '*PhoneOutputGolden*'
 */
class PhoneOutputGoldenTest {
    private val dir = File(javaClass.classLoader!!.getResource("configs/manifest.json")!!.toURI()).parentFile
    private val golden = File("../test-fixtures/phone-output.json").takeIf { it.parentFile.isDirectory } ?: File("test-fixtures/phone-output.json")

    private fun actual(): JsonObject = buildJsonObject {
        val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonArray
        for (e in manifest.map { it.jsonObject["file"]!!.jsonPrimitive.content }.sorted()) {
            putJsonObject(e) {
                when (val r = ConfigCodec.decode(File(dir, e).readBytes())) {
                    is ParseResult.Invalid -> putJsonArray("errors") { r.errors.forEach { add(it) } }
                    is ParseResult.Ok -> {
                        put("canonicalSha256", ConfigCodec.sha256(ConfigCodec.encodeBytes(r.config)))
                        // #79: load warnings (status.json `warnings`), only when there are some.
                        Elements.warnings(r.config).takeIf { it.isNotEmpty() }?.let { w -> putJsonArray("warnings") { w.forEach { add(it) } } }
                    }
                }
            }
        }
    }

    @Test fun phoneOutputMatchesGolden() {
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        val text = pretty.encodeToString(JsonObject.serializer(), actual()) + "\n"
        if (System.getenv("UPDATE_PHONE_OUTPUT") == "1") golden.writeText(text)
        assertEquals("phone output changed; if intended, regenerate (see class doc) and update scripts/validate.py", golden.readText(), text)
    }
}
