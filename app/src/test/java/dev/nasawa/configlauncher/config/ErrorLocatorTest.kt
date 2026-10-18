package dev.nasawa.configlauncher.config

import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ErrorLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val text = """{
  "schemaVersion": 1,
  "pages": [
    {
      "id": "home",
      "items": [
        { "id": "a", "type": "app", "package": "p.a", "x": 0, "y": 0 },
        { "id": "b", "type": "app", "x": 9, "y": 0 }
      ]
    }
  ]
}
"""

    private fun errorsOf(t: String) = (ConfigCodec.decode(t) as ParseResult.Invalid).errors

    @Test fun validatorPathPointsAtKey() {
        val errs = errorsOf(text)
        val locs = ErrorLocator.locate(text, errs)
        val xErr = errs.indexOfFirst { it.startsWith("pages[0].items[1].x") }
        assertTrue(errs.toString(), xErr >= 0)
        assertEquals(TextPos(8, 37), locs[xErr])
    }

    @Test fun missingKeyFallsBackToEnclosingObject() {
        val locs = ErrorLocator.locate(text, listOf("pages[0].items[1].package: missing", "pages[0].items[1]: missing required \"package\""))
        assertEquals(TextPos(8, 9), locs[0])
        assertEquals(TextPos(8, 9), locs[1])
    }

    @Test fun topLevelKey() =
        assertEquals(TextPos(2, 3), ErrorLocator.locate(text, listOf("schemaVersion: must be 1"))[0])

    @Test fun preScanLineMarker() {
        val t = "{\n  \"a\": 1,\n    \"a\": 2\n}"
        val e = errorsOf(t)
        assertTrue(e[0], e[0].contains("(line 3)"))
        assertEquals(TextPos(3, 5), ErrorLocator.locate(t, e)[0])
    }

    @Test fun parserOffset() {
        val t = "{\n  \"schemaVersion\": 1,\n  \"pages\": [ x ]\n}"
        val e = errorsOf(t)
        val p = ErrorLocator.locate(t, e)[0]
        assertNotNull(e.toString(), p)
        assertEquals(3, p!!.line)
    }

    @Test fun unknownFormatsGiveNull() =
        assertNull(ErrorLocator.locate(text, listOf("not valid UTF-8"))[0])

    @Test fun truncatedJsonStillLocatesEarlierPaths() =
        assertEquals(TextPos(2, 3), ErrorLocator.locate("{\n  \"schemaVersion\": 1,\n  \"pages\": [", listOf("schemaVersion: x"))[0])

    @Test fun engineLocatesOnlyOnDemand() = runBlocking {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(text)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0)
        assertEquals(listOf(null), e.locateErrors(listOf("schemaVersion: x")))
        e.poll(force = true)
        val errs = e.state.value.errors
        assertTrue(e.locateErrors(errs).any { it?.line == 8 })
    }
}
