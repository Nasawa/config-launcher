package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Reconcile
import org.junit.Assert.*
import org.junit.Test

/** AF-2 (§18.5): `unsorted` is type-checked, otherwise ignored, and never written. */
class UnsortedIgnoredTest {
    private fun doc(u: String) = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}],"unsorted":$u}"""

    @Test fun validValuesLoadAndAreIgnored() {
        for (mode in listOf("drawer", "page", "hidden")) {
            val r = ConfigCodec.decode(doc("""{"mode":"$mode","pageTitle":"Inbox"}"""))
            assertTrue(mode, r is ParseResult.Ok)
            val layout = Reconcile.resolve((r as ParseResult.Ok).config, listOf(InstalledApp("a.b", "a.b.M", "A")))
            assertEquals("no Unsorted page for $mode", 1, layout.pages.size)
            assertEquals(1, layout.drawer.size)
        }
    }

    @Test fun typosAreStillErrors() {
        assertTrue(ConfigCodec.decode(doc("""{"mode":"sideways"}""")) is ParseResult.Invalid)
        assertTrue(ConfigCodec.decode(doc("""{"pageTitle":7}""")) is ParseResult.Invalid)
        assertTrue(ConfigCodec.decode(doc("""{"pageTitle":"${"x".repeat(41)}"}""")) is ParseResult.Invalid)
        assertTrue(ConfigCodec.decode(doc("""{"mood":"drawer"}""")) is ParseResult.Invalid)
    }

    @Test fun serializerNeverWritesUnsorted() {
        val c = (ConfigCodec.decode(doc("""{"mode":"page"}""")) as ParseResult.Ok).config
        assertFalse(ConfigCodec.encode(c).contains("unsorted"))
        assertFalse(ConfigCodec.encode(dev.nasawa.configlauncher.store.DefaultLayout.config).contains("unsorted"))
    }
}
