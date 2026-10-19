package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ARCHITECTURE.md §20.2 (#88). */
class SchemaCopyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val schema = (File("../schema/launcher-config.v1.schema.json").takeIf { it.isFile } ?: File("schema/launcher-config.v1.schema.json")).readBytes()
    private fun copy(f: LocalFileSource) = File(f.dir, ".configlauncher/${SchemaCopy.NAME}")

    @Test fun writesIntoConfiglauncherOnceThenOnlyWhenSchemaChanges() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = true)
        val sha = SchemaCopy.sync(f, schema, null)
        assertEquals(ConfigCodec.sha256(schema), sha)
        assertArrayEquals(schema, copy(f).readBytes())
        assertNull("same schema: no write", SchemaCopy.sync(f, schema, sha))
        val newer = schema + "\n".toByteArray()
        assertNotNull("app update with a new schema rewrites it", SchemaCopy.sync(f, newer, sha))
        assertArrayEquals(newer, copy(f).readBytes())
        assertFalse(File(f.dir, SchemaCopy.NAME).exists())   // never next to launcher.json
    }

    @Test fun appOnlyModeWritesNothingAndRetriesLater() {
        val f = LocalFileSource(tmp.newFolder(), sidecars = false)
        assertNull(SchemaCopy.sync(f, schema, null))
        assertFalse(copy(f).exists())
    }
}
