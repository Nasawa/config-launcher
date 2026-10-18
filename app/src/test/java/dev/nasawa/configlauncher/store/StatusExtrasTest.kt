package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.config.ErrorLocator
import dev.nasawa.configlauncher.config.ParseResult
import dev.nasawa.configlauncher.config.TextPos
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** §17 Q4 backupError and OQ-21 conflictFiles in status.json; git conflict markers. */
class StatusExtrasTest {
    @get:Rule val tmp = TemporaryFolder()
    private val good = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}"""

    private fun status(dir: File) = Json.parseToJsonElement(File(dir, ".configlauncher/status.json").readText()).jsonObject

    @Test fun conflictNamesCloudAndGit() {
        val yes = listOf(
            "launcher.sync-conflict-20261018-101500-ABCDEFG.json",
            "launcher (Chris's conflicted copy 2026-10-18).json",
            "launcher (1).json", "launcher 2.json", "Launcher (conflict 2026-10-18 10-15-00).json",
            "launcher-DESKTOP-AB12CD.json", "launcher_conflict-20261018.json",
            "launcher_BACKUP_1234.json", "launcher_BASE_1234.json", "launcher_LOCAL_1234.json", "launcher_REMOTE_1234.json",
            "launcher.json.orig", "launcher.json.rej",
        )
        val no = listOf("launcher.json", ".configlauncher", "launcher-old.json", "notes.txt", ".git", "launcher.json.swp")
        yes.forEach { assertTrue(it, SyncConflicts.isConflict(it)) }
        no.forEach { assertFalse(it, SyncConflicts.isConflict(it)) }
    }

    @Test fun gitMarkersGetAClearError() {
        val t = "{\n  \"schemaVersion\": 1,\n<<<<<<< HEAD\n  \"pages\": []\n=======\n  \"pages\": [{}]\n>>>>>>> origin/main\n}\n"
        val e = (ConfigCodec.decode(t) as ParseResult.Invalid).errors
        assertTrue(e[0], e[0].contains("git merge conflict markers (line 3)"))
        assertEquals(TextPos(3, 1), ErrorLocator.locate(t, e)[0])
    }

    @Test fun statusListsConflictsAndClearsThem() = runBlocking {
        val dir = tmp.newFolder("src")
        val src = LocalFileSource(dir); src.file.writeText(good)
        val e = ConfigEngine(src, LastGoodStore(tmp.newFolder()), 0)
        e.poll(force = true)
        assertEquals(0, status(dir)["conflictFiles"]!!.jsonArray.size)
        File(dir, "launcher.sync-conflict-20261018-101500-ABCDEFG.json").writeText(good)
        File(dir, "launcher.json.orig").writeText(good)
        assertFalse(e.poll(force = true))
        assertEquals(listOf("launcher.json.orig", "launcher.sync-conflict-20261018-101500-ABCDEFG.json"),
            status(dir)["conflictFiles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("ok", status(dir)["result"]!!.jsonPrimitive.content)
        File(dir, "launcher.json.orig").delete(); File(dir, "launcher.sync-conflict-20261018-101500-ABCDEFG.json").delete()
        e.poll(force = true)
        assertEquals(0, status(dir)["conflictFiles"]!!.jsonArray.size)
    }

    @Test fun backupFailureIsReportedThenCleared() = runBlocking {
        val dir = tmp.newFolder("src")
        val src = LocalFileSource(dir); src.file.writeText(good)
        val lgDir = tmp.newFolder("lg")
        var fail = true
        val lg = object : LastGoodStore(lgDir) {
            override fun save(bytes: ByteArray) { if (fail) throw java.io.IOException("No space left on device") else super.save(bytes) }
        }
        val e = ConfigEngine(src, lg, 0)
        e.poll(force = true)
        assertTrue(status(dir)["backupError"]!!.jsonPrimitive.content.contains("No space left"))
        assertEquals("ok", status(dir)["result"]!!.jsonPrimitive.content)
        fail = false
        src.file.writeText(good.replace("[]", """[{"id":"a","type":"app","package":"p.a","x":0,"y":0}]""")); src.file.setLastModified(9000)
        e.poll(force = true)
        assertEquals(JsonNull, status(dir)["backupError"])
    }
}
