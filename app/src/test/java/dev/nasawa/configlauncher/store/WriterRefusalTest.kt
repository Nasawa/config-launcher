package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.Edit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §17 Q16 writer refusal (test plan WB-REFUSE): valid/writer-overflow-seed.json is compact and
 * under 2 MiB, so it loads, but its canonical form is over 2 MiB. Any edit must be refused with
 * B5, the file left untouched and the edit kept in memory.
 */
class WriterRefusalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun editOnOverflowSeedIsRefusedAndFileUntouched() = runBlocking {
        val seed = File(javaClass.classLoader!!.getResource("configs/valid/writer-overflow-seed.json")!!.toURI()).readBytes()
        val src = LocalFileSource(tmp.newFolder("src"))
        src.file.writeBytes(seed); src.file.setLastModified(1000)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder("lg")), stabilityMs = 0)
        assertTrue("seed must load", e.poll())
        val first = e.state.value.config.pages[0].items[0]
        assertTrue(e.edit(Edit.MoveToCell(first.id, e.state.value.config.pages[13].id, 11, 11)))
        assertFalse("writer must refuse output over 2 MiB", e.flush())
        assertArrayEquals("file must be untouched", seed, src.file.readBytes())
        assertNotNull("B5 expected", e.state.value.issue)
        assertTrue(e.state.value.issue.toString().contains("2 MB"))
        assertEquals("edit kept in memory", 1, e.state.value.pendingEdits)
    }
}
