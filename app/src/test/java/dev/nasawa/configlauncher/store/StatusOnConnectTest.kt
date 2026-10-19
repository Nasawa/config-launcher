package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #103: connecting a folder refreshes status.json instead of leaving one from before a reinstall. */
class StatusOnConnectTest {
    @get:Rule val tmp = TemporaryFolder()
    private val mine = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}"""
    private val theirs = """{"schemaVersion":1,"revision":4,"pages":[{"id":"p1","items":[]}]}"""
    private val now = 1_792_000_000_000L

    private fun phone() = runBlocking {
        val src = LocalFileSource(tmp.newFolder(), sidecars = false); src.file.writeText(mine)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0, clock = { now }); e.poll(force = true); e to src
    }
    private fun folder(content: String) = LocalFileSource(tmp.newFolder()).also {
        it.file.writeText(content)
        File(it.dir, ".configlauncher").mkdirs()
        File(it.dir, ".configlauncher/status.json").writeText("""{"result":"stale","hash":"old","time":"2000-01-01T00:00:00Z"}""")
    }
    private fun status(d: LocalFileSource) = Json.parseToJsonElement(File(d.dir, ".configlauncher/status.json").readText()).jsonObject

    @Test fun useFolderLayoutWritesFreshStatus() = runBlocking {
        val (e, _) = phone()
        val d = folder(theirs)
        assertTrue(e.moveTo(d, useMine = false))
        val s = status(d)
        assertEquals("ok", s["result"]!!.jsonPrimitive.content)
        assertEquals(ConfigCodec.sha256(d.file.readBytes()), s["hash"]!!.jsonPrimitive.content)
        assertEquals(4L, s["revision"]!!.jsonPrimitive.long)
        assertNotEquals("2000-01-01T00:00:00Z", s["time"]!!.jsonPrimitive.content)
    }

    @Test fun switchingToAFolderWithTheSameFileWritesFreshStatus() = runBlocking {
        val (e, _) = phone()
        val d = folder(mine)
        e.switchSource(d)
        assertEquals("ok", status(d)["result"]!!.jsonPrimitive.content)
        assertEquals(ConfigCodec.sha256(d.file.readBytes()), status(d)["hash"]!!.jsonPrimitive.content)
    }
}
