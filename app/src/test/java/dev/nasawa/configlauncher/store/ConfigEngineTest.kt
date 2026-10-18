package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cfg(rev: Int, pkg: String = "a.b") =
        """{"schemaVersion":1,"revision":$rev,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"$pkg","x":0,"y":0}]}]}"""

    private fun setup(): Triple<ConfigEngine, LocalFileSource, LastGoodStore> {
        val src = LocalFileSource(tmp.newFolder("src"))
        val lg = LastGoodStore(tmp.newFolder("private"))
        return Triple(ConfigEngine(src, lg, stabilityMs = 0), src, lg)
    }

    private fun File.put(s: String, mtime: Long) { writeText(s); setLastModified(mtime) }

    @Test fun missingFileIsCreatedFromDefault() = runBlocking {
        val (e, src, _) = setup()
        assertEquals(Origin.DEFAULT, e.state.value.origin)
        e.poll()
        assertTrue(src.file.isFile)
        assertEquals(DefaultLayout.config, (ConfigCodec.decode(src.file.readBytes()) as dev.nasawa.configlauncher.config.ParseResult.Ok).config)
        // Our own write is not re-applied as an external edit.
        assertFalse(e.poll(force = true))
    }

    @Test fun loadsValidFileAndSavesLastGood() = runBlocking {
        val (e, src, lg) = setup()
        src.file.put(cfg(1), 1000)
        assertTrue(e.poll())
        assertEquals(Origin.FILE, e.state.value.origin)
        assertEquals(1L, e.state.value.config.revision)
        assertArrayEquals(src.file.readBytes(), lg.load())
    }

    @Test fun invalidFileKeepsLastGoodModelAndReportsErrors() = runBlocking {
        val (e, src, _) = setup()
        src.file.put(cfg(1), 1000); e.poll()
        src.file.put("""{"schemaVersion":1,"pages":[]}""", 2000)
        assertFalse(e.poll())
        assertEquals(1L, e.state.value.config.revision)
        assertTrue(e.state.value.errors.single().contains("pages"))
        src.file.put(cfg(3), 3000)
        assertTrue(e.poll())
        assertEquals(emptyList<String>(), e.state.value.errors)
    }

    @Test fun unchangedStampSkipsRead() = runBlocking {
        val (e, src, _) = setup()
        src.file.put(cfg(1), 1000); e.poll()
        assertFalse(e.poll())
    }

    @Test fun coldStartUsesLastGood() = runBlocking {
        val (e, src, lg) = setup()
        src.file.put(cfg(7), 1000); e.poll()
        val again = ConfigEngine(LocalFileSource(src.dir), lg, 0)
        assertEquals(Origin.LAST_GOOD, again.state.value.origin)
        assertEquals(7L, again.state.value.config.revision)
    }

    @Test fun lastGoodHistoryKeepsTen() {
        var t = 1_000_000_000_000L
        val lg = LastGoodStore(tmp.newFolder("lg"), keep = 10) { t++ }
        repeat(15) { lg.save("v$it".toByteArray()) }
        assertEquals(10, lg.historyFiles().size)
        assertEquals("v13", lg.historyFiles().first().readText())
        assertEquals("v14", String(lg.load()!!))
    }

    @Test fun localWriteIsAtomicReplace() {
        val src = LocalFileSource(tmp.newFolder("w"))
        src.write("one".toByteArray()); src.write("two".toByteArray())
        assertEquals("two", src.file.readText())
        assertFalse(File(src.dir, ".configlauncher/launcher.json.tmp").exists())
    }
}
