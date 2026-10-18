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
 * §17 Q16: 2 MiB read cap. A legal 2000-item config with long strings (100-char package,
 * 152-char activity, 60-emoji labels, 64-char IDs, profile written; ~1.46 MiB canonical)
 * must fit, so the launcher can always read its own write-back.
 */
class CanonicalSizeTest {
    @Test fun canonicalWriteOfLongStringMaxConfigFitsReadCap() {
        val pkg = "com." + "a".repeat(96)
        val act = pkg + "." + "B".repeat(151 - pkg.length)
        val label = "\uD83D\uDE00".repeat(60)
        val pages = (0 until 14).joinToString(",") { p ->
            val n = if (p < 13) 144 else 2000 - 13 * 144
            val items = (0 until n).joinToString(",") { i ->
                val id = ("a-$p-$i-" + "x".repeat(64)).take(64)
                """{"id":"$id","type":"app","package":"$pkg","activity":"$act","profile":"personal","label":"$label","x":${i % 12},"y":${i / 12}}"""
            }
            """{"id":"p$p","items":[$items]}"""
        }
        val compact = """{"schemaVersion":1,"grid":{"columns":12,"rows":12},"pages":[$pages]}"""
        val parsed = ConfigCodec.decode(compact.toByteArray())
        if (parsed !is ParseResult.Ok) fail("long-string max config should be valid: ${(parsed as ParseResult.Invalid).errors.take(3)}")
        val written = ConfigCodec.encodeBytes((parsed as ParseResult.Ok).config)
        if (written.size > ConfigCodec.MAX_BYTES)
            fail("canonical write is ${written.size} bytes, over the ${ConfigCodec.MAX_BYTES}-byte cap")
        if (written.size < 1_000_000) fail("fixture should be near the long-string worst case, got ${written.size}")
    }
}
