package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Edits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Grace's #70 blocker, end to end on the real engine: the empty-page cleanup at edit-mode exit is replayed
 * onto an agent's file without deleting an empty page the agent added meanwhile.
 */
class DropPagesMergeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file1 = """{"schemaVersion":1,"revision":1,"grid":{"columns":4,"rows":5},"pages":[
        {"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]},{"id":"two","items":[]}]}"""
    private val agent = file1.replace("\"revision\":1", "\"revision\":5")
        .replace("{\"id\":\"two\",\"items\":[]}", "{\"id\":\"two\",\"items\":[]},{\"id\":\"agent-empty\",\"title\":\"Agent\",\"items\":[]}")

    @Test fun agentsNewEmptyPageSurvivesTheMerge() = runBlocking {
        val src = LocalFileSource(tmp.newFolder("src")); src.file.writeText(file1); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0); e.poll()
        e.edit(Edit.MoveToCell("a", "home", 2, 2))
        // Edit mode ends: only the pages empty right now are recorded.
        val ids = Edits.emptyPageIds(e.state.value.config)
        assertEquals(listOf("two"), ids)
        e.edit(Edit.DropPages(ids))
        src.file.writeText(agent); src.file.setLastModified(2000)   // agent's file lands before our write
        assertTrue(e.flush())
        val c = (ConfigCodec.decode(src.file.readBytes()) as ParseResult.Ok).config
        assertEquals(listOf("home", "agent-empty"), c.pages.map { it.id })
        assertEquals(2 to 2, c.pages[0].items.first { it.id == "a" }.let { it.x to it.y })
    }
}
