package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Runs every fixture in src/test/resources/configs (from docs/test-plan.md, manifest.json gives the
 * expected outcome and ARCHITECTURE.md reference) through ConfigCodec.decode(bytes).
 * valid/ must decode to Ok; invalid/ must decode to Invalid and never throw.
 */
class FixtureConformanceTest {
    private val dir = File(javaClass.classLoader!!.getResource("configs/manifest.json")!!.toURI()).parentFile
    private val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonArray.map { it.jsonObject }

    @Test fun everyFixtureMatchesManifest() {
        val problems = mutableListOf<String>()
        for (e in manifest) {
            val file = e["file"]!!.jsonPrimitive.content
            val want = file.startsWith("valid/")
            val result = try { ConfigCodec.decode(File(dir, file).readBytes()) } catch (t: Throwable) {
                problems += "$file: threw ${t::class.simpleName} (${e["ref"]!!.jsonPrimitive.content})"; continue
            }
            val ok = result is ParseResult.Ok
            if (ok != want) problems += "$file: expected ${if (want) "valid" else "invalid"}, got " +
                (if (ok) "valid" else "invalid ${(result as ParseResult.Invalid).errors.take(2)}") +
                " (${e["ref"]!!.jsonPrimitive.content})"
        }
        if (problems.isNotEmpty()) fail("${problems.size} of ${manifest.size} fixtures disagree:\n" + problems.joinToString("\n"))
    }
}

/**
 * A§15 OQ-9 allows 2000 items, but the launcher reads at most 256 KiB. A legal near-max config,
 * once written back in canonical (pretty) form, must still be readable by the launcher itself.
 */
class CanonicalSizeTest {
    @Test fun canonicalWriteOfMaxLegalConfigFitsReadCap() {
        val pages = (0 until 14).joinToString(",") { p ->
            val n = if (p < 13) 144 else 2000 - 13 * 144
            val items = (0 until n).joinToString(",") { i ->
                """{"id":"a-$p-$i","type":"app","package":"com.example.app$i","x":${i % 12},"y":${i / 12}}"""
            }
            """{"id":"p$p","items":[$items]}"""
        }
        val compact = """{"schemaVersion":1,"grid":{"columns":12,"rows":12},"pages":[$pages]}"""
        val parsed = ConfigCodec.decode(compact.toByteArray())
        if (parsed !is ParseResult.Ok) fail("near-max config should be valid: ${(parsed as ParseResult.Invalid).errors.take(3)}")
        val written = ConfigCodec.encodeBytes((parsed as ParseResult.Ok).config)
        if (written.size > ConfigCodec.MAX_BYTES)
            fail("canonical write is ${written.size} bytes, over the ${ConfigCodec.MAX_BYTES}-byte read cap: the launcher would reject its own file")
    }
}
