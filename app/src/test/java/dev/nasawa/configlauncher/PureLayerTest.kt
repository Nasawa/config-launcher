package dev.nasawa.configlauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** ARCHITECTURE.md §10: model and config must stay Android-free. */
class PureLayerTest {
    @Test fun noAndroidImportsInModelOrConfig() {
        val src = File(System.getProperty("repoRoot"), "app/src/main/java/dev/nasawa/configlauncher")
        val bad = listOf("model", "config").flatMap { File(src, it).walk().filter { f -> f.extension == "kt" }.toList() }
            .filter { f -> f.readLines().any { it.startsWith("import android") || it.startsWith("import androidx") } }
            .map { it.name }
        assertEquals(emptyList<String>(), bad)
    }
}
