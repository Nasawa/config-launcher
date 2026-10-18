package dev.nasawa.configlauncher.store

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Store fault injection (docs/test-plan.md §5 FI-*, §6 CM-12/13, §4 SW-*), using an in-memory
 * ConfigSource whose faults can be switched on per call.
 */
class ConfigEngineFaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSource : ConfigSource {
        var content: ByteArray? = null
        var mtime = 0L
        var docId = "doc1"
        var stampFails: Exception? = null
        var readFails: Exception? = null
        var writeFails: Exception? = null
        var writes = 0
        /** Called after each stamp(); lets a test change the file between the read and the stability re-check. */
        var onStamp: (Int) -> Unit = {}
        private var stamps = 0
        override val displayName = "fake"
        override fun stamp(): Stamp? {
            stampFails?.let { throw it }
            val s = content?.let { Stamp(docId, mtime, it.size.toLong()) }
            onStamp(++stamps)
            return s
        }
        override fun read(maxBytes: Int): ByteArray? {
            readFails?.let { throw it }
            val c = content ?: return null
            if (c.size > maxBytes) throw IOException("larger than cap")
            return c
        }
        override fun write(bytes: ByteArray) {
            writeFails?.let { throw it }
            writes++; content = bytes; mtime++
        }
        val sidecars = mutableMapOf<String, ByteArray>()
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) { sidecars["$subdir/$name"] = bytes }
        fun put(s: String) { content = s.toByteArray(); mtime++ }
    }

    private fun cfg(rev: Int?, pkg: String = "a.b") =
        """{"schemaVersion":1${if (rev != null) ",\"revision\":$rev" else ""},"pages":[{"id":"home","items":[{"id":"a","type":"app","package":"$pkg","x":0,"y":0}]}]}"""

    private fun engine(src: FakeSource, stabilityMs: Long = 0) =
        ConfigEngine(src, LastGoodStore(tmp.newFolder()), stabilityMs)

    @Test fun providerUnavailableKeepsModelAndRecovers() = runBlocking {   // FI-8
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src); e.poll()
        src.put(cfg(2, "c.d")); src.stampFails = IOException("provider died")
        assertFalse(e.poll())
        assertEquals(1L, e.state.value.config.revision)
        assertTrue(e.state.value.errors.isNotEmpty())
        src.stampFails = null
        assertTrue(e.poll())
        assertEquals(2L, e.state.value.config.revision)
        assertTrue(e.state.value.errors.isEmpty())
    }

    @Test fun permissionRevokedOnReadKeepsModel() = runBlocking {   // FI-7
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src); e.poll()
        src.put(cfg(2)); src.readFails = SecurityException("grant revoked")
        assertFalse(e.poll())
        assertEquals(1L, e.state.value.config.revision)
        assertTrue(e.state.value.errors.isNotEmpty())
    }

    @Test fun oversizeFileRejectedWithoutSwap() = runBlocking {   // HC-5
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src); e.poll()
        src.put(cfg(2).replace("}]}]}", "}],\"x-pad\":\"${"a".repeat(2 * 1024 * 1024 + 1)}\"}]}"))
        assertFalse(e.poll())
        assertEquals(1L, e.state.value.config.revision)
    }

    @Test fun tornReadDuringStabilityWindowIsNotApplied() = runBlocking {   // FI-12, CM-13
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src, stabilityMs = 20); e.poll()
        val partial = cfg(2).substring(0, 40)
        src.put(partial)
        // The writer finishes during the 300 ms (here 20 ms) stability wait.
        src.onStamp = { n -> if (n == 3) src.put(cfg(3)) }
        assertFalse(e.poll())
        assertEquals(1L, e.state.value.config.revision)
        assertTrue("torn read must not surface errors", e.state.value.errors.isEmpty())
        src.onStamp = {}
        assertTrue(e.poll())
        assertEquals(3L, e.state.value.config.revision)
    }

    @Test fun lowerOrMissingRevisionStillAccepted() = runBlocking {   // CM-12, A§15 OQ-19/20
        val src = FakeSource().apply { put(cfg(5)) }
        val e = engine(src); e.poll()
        src.put(cfg(2, "c.d")); assertTrue(e.poll())
        src.put(cfg(null, "e.f")); assertTrue(e.poll())
        assertNull(e.state.value.config.revision)
    }

    @Test fun mtimeOnlyTouchIsNoOp() = runBlocking {   // SW-5
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src); e.poll()
        src.mtime += 10
        assertFalse(e.poll())
        assertEquals(0, src.writes)
    }

    @Test fun docIdChangeWithSameContentIsNoOp() = runBlocking {   // SW-6
        val src = FakeSource().apply { put(cfg(1)) }
        val e = engine(src); e.poll()
        src.docId = "doc2"
        assertFalse(e.poll())
    }

    @Test fun reloadNeverWrites() = runBlocking {   // A§15 OQ-17: reloads never trigger a write
        val src = FakeSource().apply { put(cfg(1).replace(",", ",\n  ")) }
        val e = engine(src); assertTrue(e.poll())
        assertEquals(0, src.writes)
    }

    /**
     * FI-11 / A§4 / UX B4: when launcher.json is missing the launcher offers "Create"/"Recreate";
     * it must not write on its own (a sync client or git may be mid-replace, and auto-creating would
     * clobber the incoming file). Currently fails: ConfigEngine.pollLocked auto-creates.
     */
    @Test fun missingFileIsNotAutoCreated() = runBlocking {
        val src = FakeSource()
        val e = engine(src)
        e.poll()
        assertEquals("launcher must not create launcher.json without the user tapping Create", 0, src.writes)
    }

    /** FI-9: a failed create (disk full) leaves the model and reports, no throw. */
    @Test fun diskFullOnCreateReportsError() = runBlocking {
        val src = FakeSource().apply { writeFails = IOException("ENOSPC") }
        val e = engine(src)
        val before = e.state.value.config
        e.poll()
        assertFalse(e.createFromCurrentLayout()) // creation is explicit (B4); a failed create reports B5
        assertEquals(before, e.state.value.config)
        assertTrue(e.state.value.issue is Issue.SaveFailed)
    }
}
