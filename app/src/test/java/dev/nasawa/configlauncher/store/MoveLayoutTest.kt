package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.store.ConfigEngine.Inspection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §18.2: Skip for now (app storage) and moving the layout to/from a folder. */
class MoveLayoutTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mine = """{"schemaVersion":1,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]}]}"""
    private val theirs = """{"schemaVersion":1,"pages":[{"id":"p1","items":[]},{"id":"p2","items":[]}]}"""

    private fun phone(): Pair<ConfigEngine, LocalFileSource> = runBlocking {
        val src = LocalFileSource(tmp.newFolder("phone")); src.file.writeText(mine)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder("lg")), 0); e.poll(force = true); e to src
    }
    private fun ids(f: File) = (ConfigCodec.decode(f.readBytes()) as ParseResult.Ok).config.pages.map { it.id }

    @Test fun appStorageNeverOffersReconnectAndHasNoFolderBanners() = runBlocking {
        val (e, _) = phone()
        assertFalse(e.state.value.reconnectable)
        assertNull(e.state.value.issue)
    }

    @Test fun inspectReportsFolderContents() {
        val (e, _) = phone()
        val d = LocalFileSource(tmp.newFolder("f"))
        assertEquals(Inspection.Empty, e.inspect(d))
        d.file.writeText(theirs); assertEquals(Inspection.Valid(2, 0), e.inspect(d))
        d.file.writeText("{"); assertTrue(e.inspect(d) is Inspection.Invalid)
    }

    @Test fun moveToEmptyFolderWritesCurrentLayout() = runBlocking {
        val (e, _) = phone()
        val d = LocalFileSource(tmp.newFolder("f"))
        assertTrue(e.moveTo(d, useMine = false)) // empty: always ours
        assertEquals(listOf("home"), ids(d.file))
        assertSame(d, e.source)
    }

    @Test fun replaceWithMineBacksUpFolderFile() = runBlocking {
        val (e, phone) = phone()
        val d = LocalFileSource(tmp.newFolder("f")); d.file.writeText(theirs)
        assertTrue(e.moveTo(d, useMine = true))
        assertEquals(listOf("home"), ids(d.file))
        val backups = File(d.dir, ".configlauncher/backup").listFiles()!!
        assertEquals(theirs, backups.single().readText())
        assertEquals(mine, phone.file.readText()) // phone file is kept
    }

    @Test fun useFolderLayoutKeepsMineInHistory() = runBlocking {
        val lg = tmp.newFolder("lg2")
        val src = LocalFileSource(tmp.newFolder("phone2")); src.file.writeText(mine)
        val e = ConfigEngine(src, LastGoodStore(lg), 0); e.poll(force = true)
        val d = LocalFileSource(tmp.newFolder("f")); d.file.writeText(theirs)
        assertTrue(e.moveTo(d, useMine = false))
        assertEquals(listOf("p1", "p2"), e.state.value.config.pages.map { it.id })
        assertEquals(theirs, d.file.readText())
        assertTrue(LastGoodStore(lg).historyFiles().any { it.readText().contains("\"home\"") })
    }

    @Test fun moveBackToPhoneCopiesCurrentLayout() = runBlocking {
        val (e, phone) = phone()
        val d = LocalFileSource(tmp.newFolder("f")); d.file.writeText(theirs)
        e.moveTo(d, useMine = false)
        assertTrue(e.moveTo(phone, useMine = true))
        assertEquals(listOf("p1", "p2"), ids(phone.file))
    }

    @Test fun skipOnFreshInstallWritesStarterSoNoB4() = runBlocking {
        val phone = LocalFileSource(tmp.newFolder("fresh"))
        val e = ConfigEngine(phone, LastGoodStore(tmp.newFolder("lg3")), 0)
        assertTrue(e.moveTo(phone, useMine = false))
        assertTrue(phone.file.isFile)
        assertNull(e.state.value.issue)
        assertFalse(e.poll(force = true))
    }
}
