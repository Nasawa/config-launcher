package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** #251 (Grace): where [SafWrites] stages its temp, and that root temps are cleaned up without touching user files. */
class SafWritesTest {
    private val dir = Document.MIME_TYPE_DIR
    private val bin = "application/octet-stream"
    private var keyN = 0
    private fun key() = "writes-${System.identityHashCode(this)}-${keyN++}"

    /** Records every name the provider was asked to create; can fail the target write. */
    private class Recording(val t: SafDirsTest.FakeTree) : DocTree by t {
        val asked = mutableListOf<String>()
        var failTargetWrite: String? = null
        override fun create(parentDocId: String, mime: String, name: String) = t.create(parentDocId, mime, name).also { asked += name }
        override fun write(docId: String, bytes: ByteArray) {
            if (t.docs[docId]?.name == failTargetWrite) { t.write(docId, "half".toByteArray()); throw IOException("disk full") }
            t.write(docId, bytes)
        }
    }

    private fun setup(hidden: Boolean): Triple<SafDirsTest.FakeTree, Recording, SafWrites> {
        val t = SafDirsTest.FakeTree()
        if (hidden) { t.add("root", ".configlauncher", dir); t.unlisted += ".configlauncher" }
        val r = Recording(t)
        return Triple(t, r, SafWrites(r, SafDirs(r, key())))
    }
    private fun SafDirsTest.FakeTree.text(name: String) = list("root")[name]?.let { String(read(it.docId)!!) }

    @Test fun normalSaveStagesInsideConfiglauncher() {
        val (t, r, w) = setup(hidden = false)
        w.writeVerified("root", "launcher.json", "application/json", "{}".toByteArray(), "layout")
        assertEquals("{}", t.text("launcher.json"))
        assertEquals(listOf(".configlauncher", "launcher.json.tmp", "launcher.json"), r.asked)
        assertEquals(listOf(".configlauncher", "launcher.json"), t.namesIn("root"))
    }

    @Test fun suppressedFolderStagesInTheRootAndDeletesTheTemp() {
        val (t, r, w) = setup(hidden = true)
        repeat(3) { i ->
            w.writeVerified("root", "launcher.json", "application/json", "{\"v\":$i}".toByteArray(), "layout")
            w.writeVerified("root", "AGENTS.md", bin, "agents $i".toByteArray(), "AGENTS.md")
        }
        assertEquals("{\"v\":2}", t.text("launcher.json")); assertEquals("agents 2", t.text("AGENTS.md"))
        assertEquals(listOf(".configlauncher", "AGENTS.md", "launcher.json"), t.namesIn("root")) // no (N), no temp left
        assertEquals(1, r.asked.count { it == ".configlauncher" }) // tried once, then suppressed
        assertTrue(".launcher.json.tmp" in r.asked && ".AGENTS.md.tmp" in r.asked)
    }

    @Test fun providerThatHidesDotFilesStillSavesRepeatedly() {
        val (t, r, w) = setup(hidden = true)
        t.unlisted += listOf(".launcher.json.tmp") // the root temp is hidden too (MediaStore skips dot names)
        repeat(5) { i -> w.writeVerified("root", "launcher.json", "application/json", "$i".toByteArray(), "layout") }
        assertEquals("4", t.text("launcher.json"))
        assertEquals(listOf(".configlauncher", "launcher.json"), t.namesIn("root"))
        assertTrue(r.asked.none { it.contains("(") })
    }

    @Test fun failedRootTargetWriteIsRestoredAndTheTempDeleted() {
        val (t, r, w) = setup(hidden = true)
        w.writeVerified("root", "launcher.json", "application/json", "old".toByteArray(), "layout")
        r.failTargetWrite = "launcher.json"
        assertThrows(IOException::class.java) { w.writeVerified("root", "launcher.json", "application/json", "new".toByteArray(), "layout") }
        r.failTargetWrite = null
        assertEquals(listOf(".configlauncher", "launcher.json"), t.namesIn("root"))
    }

    @Test fun nextNormalSaveDeletesALeftoverRootTemp() {
        val (t, _, w) = setup(hidden = false)
        t.write(t.add("root", ".launcher.json.tmp", bin), "left by a killed save".toByteArray())
        w.writeVerified("root", "launcher.json", "application/json", "{}".toByteArray(), "layout")
        assertEquals(listOf(".configlauncher", "launcher.json"), t.namesIn("root"))
    }

    @Test fun startSweepDeletesOnlyOurRootTemps() {
        val (t, _, w) = setup(hidden = false)
        for (n in listOf(".launcher.json.tmp", ".AGENTS.md.tmp", "launcher.json.tmp", ".notes.tmp", "AGENTS.md", "launcher.json"))
            t.write(t.add("root", n, bin), "x".toByteArray())
        t.add("root", ".layout.json.tmp", dir)
        w.sweepRootTemps("root")
        assertEquals(listOf(".layout.json.tmp", ".notes.tmp", "AGENTS.md", "launcher.json", "launcher.json.tmp"), t.namesIn("root"))
    }
}
