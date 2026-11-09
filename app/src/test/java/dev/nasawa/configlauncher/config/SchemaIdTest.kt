package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.store.DefaultLayout
import org.junit.Assert.*
import org.junit.Test

/** #242: old GitHub and new reflowlauncher.com `$schema` both validate; round-trip preserves whichever was there. */
class SchemaIdTest {
    private val oldUrl = "https://github.com/Nasawa/config-launcher/schema/launcher-config.v1.schema.json"
    private val newUrl = "https://reflowlauncher.com/schema/launcher-config.v1.schema.json"

    private fun doc(schema: String) = """
        {"${'$'}schema":"$schema","schemaVersion":1,"pages":[{"id":"home","items":[]}]}
    """.trimIndent()

    private fun ok(text: String): dev.nasawa.configlauncher.model.LauncherConfig {
        val r = ConfigCodec.decode(text)
        assertTrue("expected Ok, got $r", r is ParseResult.Ok)
        return (r as ParseResult.Ok).config
    }

    @Test fun oldGitHubSchemaUrlLoadsWithNoErrorOrWarning() {
        val c = ok(doc(oldUrl))
        assertEquals(oldUrl, c.schemaRef)
        assertTrue("unexpected warnings: ${Elements.warnings(c)}", Elements.warnings(c).isEmpty())
    }

    @Test fun newReflowSchemaUrlLoadsWithNoErrorOrWarning() {
        val c = ok(doc(newUrl))
        assertEquals(newUrl, c.schemaRef)
        assertTrue("unexpected warnings: ${Elements.warnings(c)}", Elements.warnings(c).isEmpty())
    }

    @Test fun roundTripPreservesOldSchemaUrl() {
        val once = ConfigCodec.encode(ok(doc(oldUrl)))
        assertTrue(once, oldUrl in once)
        assertEquals(oldUrl, ok(once).schemaRef)
        assertFalse(newUrl in once)
    }

    @Test fun roundTripPreservesNewSchemaUrl() {
        val once = ConfigCodec.encode(ok(doc(newUrl)))
        assertTrue(once, newUrl in once)
        assertEquals(newUrl, ok(once).schemaRef)
    }

    @Test fun defaultLayoutWritesNewUrl() {
        assertEquals(newUrl, DefaultLayout.config.schemaRef)
        assertTrue(newUrl in ConfigCodec.encode(DefaultLayout.config))
    }
}
