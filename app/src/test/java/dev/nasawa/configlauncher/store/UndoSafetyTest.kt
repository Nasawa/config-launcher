package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.LauncherConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * QA (test plan §6 CM-19..CM-24): the §18.2 Undo safety rule. Undo must never overwrite an external
 * change; the pre-merge file is always in history, whichever path Undo takes.
 */
class UndoSafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file1 = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[{"id":"home","items":[
        {"id":"a","type":"app","package":"p.a","x":0,"y":0},{"id":"b","type":"app","package":"p.b","x":1,"y":0}]}],"dock":[]}"""
    private val agent = file1.replace("\"revision\":1", "\"revision\":9")
        .replace("\"package\":\"p.b\"", "\"package\":\"p.b\",\"label\":\"Bee\"")
        .replace("\"package\":\"p.a\",\"x\":0", "\"package\":\"p.a\",\"x\":3")
        .replace("\"dock\":[]", "\"dock\":[{\"id\":\"c\",\"type\":\"app\",\"package\":\"p.c\"}]")
    private val cancelled = Msg(dev.nasawa.configlauncher.R.string.undo_cancelled)

    private class Rig(val e: ConfigEngine, val src: LocalFileSource, val lg: LastGoodStore)

    /** Load file1, user moves a to (2,2), agent writes [agent], flush auto-merges. Snackbar is now up. */
    private fun merged(): Rig = runBlocking {
        val src = LocalFileSource(tmp.newFolder("src")); src.file.writeText(file1); src.file.setLastModified(1000)
        val lg = LastGoodStore(tmp.newFolder("lg"))
        val e = ConfigEngine(src, lg, stabilityMs = 0); e.poll()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        src.file.writeText(agent); src.file.setLastModified(2000)
        assertTrue(e.flush()); assertTrue(e.state.value.mergeUndo)
        Rig(e, src, lg)
    }
    private fun cfg(s: String) = (ConfigCodec.decode(s.toByteArray()) as ParseResult.Ok).config
    private fun onDisk(r: Rig) = cfg(r.src.file.readText())
    private fun pos(c: LauncherConfig, id: String) = c.pages[0].items.first { it.id == id }.let { it.x to it.y }
    private fun preMergeInHistory(r: Rig) = r.lg.historyFiles().any { it.readText() == agent }
    /** Simulates `git pull` replacing the file (new inode, new mtime) without the engine polling first. */
    private fun externalRewrite(r: Rig, text: String) {
        val tmpF = java.io.File(r.src.file.parentFile, ".launcher.json.pull"); tmpF.writeText(text)
        assertTrue(tmpF.renameTo(r.src.file)); r.src.file.setLastModified(9000)
    }

    @Test fun unchangedFile_restoresPreMergeContent_andHistoryHasIt() = runBlocking {
        val r = merged()
        assertTrue(r.e.undoMerge())
        val c = onDisk(r); val a = cfg(agent)
        // Item order inside a page isn't semantic; compare by ID.
        assertEquals(a.pages[0].items.sortedBy { it.id }, c.pages[0].items.sortedBy { it.id }); assertEquals(a.dock, c.dock)
        assertTrue(preMergeInHistory(r))
        assertFalse(r.e.state.value.mergeUndo)
    }

    @Test fun changedFile_unrelatedEdit_revertsOnlyMergedItems_withoutPolling() = runBlocking {
        val r = merged()
        // git pull lands inside the 10 s window; engine has NOT polled it yet.
        val pulled = r.src.file.readText().replace("\"Bee\"", "\"Bumble\"")
        externalRewrite(r, pulled)
        assertTrue(r.e.undoMerge())
        val c = onDisk(r)
        assertEquals("merged item reverted", 3 to 0, pos(c, "a"))
        assertEquals("external rename kept", "Bumble", c.pages[0].items.first { it.id == "b" }.label)
        assertTrue(preMergeInHistory(r))
        assertTrue("the pulled file is in history", r.lg.historyFiles().any { it.readText() == pulled })
    }

    @Test fun changedFile_sameItemMoved_cancels_exactMessage_noWrite() = runBlocking {
        val r = merged()
        val pulled = r.src.file.readText().replace("\"x\": 2", "\"x\": 1")
        assertNotEquals(r.src.file.readText(), pulled)
        externalRewrite(r, pulled)
        assertFalse(r.e.undoMerge())
        assertEquals(pulled, r.src.file.readText())
        assertEquals(cancelled, r.e.state.value.notice)
        assertFalse(r.e.state.value.mergeUndo)
        assertTrue("pre-merge file still in history on cancel", preMergeInHistory(r))
    }

    @Test fun changedFile_targetCellNowOccupied_cancels() = runBlocking {
        val r = merged()
        // Pull puts a new item on (3,0), where Undo would put a back.
        val pulled = r.src.file.readText().replaceFirst("\"items\": [", "\"items\": [{\"id\":\"z\",\"type\":\"app\",\"package\":\"p.z\",\"x\":3,\"y\":0},")
        assertNotEquals(r.src.file.readText(), pulled)
        externalRewrite(r, pulled)
        assertFalse(r.e.undoMerge())
        assertEquals(pulled, r.src.file.readText())
        assertEquals(cancelled, r.e.state.value.notice)
    }

    @Test fun changedFile_nowInvalid_cancels_bytesUntouched() = runBlocking {
        val r = merged()
        externalRewrite(r, "{ half-written")
        assertFalse(r.e.undoMerge())
        assertEquals("{ half-written", r.src.file.readText())
        assertEquals(cancelled, r.e.state.value.notice)
        assertTrue(preMergeInHistory(r))
    }

    @Test fun fileDeleted_cancels_andDoesNotRecreate() = runBlocking {
        val r = merged()
        assertTrue(r.src.file.delete())
        assertFalse(r.e.undoMerge())
        assertFalse(r.src.file.exists())
        assertEquals(cancelled, r.e.state.value.notice)
    }

    @Test fun whitespaceOnlyReformat_takesChangedPath_andKeepsFormattingIndependentContent() = runBlocking {
        val r = merged()
        val reformatted = r.src.file.readText().replace("\n", "\n  ")
        externalRewrite(r, reformatted)
        assertTrue(r.e.undoMerge())
        assertEquals(3 to 0, pos(onDisk(r), "a"))
        assertTrue(r.lg.historyFiles().any { it.readText() == reformatted })
    }

    @Test fun gitPullDuringSnackbar_polled_thenUndo_neverLosesPull() = runBlocking {
        val r = merged()
        val pulled = r.src.file.readText().replace("\"dock\": [", "\"dock\": [{\"id\":\"d\",\"type\":\"app\",\"package\":\"p.d\"},")
        assertNotEquals(r.src.file.readText(), pulled)
        externalRewrite(r, pulled)
        assertTrue(r.e.poll(force = true))
        assertTrue("Undo survives an external reload", r.e.state.value.mergeUndo)
        assertTrue(r.e.undoMerge())
        val c = onDisk(r)
        assertTrue("pulled dock item kept", c.dock!!.any { it.id == "d" })
        assertEquals(3 to 0, pos(c, "a"))
        assertTrue(preMergeInHistory(r))
    }

    @Test fun undoIsOneShot_andDismissExpiresIt() = runBlocking {
        val r = merged()
        r.e.dismissMergeUndo()
        val before = r.src.file.readText()
        assertFalse(r.e.undoMerge())
        assertEquals(before, r.src.file.readText())
    }
}
