package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WriteBackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file1 = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
        {"id":"a","type":"app","package":"p.a","x":0,"y":0},{"id":"b","type":"app","package":"p.b","x":1,"y":0}]}],"dock":[]}"""

    private fun setup(): Pair<ConfigEngine, LocalFileSource> = runBlocking {
        val src = LocalFileSource(tmp.newFolder("src"))
        src.file.writeText(file1); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        e.askBeforeMerge = true // these tests cover the B6 prompt path; AutoMergeTest covers the default
        e.poll(); e to src
    }

    private fun onDisk(src: LocalFileSource) = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config

    @Test fun editIsOptimisticThenWrittenCanonically() = runBlocking {
        val (e, src) = setup()
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 3, 4)))
        assertEquals(1, e.state.value.pendingEdits)
        assertEquals(file1, src.file.readText()) // nothing written before flush
        assertTrue(e.flush())
        val c = onDisk(src)
        assertEquals(3 to 4, c.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
        assertEquals(2L, c.revision)
        assertEquals(ConfigCodec.encode(c), src.file.readText())
        assertEquals(ConfigCodec.sha256(src.file.readBytes()), e.state.value.baseHash)
    }

    @Test fun selfWriteIsNotReloaded() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToDock("b", 0)); e.flush()
        src.file.setLastModified(5000) // poller sees a new stamp
        assertFalse(e.poll())
    }

    @Test fun externalEditUnderPendingEditsIsAConflict() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        val external = file1.replace("\"revision\":1", "\"revision\":9")
        src.file.writeText(external)
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
        assertEquals(external, src.file.readText()) // nothing clobbered
        assertFalse(e.poll(force = true))            // reloads are held during the conflict
    }

    @Test fun keepMineReplaysOntoFileVersion() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        // Agent added c and renamed b meanwhile.
        src.file.writeText(file1.replace("\"revision\":1", "\"revision\":9")
            .replace("{\"id\":\"b\",\"type\":\"app\",\"package\":\"p.b\"", "{\"id\":\"b\",\"type\":\"app\",\"package\":\"p.b\",\"label\":\"Bee\"")
            .replace("\"dock\":[]", "\"dock\":[{\"id\":\"c\",\"type\":\"app\",\"package\":\"p.c\"}]"))
        e.flush()
        assertTrue(e.resolveKeepMine())
        val c = onDisk(src)
        assertEquals(2 to 2, c.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
        assertEquals("Bee", c.pages[0].items.first { it.id == "b" }.label)
        assertEquals(listOf("c"), c.dock!!.map { it.id })
        assertEquals(10L, c.revision)
        assertFalse(e.state.value.conflict)
        assertNull("§9.1: Keep mine with everything kept shows nothing extra", e.state.value.merge)
        assertFalse(e.state.value.mergeUndo)
    }

    @Test fun keepMineSkippedUsesSameCopyWithoutUndo() = runBlocking {   // §9.1, CM-8
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2)); e.edit(Edit.MoveToCell("b", "home", 3, 3))
        src.file.writeText(file1.replace("\"revision\":1", "\"revision\":9").replace(Regex(",\\{\"id\":\"b\"[^}]*\\}"), ""))
        assertFalse(src.file.readText().contains("p.b"))
        e.askBeforeMerge = true
        e.flush()
        assertTrue(e.state.value.conflict)
        assertTrue(e.resolveKeepMine())
        assertEquals(MergeNotice(kept = 1, skipped = 1, folder = null, undo = false), e.state.value.merge)
        assertFalse(e.state.value.mergeUndo)
    }

    @Test fun useFileDiscardsMine() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        val external = file1.replace("\"revision\":1", "\"revision\":9")
        src.file.writeText(external); src.file.setLastModified(9000)
        e.flush()
        e.resolveUseFile()
        assertEquals(9L, e.state.value.config.revision)
        assertEquals(0 to 0, e.state.value.config.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
        assertEquals(external, src.file.readText())
    }

    @Test fun dragHoldsReloads() = runBlocking {
        val (e, src) = setup()
        e.dragging = true
        src.file.writeText(file1.replace("\"revision\":1", "\"revision\":5")); src.file.setLastModified(7000)
        assertFalse(e.poll())
        e.dragging = false
        assertTrue(e.poll())
    }

    @Test fun editsDuringConflictJoinQueueAndKeepMineReplaysAll() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText(file1.replace("\"revision\":1", "\"revision\":9"))
        e.flush()
        assertTrue(e.state.value.conflict)
        assertTrue(e.edit(Edit.MoveToDock("b", 0)))
        assertEquals(2, e.state.value.pendingEdits)
        assertFalse(e.flush())
        assertTrue(e.resolveKeepMine())
        val c = onDisk(src)
        assertEquals(listOf("b"), c.dock!!.map { it.id })
        assertEquals(2 to 2, c.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
    }

    @Test fun editWhileFileStillBrokenOverwritesAfterSnapshot() = runBlocking {
        val (e, src) = setup()
        src.file.writeText("{ broken"); src.file.setLastModified(5000)
        e.poll()
        assertNotNull(e.state.value.issue)
        assertTrue(e.edit(Edit.MoveToCell("a", "home", 1, 1)))
        assertTrue(e.flush())
        assertFalse(e.state.value.conflict)
        assertEquals(1 to 1, onDisk(src).pages[0].items.first { it.id == "a" }.let { it.x to it.y })
    }

    @Test fun brokenFileChangedAgainBeforeWriteIsAConflict() = runBlocking {
        val (e, src) = setup()
        src.file.writeText("{ broken"); src.file.setLastModified(5000)
        e.poll()
        e.edit(Edit.MoveToCell("a", "home", 1, 1))
        src.file.writeText(file1.replace("\"revision\":1", "\"revision\":7"))
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
    }
}
