package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #55: applied-revision receipt and the agent reload. */
class ReloadReceiptTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun doc(rev: Int, pkg: String = "a.b") = """{"schemaVersion":1,"revision":$rev,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"$pkg","x":0,"y":0}]}]}"""
    private var now = 1_000L

    private fun setup(): Pair<ConfigEngine, LocalFileSource> {
        val src = LocalFileSource(tmp.newFolder()); src.file.writeText(doc(7))
        return ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { now }) to src
    }
    private fun status(src: LocalFileSource) = Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject

    @Test fun appliedRevisionNextToHash() = runBlocking {
        val (e, src) = setup()
        assertEquals(ReloadOutcome.RELOADED, e.reloadForAgent())
        val s = status(src)
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(7L, s["revision"]!!.jsonPrimitive.long)
        assertEquals(ConfigCodec.sha256(src.file.readBytes()), s["hash"]!!.jsonPrimitive.content)
    }

    @Test fun rejectedFileHasNullRevision() = runBlocking {
        val (e, src) = setup(); e.poll()
        src.file.writeText(doc(8).replace("\"x\":0", "\"x\":99")); src.file.setLastModified(5000)
        assertEquals("#75: not reported as unchanged", ReloadOutcome.REJECTED, e.reloadForAgent())
        val s = status(src)
        assertNotEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, s["revision"])
    }

    @Test fun reloadRewritesStatusAsReceiptEvenWhenUnchanged() = runBlocking {
        val (e, src) = setup(); e.poll()
        val t1 = status(src)["time"]!!.jsonPrimitive.content
        now += 60_000
        assertEquals(ReloadOutcome.UNCHANGED, e.reloadForAgent())
        assertNotEquals(t1, status(src)["time"]!!.jsonPrimitive.content)
    }

    @Test fun missingFileIsUnavailable() = runBlocking {
        val (e, src) = setup(); e.poll()
        src.file.delete()
        assertEquals(ReloadOutcome.UNAVAILABLE, e.reloadForAgent())
    }

    @Test fun pendingEditIsBusy() = runBlocking {
        val (e, _) = setup(); e.poll()
        e.edit(dev.nasawa.configlauncher.model.Edit.MoveToCell("a", "home", 1, 1))
        assertEquals(ReloadOutcome.BUSY, e.reloadForAgent())
    }

    @Test fun codesAreDistinct() {
        assertEquals(ReloadOutcome.entries.size, ReloadOutcome.entries.map { it.code }.toSet().size)
        assertEquals(listOf(0, 1), listOf(ReloadOutcome.UNCHANGED.code, ReloadOutcome.RELOADED.code))   // as documented before #75
    }
}
