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
        assertEquals("Layout updated from launcher.json. Kept your 1 change.", e.state.value.notice)
        assertTrue("both pre-merge versions in history", lg.historyFiles().size >= before + 2 && lg.historyFiles().any { it.readText() == agent })
        assertFalse(e.poll(force = true)) // our merged write is an echo
    }

    @Test fun undoWritesTheFileVersionBack() = runBlocking {
        val (e, src) = setup()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText(agent)
        e.flush()
        assertTrue(e.undoMerge())
        assertEquals(agent, src.file.readText())
        assertEquals(3 to 0, e.state.value.config.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
        assertFalse(e.state.value.mergeUndo)
        assertFalse(e.poll(force = true))
        assertFalse(e.undoMerge()) // one-shot
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

    @Test fun copy() {
        assertEquals("Layout updated from launcher.json. Kept your 2 changes.", MergeCopy.notice(2, 0))
        assertEquals("Layout updated from launcher.json. 1 change(s) skipped because the item was removed or moved in the file", MergeCopy.notice(0, 1))
    }
}
