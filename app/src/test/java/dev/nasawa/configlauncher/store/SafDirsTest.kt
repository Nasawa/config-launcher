package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** #244: a provider that auto-renames on collision, lists stale or hidden children, and concurrent callers. */
class SafDirsTest {
    private val dir = Document.MIME_TYPE_DIR
    private val bin = "application/octet-stream"

    /** In-memory SAF provider. Each map op is atomic, but find-then-create isn't, so overlapping callers race. */
    internal class FakeTree : DocTree {
        class Doc(val id: String, val parent: String, var name: String, val mime: String, var size: Long = 0)
        val docs = ConcurrentHashMap(mapOf("root" to Doc("root", "", "root", Document.MIME_TYPE_DIR)))
        private val next = AtomicInteger()
        /** Names the listing never shows (a MediaStore tree hiding the dot-folder). */
        val unlisted = mutableSetOf<String>()
        /** Created ids stay out of listings for this many further list() calls (a cached listing). */
        var staleFor = 0
        private val staleLeft = ConcurrentHashMap<String, Int>()
        val creates = AtomicInteger()
        val deleted: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        fun add(parent: String, name: String, mime: String, size: Long = 0): String {
            val id = "d${next.getAndIncrement()}"; docs[id] = Doc(id, parent, name, mime, size); return id
        }
        fun namesIn(parent: String) = docs.values.filter { it.parent == parent }.map { it.name }.sorted()

        override fun list(parentDocId: String): Map<String, DocChild> {
            Thread.yield()
            val out = docs.values.filter { it.parent == parentDocId && it.name !in unlisted && (staleLeft[it.id] ?: 0) == 0 }
                .associate { it.name to DocChild(it.id, it.mime, 0, it.size) }
            staleLeft.replaceAll { _, n -> maxOf(0, n - 1) }
            return out
        }
        override fun create(parentDocId: String, mime: String, name: String): String {
            creates.incrementAndGet()
            Thread.yield()
            val taken = docs.values.filter { it.parent == parentDocId }.map { it.name }.toSet()
            var actual = name; var n = 1
            while (actual in taken) actual = "$name (${n++})"
            return add(parentDocId, actual, mime).also { staleLeft[it] = staleFor }
        }
        override fun displayName(docId: String) = docs[docId]?.name
        override fun delete(docId: String): Boolean {
            require(docs.values.none { it.parent == docId }) { "deleted a non-empty folder" }
            deleted += docId; return docs.remove(docId) != null
        }
    }

    private var keyN = 0
    private fun key() = "tree-${System.identityHashCode(this)}-${keyN++}" // fresh process-wide cache per test

    @Test fun createsOnceAndReusesTheExactName() {
        val t = FakeTree(); val k = key()
        val a = SafDirs(t, k).ensure("root", ".configlauncher", dir)
        repeat(5) { assertEquals(a, SafDirs(t, k).ensure("root", ".configlauncher", dir)) }
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(1, t.creates.get())
    }

    @Test fun reusesAnExistingFolderWithoutCreating() {
        val t = FakeTree(); val existing = t.add("root", ".configlauncher", dir)
        assertEquals(existing, SafDirs(t, key()).ensure("root", ".configlauncher", dir))
        assertEquals(0, t.creates.get())
    }

    @Test fun staleListingAfterCreateReusesTheFolderJustMade() {
        val t = FakeTree().apply { staleFor = 3 }; val k = key()
        val a = SafDirs(t, k).ensure("root", ".configlauncher", dir)
        repeat(5) { assertEquals(a, SafDirs(t, k).ensure("root", ".configlauncher", dir)) } // repeated starts
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(1, t.creates.get())
    }

    @Test fun hiddenOriginalNeverLeavesNumberedCopies() {
        // The #244 repro: `.configlauncher` exists but the msd: tree doesn't list it.
        val t = FakeTree(); val k = key()
        val original = t.add("root", ".configlauncher", dir)
        t.add(original, "status.json", bin, 10)
        t.unlisted += ".configlauncher"
        repeat(10) { // ten sidecar writes / starts
            val e = assertThrows(IOException::class.java) { SafDirs(t, k).ensure("root", ".configlauncher", dir) }
            assertTrue(e.message!!, e.message!!.contains(".configlauncher"))
        }
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(1, t.creates.get()) // tried once, then stopped creating
        assertEquals(listOf("status.json"), t.namesIn(original)) // user/app content untouched
    }

    @Test fun renamedCreateUsesTheOriginalOnceItIsListed() {
        val t = FakeTree(); val k = key()
        val original = t.add("root", ".configlauncher", dir)
        // First listing misses it (cached), the re-list after the rename sees it.
        val tree = object : DocTree by t {
            var calls = 0
            override fun list(parentDocId: String) =
                if (parentDocId == "root" && calls++ == 0) emptyMap() else t.list(parentDocId)
        }
        assertEquals(original, SafDirs(tree, k).ensure("root", ".configlauncher", dir))
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(original, SafDirs(t, k).ensure("root", ".configlauncher", dir))
    }

    @Test fun neverDeletesPreExistingNumberedFolders() {
        val t = FakeTree(); val k = key()
        t.add("root", ".configlauncher", dir); t.unlisted += ".configlauncher"
        val old1 = t.add("root", ".configlauncher (1)", dir)
        t.add(old1, "status.json", bin, 5)
        assertThrows(IOException::class.java) { SafDirs(t, k).ensure("root", ".configlauncher", dir) }
        assertEquals(listOf(".configlauncher", ".configlauncher (1)"), t.namesIn("root")) // our "(2)" was removed
        assertEquals(1, t.deleted.size)
        assertTrue(t.docs.containsKey(old1))
    }

    @Test fun keepsTheRenamedDocumentWhenItIsNotEmpty() {
        val t = FakeTree(); val k = key()
        t.add("root", "AGENTS.md", bin, 100); t.unlisted += "AGENTS.md"
        // Provider fills the new file before we look (size > 0): not ours to delete.
        val tree = object : DocTree by t {
            override fun create(parentDocId: String, mime: String, name: String) =
                t.create(parentDocId, mime, name).also { t.docs[it]!!.size = 1 }
        }
        assertThrows(IOException::class.java) { SafDirs(tree, k).ensure("root", "AGENTS.md", bin) }
        assertTrue(t.deleted.isEmpty())
    }

    @Test fun renamedFileIsDeletedWhenEmpty() {
        val t = FakeTree(); val k = key()
        t.add("root", "launcher.json", "application/json", 50); t.unlisted += "launcher.json"
        assertThrows(IOException::class.java) { SafDirs(t, k).ensure("root", "launcher.json", "application/json") }
        assertEquals(listOf("launcher.json"), t.namesIn("root"))
    }

    @Test fun concurrentCallersShareOneFolder() {
        val t = FakeTree(); val k = key()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val ids = (1..32).map { i ->
            pool.submit<List<String>> {
                go.await()
                val c = SafDirs(t, k).ensure("root", ".configlauncher", dir)
                val sub = if (i % 2 == 0) "backup" else "broken"
                listOf(c, SafDirs(t, k).ensure(c, sub, dir))
            }
        }
        go.countDown()
        val got = ids.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, got.map { it[0] }.toSet().size)
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
        assertEquals(listOf("backup", "broken"), t.namesIn(got[0][0]))
        assertEquals(3, t.creates.get())
    }

    @Test fun deletedFolderIsRecreatedNotRenamed() {
        val t = FakeTree(); val k = key()
        val a = SafDirs(t, k).ensure("root", ".configlauncher", dir)
        t.docs.remove(a) // the user deleted it
        val b = SafDirs(t, k).ensure("root", ".configlauncher", dir)
        assertNotEquals(a, b)
        assertEquals(listOf(".configlauncher"), t.namesIn("root"))
    }

    // #251 (Archimedes): suppression lifecycle.

    private fun hiddenTree() = FakeTree().apply { add("root", ".configlauncher", Document.MIME_TYPE_DIR); unlisted += ".configlauncher" }

    @Test fun suppressionIsPerTree() {
        val a = hiddenTree(); val b = FakeTree(); val ka = key(); val kb = key()
        assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) }
        assertEquals(listOf(".configlauncher"), SafDirs(a, ka).suppressed())
        // Same doc ids and name in another tree: not suppressed, created normally.
        val id = SafDirs(b, kb).ensure("root", ".configlauncher", dir)
        assertEquals(".configlauncher", b.displayName(id))
        assertTrue(SafDirs(b, kb).suppressed().isEmpty())
        assertTrue(SafDirs(b, kb).takeNew().isEmpty())
    }

    @Test fun clearTreeRetriesOnlyThatTree() {
        val a = hiddenTree(); val b = hiddenTree(); val ka = key(); val kb = key()
        assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) }
        assertThrows(IOException::class.java) { SafDirs(b, kb).ensure("root", ".configlauncher", dir) }
        SafDirs.clearTree(ka)
        assertTrue(SafDirs(a, ka).suppressed().isEmpty())
        assertEquals(listOf(".configlauncher"), SafDirs(b, kb).suppressed())
        assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) }
        assertEquals(2, a.creates.get()) // tried again after the re-pick
        assertThrows(IOException::class.java) { SafDirs(b, kb).ensure("root", ".configlauncher", dir) }
        assertEquals(1, b.creates.get()) // still suppressed
    }

    @Test fun clearAllRetriesEveryTree() {
        val a = hiddenTree(); val ka = key()
        assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) }
        SafDirs.clearAll()
        assertTrue(SafDirs(a, ka).suppressed().isEmpty())
    }

    @Test fun takeNewReportsEachSuppressionOnce() {
        val a = hiddenTree(); val ka = key()
        repeat(5) { assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) } }
        assertEquals(listOf(".configlauncher"), SafDirs(a, ka).takeNew())
        assertTrue(SafDirs(a, ka).takeNew().isEmpty())
        SafDirs.clearTree(ka) // re-armed: a recurrence is reported again
        assertThrows(IOException::class.java) { SafDirs(a, ka).ensure("root", ".configlauncher", dir) }
        assertEquals(listOf(".configlauncher"), SafDirs(a, ka).takeNew())
    }
}
