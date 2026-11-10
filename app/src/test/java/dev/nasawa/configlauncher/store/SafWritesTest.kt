package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** #251 (Grace): where [SafWrites] stages its temp, restore and keep on failure, and the root-temp cleanup. */
class SafWritesTest {
    private val dir = Document.MIME_TYPE_DIR
    private val bin = "application/octet-stream"
    private val json = "application/json"
    private var keyN = 0
    private fun key() = "writes-${System.identityHashCode(this)}-${keyN++}"

    private class MemJournal : TempJournal {
        val ids = mutableMapOf<String, MutableList<String>>()
        override fun add(treeKey: String, docId: String) { ids.getOrPut(treeKey) { mutableListOf() } += docId }
        override fun remove(treeKey: String, docId: String) { ids[treeKey]?.remove(docId) }
        override fun list(treeKey: String) = ids[treeKey].orEmpty().toList()
    }

    /** Records every name asked for; can fail writes to a name, and run a hook when a root temp is written. */
    private class Recording(val t: SafDirsTest.FakeTree) : DocTree by t {
        val asked = mutableListOf<String>()
        /** The next write to a document with this name writes "half" and throws (once; the restore then works). */
        val failWrites = mutableSetOf<String>()
        var onRootTempWrite: (() -> Unit)? = null
        override fun create(parentDocId: String, mime: String, name: String) = t.create(parentDocId, mime, name).also { asked += name }
        /** A killed process: nothing it would still do reaches the provider. */
        var dead = false
        override fun delete(docId: String) = if (dead) false else t.delete(docId)
        override fun write(docId: String, bytes: ByteArray) {
            val name = t.docs[docId]?.name
            if (name != null && failWrites.remove(name)) { t.write(docId, "half".toByteArray()); throw IOException("disk full") }
            if (name != null && name.startsWith(".") && name.endsWith(".tmp")) onRootTempWrite?.let { onRootTempWrite = null; it() }
            t.write(docId, bytes)
        }
    }

    private class Setup(val t: SafDirsTest.FakeTree, val r: Recording, val w: SafWrites, val journal: MemJournal, val key: String)
    private fun setup(hidden: Boolean, hideDots: Boolean = false, token: () -> String = counter()): Setup {
        val t = SafDirsTest.FakeTree().apply { hideDotFiles = hideDots }
        if (hidden) { t.add("root", ".configlauncher", dir); t.unlisted += ".configlauncher" }
        val r = Recording(t); val k = key(); val j = MemJournal()
        return Setup(t, r, SafWrites(r, SafDirs(r, k), k, j, token), j, k)
    }
    private fun counter(): () -> String { var n = 0; return { "t${n++}" } }
    private fun SafDirsTest.FakeTree.text(name: String) = docs.values.firstOrNull { it.parent == "root" && it.name == name }?.let { String(read(it.id)!!) }
    private fun SafDirsTest.FakeTree.numbered() = namesIn("root").filter { "(" in it }

    @Test fun normalSaveStagesInsideConfiglauncher() {
        val s = setup(hidden = false)
        s.w.writeVerified("root", "launcher.json", json, "{}".toByteArray(), "layout")
        assertEquals("{}", s.t.text("launcher.json"))
        assertEquals(listOf(".configlauncher", "launcher.json.tmp", "launcher.json"), s.r.asked)
        assertEquals(listOf(".configlauncher", "launcher.json"), s.t.namesIn("root"))
    }

    @Test fun suppressedFolderStagesInTheRootAndDeletesTheTemp() {
        val s = setup(hidden = true)
        repeat(3) { i ->
            s.w.writeVerified("root", "launcher.json", json, "{\"v\":$i}".toByteArray(), "layout")
            s.w.writeVerified("root", "AGENTS.md", bin, "agents $i".toByteArray(), "AGENTS.md")
        }
        assertEquals("{\"v\":2}", s.t.text("launcher.json")); assertEquals("agents 2", s.t.text("AGENTS.md"))
        assertEquals(listOf(".configlauncher", "AGENTS.md", "launcher.json"), s.t.namesIn("root")) // no (N), no temp left
        assertEquals(1, s.r.asked.count { it == ".configlauncher" }) // tried once, then suppressed
        assertTrue(s.r.asked.toString(), ".launcher.json.t0.tmp" in s.r.asked && ".AGENTS.md.t1.tmp" in s.r.asked) // a new name per save
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun hiddenLeftoverFromAKilledSaveNeverBlocksSavesAndIsSweptById() {
        // Grace's repro: the provider hides dot files, and a force-stop left a hidden 1888-byte temp (journaled).
        val s = setup(hidden = true, hideDots = true)
        val stale = s.t.add("root", ".launcher.json.tmp", bin); s.t.write(stale, ByteArray(1888))
        val stale2 = s.t.add("root", ".launcher.json.dead.tmp", bin); s.t.write(stale2, ByteArray(1888)); s.journal.add(s.key, stale2)
        repeat(5) { i -> s.w.writeVerified("root", "launcher.json", json, "$i".toByteArray(), "layout") }
        assertEquals("4", s.t.text("launcher.json"))
        assertEquals(emptyList<String>(), s.t.numbered())
        assertEquals(listOf(".configlauncher", ".launcher.json.dead.tmp", ".launcher.json.tmp", "launcher.json"), s.t.namesIn("root"))
        s.w.sweepRootTemps("root") // next start
        assertFalse("tracked by id: deleted though the listing hides it", s.t.docs.containsKey(stale2))
        assertTrue("untracked and hidden: orphaned, never guessed at by name", s.t.docs.containsKey(stale))
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun renamedRootTempIsUsedAndDeletedById() {
        // Even a colliding name (fixed token, hidden leftover) is accepted as returned and deleted by its id.
        val s = setup(hidden = true, hideDots = true, token = { "x" })
        val stale = s.t.add("root", ".launcher.json.x.tmp", bin); s.t.write(stale, ByteArray(1888))
        repeat(5) { i -> s.w.writeVerified("root", "launcher.json", json, "$i".toByteArray(), "layout") }
        assertEquals("4", s.t.text("launcher.json"))
        assertEquals(5, s.r.asked.count { it == ".launcher.json.x.tmp" })
        assertEquals(emptyList<String>(), s.t.numbered()) // each renamed `(1)` copy was deleted by id
        assertEquals(listOf(".configlauncher", ".launcher.json.x.tmp", "launcher.json"), s.t.namesIn("root"))
    }

    @Test fun failedTargetWriteRestoresTheOriginalBytesAndDeletesTheTemp() {
        val s = setup(hidden = true)
        s.w.writeVerified("root", "launcher.json", json, "old".toByteArray(), "layout")
        s.r.failWrites += "launcher.json"
        val e = assertThrows(IOException::class.java) { s.w.writeVerified("root", "launcher.json", json, "new".toByteArray(), "layout") }
        s.r.failWrites.clear()
        assertEquals("the original bytes, not the temp's new ones", "old", s.t.text("launcher.json"))
        assertFalse(e.message!!, e.message!!.contains("kept in"))
        assertEquals(listOf(".configlauncher", "launcher.json"), s.t.namesIn("root"))
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun normalModeRestoresTheOriginalBytesToo() {
        val s = setup(hidden = false)
        s.w.writeVerified("root", "launcher.json", json, "old".toByteArray(), "layout")
        s.r.failWrites += "launcher.json"
        assertThrows(IOException::class.java) { s.w.writeVerified("root", "launcher.json", json, "new".toByteArray(), "layout") }
        s.r.failWrites.clear()
        assertEquals("old", s.t.text("launcher.json"))
    }

    @Test fun rootTempIsKeptWhenTargetAndRestoreBothFail() {
        val s = setup(hidden = true)
        s.w.writeVerified("root", "launcher.json", json, "old".toByteArray(), "layout")
        // Every write to launcher.json fails, so the restore fails too: the temp is the only durable new copy.
        val failing = object : DocTree by s.r {
            override fun write(docId: String, bytes: ByteArray) {
                if (s.t.docs[docId]?.name == "launcher.json") { s.t.write(docId, "ha".toByteArray()); throw IOException("disk full") }
                s.r.write(docId, bytes)
            }
        }
        val w = SafWrites(failing, SafDirs(failing, s.key), s.key, s.journal, { "keep" })
        val e = assertThrows(IOException::class.java) { w.writeVerified("root", "launcher.json", json, "new".toByteArray(), "layout") }
        assertTrue(e.message!!, e.message!!.contains("kept in .launcher.json.keep.tmp"))
        assertEquals("new", s.t.text(".launcher.json.keep.tmp"))
        assertTrue("out of the journal, so no sweep deletes it", s.journal.list(s.key).isEmpty())
        w.sweepRootTemps("root")
        assertEquals("new", s.t.text(".launcher.json.keep.tmp"))
    }

    @Test fun nextNormalSaveDeletesALegacyRootTemp() {
        val s = setup(hidden = false)
        s.t.write(s.t.add("root", ".launcher.json.tmp", bin), "left by a 7803222 build".toByteArray())
        s.w.writeVerified("root", "launcher.json", json, "{}".toByteArray(), "layout")
        assertEquals(listOf(".configlauncher", "launcher.json"), s.t.namesIn("root"))
    }

    @Test fun startSweepDeletesOnlyOurRootTemps() {
        val s = setup(hidden = false)
        for (n in listOf(".launcher.json.tmp", ".AGENTS.md.tmp", "launcher.json.tmp", ".notes.tmp", "AGENTS.md", "launcher.json"))
            s.t.write(s.t.add("root", n, bin), "x".toByteArray())
        val user = s.t.add("root", "notes.txt", bin); s.journal.add(s.key, user) // a journaled id that isn't a temp
        s.w.sweepRootTemps("root")
        assertEquals(listOf(".notes.tmp", "AGENTS.md", "launcher.json", "launcher.json.tmp", "notes.txt"), s.t.namesIn("root"))
    }

    @Test fun sweepNeverDeletesAFolder() {
        val s = setup(hidden = false)
        s.t.add("root", ".launcher.json.tmp", dir)                                         // legacy name, but a folder
        val d = s.t.add("root", ".AGENTS.md.zz.tmp", dir); s.journal.add(s.key, d)          // journaled id, but a folder
        s.w.sweepRootTemps("root")
        assertEquals(listOf(".AGENTS.md.zz.tmp", ".launcher.json.tmp"), s.t.namesIn("root"))
        assertTrue(s.t.deleted.isEmpty())
    }

    @Test fun startSweepWaitsForASaveInProgress() {
        // The sweep runs (on another thread) while a save is writing its journaled root temp. The shared lock
        // makes it wait; without it, it deletes the temp the save is using and the save fails.
        val s = setup(hidden = true)
        var sweeper: Thread? = null
        s.r.onRootTempWrite = {
            sweeper = Thread { s.w.sweepRootTemps("root") }.also { it.start() }
            val th = sweeper!!
            while (th.state != Thread.State.BLOCKED && th.state != Thread.State.TERMINATED) Thread.yield()
        }
        s.w.writeVerified("root", "launcher.json", json, "{}".toByteArray(), "layout")
        sweeper!!.join()
        assertEquals("{}", s.t.text("launcher.json"))
        assertEquals(listOf(".configlauncher", "launcher.json"), s.t.namesIn("root"))
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileJournalSurvivesARestartPerTree() {
        val f = java.io.File(tmp.root, "config/saf-temps.txt")
        FileTempJournal(f).apply { add("A", "msd:1"); add("A", "msd:2"); add("B", "msd:3"); add("A", "msd:1"); remove("A", "msd:2") }
        val again = FileTempJournal(f) // a new process
        assertEquals(listOf("msd:1"), again.list("A"))
        assertEquals(listOf("msd:3"), again.list("B"))
    }

    @Test fun aSaveKilledMidWriteIsSweptOnTheNextStart() {
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "launcher.json", json, "old".toByteArray(), "layout")
        s.r.onRootTempWrite = { s.r.dead = true; throw RuntimeException("force-stop") }
        assertThrows(RuntimeException::class.java) { s.w.writeVerified("root", "launcher.json", json, "new".toByteArray(), "layout") }
        assertEquals("left behind, hidden from the listing", 1, s.t.namesIn("root").count { it.endsWith(".tmp") })
        s.r.dead = false
        val restarted = SafWrites(s.r, SafDirs(s.r, s.key), s.key, s.journal)
        restarted.sweepRootTemps("root")
        assertEquals(listOf(".configlauncher", "launcher.json"), s.t.namesIn("root"))
        assertEquals("old", s.t.text("launcher.json"))
    }
}
