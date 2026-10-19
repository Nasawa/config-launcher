package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import dev.nasawa.configlauncher.store.SchemaCopy.Outcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ARCHITECTURE.md §20.2 (#88). */
class SchemaCopyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val schema = (File("../schema/launcher-config.v1.schema.json").takeIf { it.isFile } ?: File("schema/launcher-config.v1.schema.json")).readBytes()
    private fun folder() = LocalFileSource(tmp.newFolder(), sidecars = true)
    private fun copy(f: LocalFileSource) = File(f.dir, ".configlauncher/${SchemaCopy.NAME}")
    private fun files(f: LocalFileSource) = f.dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(f.dir).path }.toSet()

    @Test fun currentSchemaHashIsListedAsShipped() {
        assertTrue("schema changed: add its SHA-256 to SchemaCopy.SHIPPED_SHA256", ConfigCodec.sha256(schema) in SchemaCopy.SHIPPED_SHA256)
    }

    @Test fun writesOnlyItsOwnFileOnce() {
        val f = folder()
        File(f.dir, "launcher.json").writeText("{}")
        File(f.dir, ".configlauncher").mkdirs(); File(f.dir, ".configlauncher/status.json").writeText("s")
        assertEquals(Outcome.WROTE, SchemaCopy.sync(f, schema))
        assertArrayEquals(schema, copy(f).readBytes())
        assertEquals(setOf("launcher.json", ".configlauncher/status.json", ".configlauncher/${SchemaCopy.NAME}"), files(f))
        assertEquals("{}", File(f.dir, "launcher.json").readText())
        assertEquals(Outcome.UP_TO_DATE, SchemaCopy.sync(f, schema))
    }

    @Test fun appUpdateReplacesAShippedVersion() {
        val f = folder()
        SchemaCopy.sync(f, schema)
        val newer = schema + "\n".toByteArray()
        assertEquals(Outcome.UPDATED, SchemaCopy.sync(f, newer))
        assertArrayEquals(newer, copy(f).readBytes())
    }

    @Test fun neverOverwritesAUserEditedCopy() {
        val f = folder()
        copy(f).parentFile!!.mkdirs(); copy(f).writeText("{\"mine\": true}")
        assertEquals(Outcome.USER_CHANGED, SchemaCopy.sync(f, schema))
        assertEquals("{\"mine\": true}", copy(f).readText())
    }

    @Test fun appOnlyModeWritesNothing() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = false)
        assertEquals(Outcome.FAILED, SchemaCopy.sync(f, schema))
        assertTrue(files(f).isEmpty())
    }
}
