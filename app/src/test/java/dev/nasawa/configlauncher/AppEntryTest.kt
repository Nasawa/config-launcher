package dev.nasawa.configlauncher

import dev.nasawa.configlauncher.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryTest {
    @Test fun keyIsPackageSlashActivity() {
        assertEquals("a.b/a.b.Main", AppEntry("a.b", "a.b.Main", "B").key)
    }
}
