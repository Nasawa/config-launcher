package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConfigCodecTest {
    private val minimal = File(System.getProperty("repoRoot"), "schema/examples/minimal.json").readText()

    private fun ok(text: String) = (ConfigCodec.decode(text) as? ParseResult.Ok)?.config
        ?: fail("expected valid, got ${ConfigCodec.decode(text)}").let { error("unreachable") }

    private fun errors(text: String) = (ConfigCodec.decode(text) as? ParseResult.Invalid)?.errors
        ?: error("expected invalid: $text")

    @Test fun parsesMinimalExample() {
        val c = ok(minimal)
        assertEquals(1, c.schemaVersion)
        assertEquals(Grid(4, 5), c.grid)
        val home = c.pages.single()
        assertEquals("home", home.id)
        val folder = home.items[2] as FolderItem
        assertEquals("Tools", folder.label)
        assertEquals("com.android.deskclock.DeskClock", (folder.items[1] as AppItem).activity)
        assertEquals(listOf("a-dialer", "a-camera"), c.dock!!.map { it.id })
        assertEquals(UnsortedMode.DRAWER, c.unsortedMode)
    }

    @Test fun roundTripIsStableAndCanonical() {
        val c = ok(minimal)
        val once = ConfigCodec.encode(c)
        assertEquals(c, ok(once))
        assertEquals(once, ConfigCodec.encode(ok(once)))
        assertTrue(once.endsWith("}\n"))
        assertFalse(once.contains("\r"))
        assertTrue(once.contains("\n  \"schemaVersion\": 1,"))
        // Canonical output equals the hand-written example modulo whitespace.
        assertEquals(minimal.replace(Regex("\\s"), ""), once.replace(Regex("\\s"), ""))
    }

    @Test fun preservesXKeysAfterKnownKeys() {
        val c = ok("""{"x-note":"hi","pages":[{"id":"p","items":[],"x-a":1}],"schemaVersion":1}""")
        assertEquals("\"hi\"", c.extras["x-note"].toString())
        val out = ConfigCodec.encode(c)
        assertTrue(out.indexOf("\"x-note\"") > out.indexOf("\"pages\""))
        assertTrue(out.contains("\"x-a\": 1"))
    }

    @Test fun stripsBom() {
        assertTrue(ConfigCodec.decode(("\uFEFF" + minimal).toByteArray()) is ParseResult.Ok)
    }

    @Test fun rejectsBadJson() {
        assertTrue(errors("{").single().startsWith("not valid JSON"))
    }

    @Test fun rejectsOversize() {
        assertTrue(ConfigCodec.decode(ByteArray(ConfigCodec.MAX_BYTES + 1)) is ParseResult.Invalid)
        assertEquals(2 * 1024 * 1024, ConfigCodec.MAX_BYTES)
    }

    @Test fun newerSchemaVersionIsReported() {
        assertTrue(errors("""{"schemaVersion":2,"pages":[]}""").single().contains("newer"))
    }

    @Test fun hashIsHexSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ConfigCodec.sha256(ByteArray(0)))
    }
}
