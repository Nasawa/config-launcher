package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.model.InstalledApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #251 (Archimedes): the engine's side of the [SafDirs] suppression: resets on re-pick and RELOAD, and the one-shot warning. */
class SafSuppressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir = Document.MIME_TYPE_DIR
    private val doc = """{"schemaVersion":1,"revision":3,"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"a.b","x":0,"y":0}]}]}"""

    /** A folder source whose sidecars go through [SafDirs] on a fake provider, like SafSource. launcher.json is in memory. */
    private inner class TreeSource(val t: SafDirsTest.FakeTree, val key: String) : ConfigSource {
        private val dirs = SafDirs(t, key)
        var file: ByteArray? = doc.toByteArray()
        val sidecars = mutableMapOf<String, String>()
        override val displayName = "Folder: test"
        override fun stamp() = file?.let { Stamp("f", 1, it.size.toLong()) }
        override fun read(maxBytes: Int) = file
        override fun write(bytes: ByteArray) { dirs.ensure("root", ".configlauncher", dir); file = bytes }
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
            var d = dirs.ensure("root", ".configlauncher", dir)
            if (subdir.isNotEmpty()) d = dirs.ensure(d, subdir, dir)
            val id = dirs.ensure(d, name, "application/octet-stream")
            t.docs[id]!!.size = bytes.size.toLong(); sidecars[name] = String(bytes)
        }
        override fun suppressedNames() = dirs.suppressed()
        override fun takeNewlySuppressed() = dirs.takeNew()
        override fun clearSuppression() = SafDirs.clearTree(key)
    }

    private var keyN = 0
    private fun key() = "suppress-${System.identityHashCode(this)}-${keyN++}"
    /** `.configlauncher` exists but the provider doesn't list it (the #244 msd: case). Returns (tree, original id). */
    private fun hiddenTree(): Pair<SafDirsTest.FakeTree, String> {
        val t = SafDirsTest.FakeTree(); val id = t.add("root", ".configlauncher", dir); t.unlisted += ".configlauncher"; return t to id
    }
    private fun engine(src: ConfigSource) = ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs = 0, clock = { 1_000L })
    private val line = "Can't save .configlauncher: the storage app hides the one already in this folder, so no copy was made. Choose the folder again to retry."
    private fun ConfigEngine.takeNotice() = state.value.notice.also { consumeNotice() }

    /** The user deleted the hidden original from another app (and the provider lists normally again). */
    private fun SafDirsTest.FakeTree.userDeletes(id: String) { docs.remove(id); unlisted.clear() }

    @Test fun warningFiresOncePerSuppression() = runBlocking {
        val (t, _) = hiddenTree(); val e = engine(TreeSource(t, key()))
        e.poll(force = true)
        assertEquals(Msg(R.string.folder_hidden_notice, ".configlauncher"), e.takeNotice())
        assertEquals(listOf(line), e.warnings.value)
        repeat(5) { e.poll(force = true); e.poll() }
        assertNull("once, not on every poll", e.takeNotice())
        assertEquals(listOf(line), e.warnings.value) // the Warnings list keeps one line while it lasts
        assertEquals(1, t.creates.get())
    }

    @Test fun statusJsonGetsTheLineWhenWritable() = runBlocking {
        // apps.json hidden inside a visible .configlauncher: status.json there is still writable.
        val t = SafDirsTest.FakeTree(); val src = TreeSource(t, key()); val e = engine(src)
        e.poll(force = true)
        val cl = t.list("root")[".configlauncher"]!!.docId
        t.add(cl, "apps.json", "application/octet-stream", 9); t.unlisted += "apps.json"
        e.writeApps(listOf(InstalledApp("a.b", "a.b.Main", "A")))
        assertEquals(Msg(R.string.folder_hidden_notice, "apps.json"), e.takeNotice())
        val want = line.replace(".configlauncher", "apps.json")
        assertTrue(src.sidecars["status.json"]!!, src.sidecars["status.json"]!!.contains(want.replace("\"", "\\\"")))
        assertEquals(listOf(want), e.warnings.value)
        e.writeApps(listOf(InstalledApp("a.b", "a.b.Main", "B"))); e.poll(force = true)
        assertNull(e.takeNotice())
    }

    @Test fun rePickClearsTheSuppression() = runBlocking {
        val (t, original) = hiddenTree(); val k = key(); val e = engine(TreeSource(t, k))
        e.poll(force = true); e.takeNotice()
        t.userDeletes(original)
        e.poll(force = true)
        assertEquals("by design: not noticed until a re-pick, RELOAD or restart", 1, t.creates.get())
        assertTrue(e.moveTo(TreeSource(t, k), useMine = false)) // Choose folder → the same folder
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(listOf("status.json"), t.namesIn(t.list("root")[".configlauncher"]!!.docId)) // created again, used
        assertTrue(e.warnings.value.isEmpty())
        assertNull(e.takeNotice())
    }

    @Test fun reconnectClearsTheSuppressionAndReArmsTheWarning() = runBlocking {
        val (t, _) = hiddenTree(); val k = key(); val e = engine(TreeSource(t, k))
        e.poll(force = true); e.takeNotice()
        e.switchSource(TreeSource(t, k)) // Reconnect: still hidden, so it's tried once more and reported again
        assertEquals(2, t.creates.get())
        assertEquals(Msg(R.string.folder_hidden_notice, ".configlauncher"), e.takeNotice())
        assertEquals(listOf(".configlauncher"), t.namesIn("root")) // the renamed copy was removed again
    }

    @Test fun reloadClearsTheSuppression() = runBlocking {
        val (t, original) = hiddenTree(); val e = engine(TreeSource(t, key()))
        e.poll(force = true); e.takeNotice()
        t.userDeletes(original)
        e.reloadForAgent()
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(listOf("status.json"), t.namesIn(t.list("root")[".configlauncher"]!!.docId)) // created again, used
        assertTrue(e.warnings.value.isEmpty())
    }

    @Test fun reloadClearsEveryTree() = runBlocking {
        val (other, _) = hiddenTree(); val ko = key()
        assertThrows(java.io.IOException::class.java) { SafDirs(other, ko).ensure("root", ".configlauncher", dir) }
        val e = engine(TreeSource(SafDirsTest.FakeTree(), key()))
        e.reloadForAgent()
        assertTrue(SafDirs(other, ko).suppressed().isEmpty())
    }

    @Test fun anotherTreeIsNotSuppressed() = runBlocking {
        val (hidden, _) = hiddenTree()
        engine(TreeSource(hidden, key())).poll(force = true)
        val t = SafDirsTest.FakeTree(); val src = TreeSource(t, key()); val e = engine(src)
        e.poll(force = true)
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertNotNull(src.sidecars["status.json"])
        assertNull(e.takeNotice()); assertTrue(e.warnings.value.isEmpty())
    }
}
