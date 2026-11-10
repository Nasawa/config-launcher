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
        val shas = mutableMapOf<String, String?>()
        override fun add(treeKey: String, docId: String, sha256: String?) { ids.getOrPut(treeKey) { mutableListOf() } += docId; shas[docId] = sha256 }
        override fun remove(treeKey: String, docId: String) { ids[treeKey]?.remove(docId) }
        override fun list(treeKey: String) = ids[treeKey].orEmpty().toList()
        override fun sha256(treeKey: String, docId: String) = shas[docId]
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
    private fun counter(): () -> String { var n = 0; return { "%08x".format(n++) } }
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
        assertTrue(s.r.asked.toString(), ".launcher.json.00000000.tmp" in s.r.asked && ".AGENTS.md.00000001.tmp" in s.r.asked) // a new name per save
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun hiddenLeftoverFromAKilledSaveNeverBlocksSavesAndIsSweptById() {
        // Grace's repro: the provider hides dot files, and a force-stop left a hidden 1888-byte temp (journaled).
        val s = setup(hidden = true, hideDots = true)
        val stale = s.t.add("root", ".launcher.json.tmp", bin); s.t.write(stale, ByteArray(1888))
        val stale2 = s.t.add("root", ".launcher.json.deadbeef.tmp", bin); s.t.write(stale2, ByteArray(1888)); s.journal.add(s.key, stale2)
        repeat(5) { i -> s.w.writeVerified("root", "launcher.json", json, "$i".toByteArray(), "layout") }
        assertEquals("4", s.t.text("launcher.json"))
        assertEquals(emptyList<String>(), s.t.numbered())
        assertEquals(listOf(".configlauncher", ".launcher.json.deadbeef.tmp", ".launcher.json.tmp", "launcher.json"), s.t.namesIn("root"))
        s.w.sweepRootTemps("root") // next start
        assertFalse("tracked by id: deleted though the listing hides it", s.t.docs.containsKey(stale2))
        assertTrue("untracked and hidden: orphaned, never guessed at by name", s.t.docs.containsKey(stale))
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun renamedRootTempIsUsedAndDeletedById() {
        // Even a colliding name (fixed token, hidden leftover) is accepted as returned and deleted by its id.
        val s = setup(hidden = true, hideDots = true, token = { "aaaaaaaa" })
        val stale = s.t.add("root", ".launcher.json.aaaaaaaa.tmp", bin); s.t.write(stale, ByteArray(1888))
        repeat(5) { i -> s.w.writeVerified("root", "launcher.json", json, "$i".toByteArray(), "layout") }
        assertEquals("4", s.t.text("launcher.json"))
        assertEquals(5, s.r.asked.count { it == ".launcher.json.aaaaaaaa.tmp" })
        assertEquals(emptyList<String>(), s.t.numbered()) // each renamed `(1)` copy was deleted by id
        assertEquals(listOf(".configlauncher", ".launcher.json.aaaaaaaa.tmp", "launcher.json"), s.t.namesIn("root"))
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
        val w = SafWrites(failing, SafDirs(failing, s.key), s.key, s.journal, { "cafef00d" })
        val e = assertThrows(IOException::class.java) { w.writeVerified("root", "launcher.json", json, "new".toByteArray(), "layout") }
        assertTrue(e.message!!, e.message!!.contains("kept in .launcher.json.cafef00d.tmp"))
        assertEquals("new", s.t.text(".launcher.json.cafef00d.tmp"))
        assertTrue("out of the journal, so no sweep deletes it", s.journal.list(s.key).isEmpty())
        w.sweepRootTemps("root")
        assertEquals("new", s.t.text(".launcher.json.cafef00d.tmp"))
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
        val d = s.t.add("root", ".AGENTS.md.0000abcd.tmp", dir); s.journal.add(s.key, d)          // journaled id, but a folder
        s.w.sweepRootTemps("root")
        assertEquals(listOf(".AGENTS.md.0000abcd.tmp", ".launcher.json.tmp"), s.t.namesIn("root"))
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

    // ---- #251 NO-GO at 3fc9b0e (Grace): a save killed after the target was truncated ----

    private val valid = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}""" + "\n"
    private val validNew = """{"schemaVersion":1,"pages":[{"id":"home","items":[]},{"id":"two","items":[]}]}""" + "\n"

    /** A force-stop: no catch in the save runs (an Error, not an Exception); `finally` reaches a dead provider. */
    private class Killed : Error("force-stop")

    /** A save of [name] killed while writing the target: [leave] is what the target holds when the process dies. */
    private fun Setup.killDuringTargetWrite(name: String, bytes: String, leave: (ByteArray) -> ByteArray = { ByteArray(0) }) {
        val dying = object : DocTree by r {
            override fun write(docId: String, b: ByteArray) {
                if (t.docs[docId]?.name == name) { t.write(docId, leave(b)); r.dead = true; throw Killed() }
                r.write(docId, b)
            }
        }
        val w = SafWrites(dying, SafDirs(dying, key), key, journal, counter())
        assertThrows(Killed::class.java) { w.writeVerified("root", name, if (name == "launcher.json") json else bin, bytes.toByteArray(), name) }
        r.dead = false
    }
    private fun Setup.restart() = SafWrites(r, SafDirs(r, key), key, journal)
    private fun Setup.temps() = t.namesIn("root").filter { it.endsWith(".tmp") }

    @Test fun killedAfterTruncateRestoresLauncherJsonFromTheTempOnNextStart() {
        // Grace's repro on msd:12: launcher.json at 0 B, the hidden journaled temp holds the full new version.
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "launcher.json", json, valid.toByteArray(), "layout")
        s.killDuringTargetWrite("launcher.json", validNew)
        assertEquals("", s.t.text("launcher.json"))
        assertEquals(validNew, s.t.text(s.temps().single()))
        val warnings = s.restart().sweepRootTemps("root")
        assertEquals(validNew, s.t.text("launcher.json"))
        assertTrue(ConfigCodecOk(s.t.text("launcher.json")!!))
        assertEquals(emptyList<String>(), s.temps())
        assertTrue(s.journal.list(s.key).isEmpty())
        assertEquals(emptyList<String>(), warnings)
    }

    @Test fun killedMidTargetWriteRestoresAPartialLauncherJson() {
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "launcher.json", json, valid.toByteArray(), "layout")
        s.killDuringTargetWrite("launcher.json", validNew) { it.copyOf(20) } // a prefix of the new bytes
        s.restart().sweepRootTemps("root")
        assertEquals(validNew, s.t.text("launcher.json"))
        assertEquals(emptyList<String>(), s.temps())
    }

    @Test fun killedAfterTruncateRestoresAgentsMdToo() {
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "AGENTS.md", bin, "agents v1".toByteArray(), "AGENTS.md")
        s.killDuringTargetWrite("AGENTS.md", "agents v2")
        assertEquals("", s.t.text("AGENTS.md"))
        s.restart().sweepRootTemps("root")
        assertEquals("agents v2", s.t.text("AGENTS.md"))
        assertEquals(emptyList<String>(), s.temps())
    }

    @Test fun journalEntryWithoutAHashRestoresOnlyAParsingLauncherJson() {
        // A journal from the 3fc9b0e build has no hash: the temp must parse to count as a complete copy.
        val s = setup(hidden = true, hideDots = true)
        val target = s.t.add("root", "launcher.json", json) // 0 B
        val good = s.t.add("root", ".launcher.json.0badc0de.tmp", bin); s.t.write(good, validNew.toByteArray()); s.journal.add(s.key, good)
        s.restart().sweepRootTemps("root")
        assertEquals(validNew, String(s.t.read(target)!!))
        assertFalse(s.t.docs.containsKey(good))

        val s2 = setup(hidden = true, hideDots = true)
        val t2 = s2.t.add("root", "launcher.json", json)
        val half = s2.t.add("root", ".launcher.json.0badc0de.tmp", bin); s2.t.write(half, validNew.take(30).toByteArray()); s2.journal.add(s2.key, half)
        s2.restart().sweepRootTemps("root")
        assertEquals("a temp that doesn't parse is never restored", "", String(s2.t.read(t2)!!))
    }

    @Test fun anIncompleteTempNeverReplacesTheTarget() {
        // Killed while writing the temp: the hash doesn't match, the target was never touched, the temp goes.
        val s = setup(hidden = true, hideDots = true)
        val target = s.t.add("root", "launcher.json", json)
        val tmp = s.t.add("root", ".launcher.json.12345678.tmp", bin); s.t.write(tmp, valid.toByteArray())
        s.journal.add(s.key, tmp, dev.nasawa.configlauncher.config.ConfigCodec.sha256(validNew.toByteArray()))
        s.restart().sweepRootTemps("root")
        assertEquals("", String(s.t.read(target)!!))
        assertFalse(s.t.docs.containsKey(tmp))
    }

    @Test fun failedRestoreKeepsTheTempAndWarns() {
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "launcher.json", json, valid.toByteArray(), "layout")
        s.killDuringTargetWrite("launcher.json", validNew)
        val tmpName = s.temps().single()
        val readOnly = object : DocTree by s.r {
            override fun write(docId: String, bytes: ByteArray) { throw IOException("read-only") }
        }
        val w = SafWrites(readOnly, SafDirs(readOnly, s.key), s.key, s.journal)
        val warnings = w.sweepRootTemps("root")
        assertEquals(listOf("Couldn't finish saving launcher.json after the app was stopped: the new version is kept in $tmpName. Copy it over launcher.json to keep your last edit."), warnings)
        assertEquals(warnings, w.tempWarnings())
        assertEquals(validNew, s.t.text(tmpName))
        assertEquals("still journaled: tried again next start", 1, s.journal.list(s.key).size)
        val ok = s.restart()
        assertEquals(emptyList<String>(), ok.sweepRootTemps("root"))
        assertEquals(validNew, s.t.text("launcher.json"))
        assertEquals(emptyList<String>(), ok.tempWarnings())
    }

    @Test fun someoneElsesBrokenLauncherJsonIsNotOverwrittenAndTheEditIsKept() {
        val s = setup(hidden = true, hideDots = true)
        s.w.writeVerified("root", "launcher.json", json, valid.toByteArray(), "layout")
        s.killDuringTargetWrite("launcher.json", validNew)
        val tmpName = s.temps().single()
        val target = s.t.docs.values.single { it.name == "launcher.json" }.id
        s.t.write(target, "{ an agent's half edit".toByteArray()) // written while the app was stopped
        val warnings = s.restart().sweepRootTemps("root")
        assertEquals("{ an agent's half edit", s.t.text("launcher.json"))
        assertEquals(listOf("launcher.json has problems, and the app was stopped while saving it: your last edit on the phone is kept in $tmpName."), warnings)
        assertEquals(validNew, s.t.text(tmpName))
        s.t.write(target, valid.toByteArray()) // fixed
        assertEquals(emptyList<String>(), s.restart().sweepRootTemps("root"))
        assertEquals(emptyList<String>(), s.temps())
    }

    @Test fun sweepMatchesOnlyTheExactTempNames() {
        val s = setup(hidden = false)
        val user = listOf(".notes.tmp", ".launcher.json.tmp.tmp", ".launcher.json.ABCDEF12.tmp", ".launcher.json.1234567.tmp",
            ".AGENTS.md.123456789.tmp", ".launcher.json.12345678.tmp.bak", "launcher.json.12345678.tmp")
            .map { n -> s.t.add("root", n, bin).also { s.t.write(it, "mine".toByteArray()); s.journal.add(s.key, it) } }
        val ours = listOf(".launcher.json.0123abcd.tmp", ".AGENTS.md.89abcdef.tmp", ".launcher.json.0123abcd (1).tmp")
            .map { n -> s.t.add("root", n, bin).also { s.t.write(it, "x".toByteArray()); s.journal.add(s.key, it) } }
        s.restart().sweepRootTemps("root")
        user.forEach { assertTrue(s.t.docs[it]!!.name, s.t.docs.containsKey(it)) }
        ours.forEach { assertFalse(s.t.docs.containsKey(it)) }
        assertTrue("user files dropped from the journal, never deleted", s.journal.list(s.key).isEmpty())
        assertEquals("launcher.json", SafWrites.tempTarget(".launcher.json.0123abcd.tmp"))
        assertEquals("AGENTS.md", SafWrites.tempTarget(".AGENTS.md.89abcdef (2).tmp"))
        assertNull(SafWrites.tempTarget(".launcher.json.0123ABCD.tmp"))
    }

    @Test fun journalEntriesForMissingDocumentsAreDropped() {
        // Grace saw ea7c7721 survive: the provider throws "not a descendant" for a document that's gone.
        val s = setup(hidden = false)
        s.journal.add(s.key, "gone-null"); s.journal.add(s.key, "gone-throws"); s.journal.add(s.key, "gone-fnf")
        val provider = object : DocTree by s.r {
            override fun info(docId: String) = when (docId) {
                "gone-throws" -> throw SecurityException("Document gone-throws is not a descendant of root")
                "gone-fnf" -> throw java.io.FileNotFoundException(docId)
                else -> s.r.info(docId)
            }
        }
        SafWrites(provider, SafDirs(provider, s.key), s.key, s.journal).sweepRootTemps("root")
        assertTrue(s.journal.list(s.key).toString(), s.journal.list(s.key).isEmpty())
    }

    @Test fun journalEntriesAreKeptWhenAccessIsLost() {
        val s = setup(hidden = false)
        s.journal.add(s.key, "a"); s.journal.add(s.key, "b")
        val revoked = object : DocTree by s.r {
            override fun info(docId: String): DocInfo? = throw AccessLostException("folder permission was revoked")
        }
        SafWrites(revoked, SafDirs(revoked, s.key), s.key, s.journal).sweepRootTemps("root")
        assertEquals(listOf("a", "b"), s.journal.list(s.key))
    }

    @Test fun aJournalThatCantBeWrittenStillGetsTheTempDeleted() {
        val s = setup(hidden = true)
        val broken = object : TempJournal {
            override fun add(treeKey: String, docId: String, sha256: String?) = throw IOException("no space")
            override fun remove(treeKey: String, docId: String) = throw IOException("no space")
            override fun list(treeKey: String) = emptyList<String>()
        }
        val w = SafWrites(s.r, SafDirs(s.r, s.key), s.key, broken, counter())
        val e = assertThrows(IOException::class.java) { w.writeVerified("root", "launcher.json", json, "{}".toByteArray(), "layout") }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't save layout"))
        assertEquals("no orphaned temp", listOf(".configlauncher"), s.t.namesIn("root"))
    }

    @Test fun fileJournalKeepsHashesAndReadsOldLines() {
        val f = java.io.File(tmp.root, "config/saf-temps.txt")
        f.parentFile!!.mkdirs(); f.writeText("A\tmsd:1\n") // a 3fc9b0e line
        FileTempJournal(f).add("A", "msd:2", "abc")
        val again = FileTempJournal(f)
        assertEquals(listOf("msd:1", "msd:2"), again.list("A"))
        assertNull(again.sha256("A", "msd:1")); assertEquals("abc", again.sha256("A", "msd:2"))
    }

    // ---- #262 (Grace, #251 GO at 5e2d541): stale journal entries on msd:12 ----

    /** A MediaStore-like provider: [rowOnly] ids still return a row (and a name) after the file is gone, then throw on use. */
    private fun Setup.ghostRows(vararg rowOnly: String, on: String = "read") = object : DocTree by r {
        override fun info(docId: String) = if (docId in rowOnly) DocInfo(bin, 0) else r.info(docId)
        override fun displayName(docId: String) =
            if (docId in rowOnly) { if (on == "name") throw java.io.FileNotFoundException(docId) else ".launcher.json.ea7c7721.tmp" } else r.displayName(docId)
        override fun read(docId: String) = if (docId in rowOnly && on == "read") throw java.io.FileNotFoundException("No item at content://media/$docId") else r.read(docId)
    }

    @Test fun aRowForADeletedTempThatThrowsOnReadIsDropped() {
        // Grace's ea7c7721 / 3d4068d8: a row comes back, and FileNotFoundException on read. Gone, not transient.
        val s = setup(hidden = true, hideDots = true)
        s.journal.add(s.key, "ea7c7721-id"); s.journal.add(s.key, "3d4068d8-id")
        val p = s.ghostRows("ea7c7721-id", "3d4068d8-id")
        assertEquals(emptyList<String>(), SafWrites(p, SafDirs(p, s.key), s.key, s.journal).sweepRootTemps("root"))
        assertTrue(s.journal.list(s.key).toString(), s.journal.list(s.key).isEmpty())
        val n = s.ghostRows("ghost", on = "name"); s.journal.add(s.key, "ghost")
        SafWrites(n, SafDirs(n, s.key), s.key, s.journal).sweepRootTemps("root")
        assertTrue("not-found on the name lookup: gone too", s.journal.list(s.key).isEmpty())
    }

    @Test fun anEmptyRowIsDropped() {
        val s = setup(hidden = true, hideDots = true)
        s.journal.add(s.key, "empty-row")
        val p = object : DocTree by s.r {
            override fun info(docId: String) = if (docId == "empty-row") DocInfo("", 0) else s.r.info(docId)
            override fun displayName(docId: String) = if (docId == "empty-row") null else s.r.displayName(docId)
        }
        SafWrites(p, SafDirs(p, s.key), s.key, s.journal).sweepRootTemps("root")
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun aTempThatVanishesBeforeItsDeleteIsDropped() {
        val s = setup(hidden = true, hideDots = true)
        val tmp = s.t.add("root", ".launcher.json.0123abcd.tmp", bin); s.t.write(tmp, "x".toByteArray()); s.journal.add(s.key, tmp)
        val p = object : DocTree by s.r {
            override fun delete(docId: String): Boolean { s.t.docs.remove(docId); throw java.io.FileNotFoundException(docId) }
        }
        SafWrites(p, SafDirs(p, s.key), s.key, s.journal).sweepRootTemps("root")
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    /** A provider whose reads of [busy] keep failing with a real transient error. */
    private fun Setup.busy(vararg busy: String) = object : DocTree by r {
        override fun read(docId: String) = if (docId in busy) throw IOException("provider busy") else r.read(docId)
    }

    @Test fun transientErrorsKeepTheEntryUntilMaxStrikesThenDropItButNeverTheFile() {
        val s = setup(hidden = true, hideDots = true)
        val tmp = s.t.add("root", ".launcher.json.0123abcd.tmp", bin); s.t.write(tmp, validNew.toByteArray())
        val f = java.io.File(this.tmp.root, "config/saf-temps.txt"); val j = FileTempJournal(f)
        j.add(s.key, tmp)
        val p = s.busy(tmp)
        repeat(SafWrites.MAX_STRIKES - 1) { start ->
            SafWrites(p, SafDirs(p, s.key), s.key, FileTempJournal(f)).sweepRootTemps("root") // a new process each start
            assertEquals("kept after ${start + 1} failed starts", listOf(tmp), FileTempJournal(f).list(s.key))
        }
        SafWrites(p, SafDirs(p, s.key), s.key, FileTempJournal(f)).sweepRootTemps("root")
        assertTrue("dropped after ${SafWrites.MAX_STRIKES} failed starts in a row", FileTempJournal(f).list(s.key).isEmpty())
        assertEquals("the file is never deleted for it", validNew, s.t.text(".launcher.json.0123abcd.tmp"))
        assertFalse(tmp in s.t.deleted)
    }

    @Test fun aRefusedDeleteCountsAsAStrike() {
        val s = setup(hidden = true, hideDots = true)
        val tmp = s.t.add("root", ".launcher.json.0123abcd.tmp", bin); s.t.write(tmp, "x".toByteArray())
        val f = java.io.File(this.tmp.root, "config/saf-temps.txt"); FileTempJournal(f).add(s.key, tmp)
        val p = object : DocTree by s.r { override fun delete(docId: String) = false }
        repeat(SafWrites.MAX_STRIKES) { SafWrites(p, SafDirs(p, s.key), s.key, FileTempJournal(f)).sweepRootTemps("root") }
        assertTrue(FileTempJournal(f).list(s.key).isEmpty())
    }

    @Test fun strikesAreConsecutiveAndAccessLossCostsNone() {
        val s = setup(hidden = true, hideDots = true)
        val tmp = s.t.add("root", ".launcher.json.0123abcd.tmp", bin); s.t.write(tmp, validNew.toByteArray())
        val f = java.io.File(this.tmp.root, "config/saf-temps.txt"); FileTempJournal(f).add(s.key, tmp)
        val target = s.t.add("root", "launcher.json", json); s.t.write(target, "{ someone's half edit".toByteArray())
        val p = s.busy(tmp)
        val revoked = object : DocTree by s.r { override fun info(docId: String): DocInfo? = throw AccessLostException("revoked") }
        fun start(t: DocTree) = SafWrites(t, SafDirs(t, s.key), s.key, FileTempJournal(f)).sweepRootTemps("root")
        repeat(SafWrites.MAX_STRIKES - 1) { start(p) }
        repeat(10) { start(revoked) } // no access: no strike
        assertEquals(listOf(tmp), FileTempJournal(f).list(s.key))
        assertEquals("kept on purpose (the only copy): settled, strikes restart", 1, start(s.r).size)
        repeat(SafWrites.MAX_STRIKES - 1) { start(p) }
        assertEquals(listOf(tmp), FileTempJournal(f).list(s.key))
        start(p)
        assertTrue(FileTempJournal(f).list(s.key).isEmpty())
        assertEquals(validNew, s.t.text(".launcher.json.0123abcd.tmp"))
    }

    @Test fun aUserDotTmpIsNeverDeletedWhateverTheProviderSays() {
        // Reusable ids: a journaled id that now names a user's file. Dropped from the journal, never deleted.
        val s = setup(hidden = false)
        val mine = listOf(".notes.tmp", ".launcher.json.tmp.tmp", ".launcher.json.ABCDEF12.tmp")
            .map { n -> s.t.add("root", n, bin).also { s.t.write(it, "mine".toByteArray()); s.journal.add(s.key, it) } }
        val p = object : DocTree by s.r { override fun read(docId: String) = throw IOException("busy") }
        repeat(SafWrites.MAX_STRIKES + 1) { SafWrites(p, SafDirs(p, s.key), s.key, s.journal).sweepRootTemps("root") }
        mine.forEach { assertTrue(s.t.docs.containsKey(it)); assertFalse(it in s.t.deleted) }
        assertTrue(s.journal.list(s.key).isEmpty())
    }

    @Test fun fileJournalKeepsStrikesAndReadsOlderLines() {
        val f = java.io.File(tmp.root, "config/saf-temps.txt")
        f.parentFile!!.mkdirs(); f.writeText("A\tmsd:1\nA\tmsd:2\tabc\n") // 3fc9b0e and 5e2d541 lines
        val j = FileTempJournal(f)
        assertEquals(1, j.strike("A", "msd:1")); assertEquals(2, j.strike("A", "msd:1")); assertEquals(1, j.strike("A", "msd:2"))
        assertEquals(0, j.strike("A", "not-journaled")); assertEquals(listOf("msd:1", "msd:2"), j.list("A"))
        val again = FileTempJournal(f)
        assertNull(again.sha256("A", "msd:1")); assertEquals("abc", again.sha256("A", "msd:2"))
        assertEquals(3, again.strike("A", "msd:1"))
        again.clearStrikes("A", "msd:1"); assertEquals(1, again.strike("A", "msd:1"))
        assertEquals("abc", FileTempJournal(f).sha256("A", "msd:2"))
    }

    private fun ConfigCodecOk(text: String) = dev.nasawa.configlauncher.config.ConfigCodec.decode(text) is dev.nasawa.configlauncher.config.ParseResult.Ok
}
