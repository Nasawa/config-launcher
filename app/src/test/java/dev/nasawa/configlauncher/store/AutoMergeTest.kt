package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** §18.2: the file wins except items targeted by pending ops; Undo; `Ask before merging`. */
class AutoMergeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file1 = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
        {"id":"a","type":"app","package":"p.a","x":0,"y":0},{"id":"b","type":"app","package":"p.b","x":1,"y":0}]}],"dock":[]}"""
    // Agent: renamed b, moved a (which the user is also moving), added c to the dock.
    private val agent = file1.replace("\"revision\":1", "\"revision\":9")
        .replace("\"package\":\"p.b\"", "\"package\":\"p.b\",\"label\":\"Bee\"")
        .replace("\"package\":\"p.a\",\"x\":0", "\"package\":\"p.a\",\"x\":3")
        .replace("\"dock\":[]", "\"dock\":[{\"id\":\"c\",\"type\":\"app\",\"package\":\"p.c\"}]")

    private fun setup(): Triple<ConfigEngine, LocalFileSource, LastGoodStore> = runBlocking {
        val src = LocalFileSource(tmp.newFolder("src")); src.file.writeText(file1); src.file.setLastModified(1000)
        val lg = LastGoodStore(tmp.newFolder("lg"))
        val e = ConfigEngine(src, lg, stabilityMs = 0); e.poll(); Triple(e, src, lg)
    }
    private fun onDisk(src: LocalFileSource) = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config

    @Test fun fileWinsExceptTouchedItems() = runBlocking {
        val (e, src, lg) = setup()
        val before = lg.historyFiles().size
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText(agent)
        assertTrue(e.flush())
        assertFalse(e.state.value.conflict)
        val c = onDisk(src)
        assertEquals("user's touched item wins", 2 to 2, c.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
        assertEquals("Bee", c.pages[0].items.first { it.id == "b" }.label)
        assertEquals(listOf("c"), c.dock!!.map { it.id })
        assertEquals(10L, c.revision)
        assertTrue(e.state.value.mergeUndo)
        assertEquals(MergeNotice(kept = 1, skipped = 0, folder = null, undo = true), e.state.value.merge)
        assertTrue("both pre-merge versions in history", lg.historyFiles().size >= before + 2 && lg.historyFiles().any { it.readText() == agent })
        assertFalse(e.poll(force = true)) // our merged write is an echo
    }

    private fun merged(): Triple<ConfigEngine, LocalFileSource, LastGoodStore> {
        val (e, src, lg) = setup()
        runBlocking { e.edit(Edit.MoveToCell("a", "home", 2, 2)); src.file.writeText(agent); src.file.setLastModified(2000); assertTrue(e.flush()) }
        return Triple(e, src, lg)
    }
    private fun pos(c: dev.nasawa.configlauncher.model.LauncherConfig, id: String) = c.pages[0].items.first { it.id == id }.let { it.x to it.y }

    @Test fun undoUnchangedFileRevertsThroughWritePath() = runBlocking {
        val (e, src, lg) = merged()
        val before = lg.historyFiles().size
        assertTrue(e.undoMerge())
        val c = onDisk(src)
        assertEquals(3 to 0, pos(c, "a"))           // agent's position back
        assertEquals("Bee", c.pages[0].items.first { it.id == "b" }.label)
        assertFalse(e.state.value.mergeUndo)
        assertTrue("history snapshots before the Undo write", lg.historyFiles().size >= before + 2)
        assertFalse(e.poll(force = true))           // self-write guard
        assertFalse(e.undoMerge())                  // one-shot
    }

    @Test fun undoAfterGitPullRevertsOnlyMergedItems() = runBlocking {
        val (e, src, lg) = merged()
        // A git pull lands during the snackbar: renames b and adds d; a stays where the merge put it.
        val pulled = src.file.readText().replace("\"Bee\"", "\"Bumble\"")
            .replace("\"dock\": [", "\"dock\": [{\"id\":\"d\",\"type\":\"app\",\"package\":\"p.d\"},")
        src.file.writeText(pulled); src.file.setLastModified(9000)
        assertTrue(e.poll(force = true))
        assertTrue("external reload keeps Undo", e.state.value.mergeUndo)
        val before = lg.historyFiles().size
        assertTrue(e.undoMerge())
        val c = onDisk(src)
        assertEquals(3 to 0, pos(c, "a"))           // only the merged item reverted
        assertEquals("Bumble", c.pages[0].items.first { it.id == "b" }.label) // pull kept
        assertEquals(listOf("d", "c"), c.dock!!.map { it.id })
        assertTrue(lg.historyFiles().size >= before + 3)
        assertTrue(lg.historyFiles().any { it.readText() == pulled })
    }

    @Test fun undoCancelledWhenPullTouchedTheSameItem() = runBlocking {
        val (e, src, lg) = merged()
        val pulled = src.file.readText().replace("\"x\": 2", "\"x\": 1") // pull moves a again
        assertNotEquals(src.file.readText(), pulled)
        src.file.writeText(pulled); src.file.setLastModified(9000)
        val before = lg.historyFiles().size
        assertFalse(e.undoMerge())
        assertEquals(pulled, src.file.readText())   // never overwrites the pull
        assertEquals("File changed again, Undo cancelled.", e.state.value.notice)
        assertFalse(e.state.value.mergeUndo)
        assertEquals("no write, no new snapshots", before, lg.historyFiles().size)
    }

    @Test fun writtenLocalEditExpiresUndo() = runBlocking {
        val (e, _) = merged()
        e.edit(Edit.MoveToCell("b", "home", 0, 4)); assertTrue(e.flush())
        assertFalse(e.undoMerge())
    }

    @Test fun askBeforeMergingRestoresPrompt() = runBlocking {
        val (e, src) = setup()
        e.askBeforeMerge = true
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText(agent)
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
        assertEquals(agent, src.file.readText())
    }

    @Test fun invalidIncomingFileFallsBackToPrompt() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText("{ nope")
        assertFalse(e.flush())
        assertTrue(e.state.value.conflict)
    }

    @Test fun allSkippedHasNoUndo() = runBlocking {   // §9.1
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("b", "home", 2, 2))
        src.file.writeText(agent.replace(",{\"id\":\"b\",\"type\":\"app\",\"package\":\"p.b\",\"label\":\"Bee\",\"x\":1,\"y\":0}", ""))
        assertFalse(src.file.readText().contains("p.b"))
        assertTrue(e.flush())
        assertFalse(e.state.value.mergeUndo)
        assertEquals(MergeNotice(kept = 0, skipped = 1, folder = null, undo = false), e.state.value.merge)
        assertFalse("no Undo to run", e.undoMerge())
    }

    @Test fun mixedKeepsUndo() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2)); e.edit(Edit.MoveToCell("b", "home", 3, 3))
        src.file.writeText(agent.replace(",{\"id\":\"b\",\"type\":\"app\",\"package\":\"p.b\",\"label\":\"Bee\",\"x\":1,\"y\":0}", ""))
        assertTrue(e.flush())
        assertTrue(e.state.value.mergeUndo)
        assertEquals(MergeNotice(kept = 1, skipped = 1, folder = null, undo = true), e.state.value.merge)
    }
}
