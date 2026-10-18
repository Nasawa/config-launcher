package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PR: `.configlauncher/status.json` and `.configlauncher/broken/` (ARCHITECTURE.md §16/§17, OQ-15). */
class FolderSidecarTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good = """{"schemaVersion":1,"revision":1,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]}]}"""
    private val now = 1_790_000_000_000L // 2026-09-21T14:13:20Z

    private fun setup(): Triple<ConfigEngine, LocalFileSource, LastGoodStore> {
        val src = LocalFileSource(tmp.newFolder("src"))
        src.file.writeText(good); src.file.setLastModified(1000)
        val lg = LastGoodStore(tmp.newFolder("lg"))
        return Triple(ConfigEngine(src, lg, stabilityMs = 0, clock = { now }), src, lg)
    }

    private fun status(src: LocalFileSource) =
        Json.parseToJsonElement(File(src.dir, ".configlauncher/status.json").readText()).jsonObject

    @Test fun okLoadWritesStatus() = runBlocking {
        val (e, src) = setup()
        e.poll()
        val s = status(src)
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(0, s["errors"]!!.jsonArray.size)
        assertEquals(e.state.value.baseHash, s["hash"]!!.jsonPrimitive.content)
        assertEquals("2026-09-21T14:13:20Z", s["time"]!!.jsonPrimitive.content)
    }

    @Test fun invalidLoadWritesErrors() = runBlocking {
        val (e, src) = setup()
        e.poll()
        src.file.writeText("""{"schemaVersion":1,"pages":[{"id":"home","items":[{"id":"a","type":"app","x":0,"y":0}]}]}"""); src.file.setLastModified(2000)
        e.poll()
        val s = status(src)
        assertEquals("invalid", s["result"]!!.jsonPrimitive.content)
        assertTrue(s["errors"]!!.jsonArray.isNotEmpty())
        src.file.writeText("{ broken"); src.file.setLastModified(3000)
        e.poll()
        assertEquals("parse_error", status(src)["result"]!!.jsonPrimitive.content)
    }

    @Test fun sidecarWritesDoNotTriggerReload() = runBlocking {
        val (e, src) = setup()
        e.poll()
        val stamp = src.stamp()
        val statusFile = File(src.dir, ".configlauncher/status.json")
        val m = statusFile.lastModified()
        repeat(6) { assertFalse(e.poll(force = it % 2 == 0)) }
        assertEquals(stamp, src.stamp())
        assertEquals("unchanged result is not rewritten", m, statusFile.lastModified())
    }

    @Test fun editWhileBrokenCopiesToFolderAndHistory() = runBlocking {
        val (e, src, lg) = setup()
        e.poll()
        src.file.writeText("{ broken"); src.file.setLastModified(5000)
        e.poll()
        val historyBefore = lg.historyFiles().size
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 1, 1)))
        assertTrue(e.flush())
        val copy = File(src.dir, ".configlauncher/broken/launcher.20260921T141320Z.json")
        assertEquals("{ broken", copy.readText())
        assertTrue(lg.historyFiles().size > historyBefore)
        assertEquals("Your broken launcher.json was saved to .configlauncher/broken/.", e.state.value.notice)
        assertEquals("ok", status(src)["result"]!!.jsonPrimitive.content)
        assertFalse(e.poll(force = true)) // our own write + sidecars: no reload
    }

    @Test fun folderCopyFailureFallsBackToHistoryOnly() = runBlocking {
        val (e, src, lg) = setup()
        e.poll()
        src.file.writeText("{ broken"); src.file.setLastModified(5000)
        e.poll()
        File(src.dir, ".configlauncher/broken").writeText("blocker") // a file where the dir should be
        e.edit(Edit.MoveToCell("a", "home", 1, 1))
        assertTrue(e.flush())
        assertEquals("Your broken launcher.json was saved to history.", e.state.value.notice)
    }
}
