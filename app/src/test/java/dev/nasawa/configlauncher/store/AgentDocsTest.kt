package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.store.AgentDocs.Outcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ARCHITECTURE.md §20.1 (#87). */
class AgentDocsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val template = (File("../templates/config-folder/AGENTS.md").takeIf { it.isFile } ?: File("templates/config-folder/AGENTS.md")).readBytes()
    private val old = "old shipped".toByteArray()
    private fun folder() = LocalFileSource(tmp.newFolder(), sidecars = true)
    private fun LocalFileSource.agents() = File(dir, AgentDocs.NAME).takeIf { it.isFile }?.readBytes()

    @Test fun currentTemplateHashIsListedAsShipped() {
        assertTrue("templates/config-folder/AGENTS.md changed: add its SHA-256 to AgentDocs.SHIPPED_SHA256",
            ConfigCodec.sha256(template) in AgentDocs.SHIPPED_SHA256)
    }

    @Test fun writesOnceIntoEmptyFolder() {
        val f = folder()
        assertEquals(Outcome.WROTE, AgentDocs.sync(f, template, writtenBefore = false, enabled = true))
        assertArrayEquals(template, f.agents())
        assertEquals(Outcome.UP_TO_DATE, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertFalse(File(f.dir, "AGENTS.md.tmp").exists())
    }

    @Test fun neverOverwritesUserEdits() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("my own rules")
        assertEquals(Outcome.USER_CHANGED, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertEquals("my own rules", File(f.dir, "AGENTS.md").readText())
        assertTrue(AgentDocs.remember(Outcome.USER_CHANGED))
    }

    @Test fun updatesAnUntouchedShippedVersion() {
        val shippedOld = AgentDocs.SHIPPED_SHA256.first()
        // A file with a shipped hash but different bytes than the bundle is updated.
        assertEquals(Outcome.UPDATED, AgentDocs.decide("new".toByteArray(), template, true, true, false).also { assertEquals(ConfigCodec.sha256(template), shippedOld) })
        assertEquals(Outcome.USER_CHANGED, AgentDocs.decide(template, old, true, true, false))
    }

    @Test fun deletedFileIsNotRecreatedButAddAgainRestoresIt() {
        val f = folder()
        assertEquals(Outcome.USER_DELETED, AgentDocs.sync(f, template, writtenBefore = true, enabled = true))
        assertNull(f.agents())
        assertEquals(Outcome.WROTE, AgentDocs.sync(f, template, writtenBefore = true, enabled = true, force = true))
        assertArrayEquals(template, f.agents())
    }

    @Test fun addAgainOverwritesEvenUserEdits() {
        val f = folder()
        File(f.dir, "AGENTS.md").writeText("mine")
        assertEquals(Outcome.UPDATED, AgentDocs.sync(f, template, writtenBefore = true, enabled = false, force = true))
        assertArrayEquals(template, f.agents())
    }

    @Test fun settingOffWritesNothing() {
        val f = folder()
        assertEquals(Outcome.OFF, AgentDocs.sync(f, template, writtenBefore = false, enabled = false))
        assertNull(f.agents())
    }

    @Test fun appOnlyModeHasNoFolder() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = false)
        assertEquals(Outcome.FAILED, AgentDocs.sync(f, template, writtenBefore = false, enabled = true))
        assertNull(f.agents())
    }
}
