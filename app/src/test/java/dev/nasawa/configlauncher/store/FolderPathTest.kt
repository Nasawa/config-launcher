package dev.nasawa.configlauncher.store

import org.junit.Assert.*
import org.junit.Test

/** #57: readable config folder path. */
class FolderPathTest {
    private val ext = FolderPath.EXTERNAL_STORAGE

    @Test fun localStorage() {
        assertEquals("Internal storage/Launcher", FolderPath.of(ext, "primary:Launcher"))
        assertEquals("Internal storage/Sync/phone/Launcher", FolderPath.of(ext, "primary:Sync/phone/Launcher"))
        assertEquals("Internal storage", FolderPath.of(ext, "primary:"))
        assertEquals("SD card (1A2B-3C4D)/cfg", FolderPath.of(ext, "1A2B-3C4D:cfg"))
        assertEquals("Internal storage/Documents/x", FolderPath.of(ext, "home:x"))
    }

    @Test fun cloudFallsBackToUri() {
        assertNull(FolderPath.of("com.google.android.apps.docs.storage", "acc=1;doc=abc"))
        val uri = "content://com.google.android.apps.docs.storage/tree/acc%3D1"
        assertEquals(uri, FolderPath.launcherJson("com.google.android.apps.docs.storage", "acc=1;doc=abc", uri))
        assertEquals("Internal storage/Launcher/launcher.json", FolderPath.launcherJson(ext, "primary:Launcher", "content://x"))
    }

    @Test fun statusJsonCarriesFolder() {
        val s = String(StatusJson.encode("ok", emptyList(), "h", 0, folder = "Internal storage/Launcher/launcher.json"))
        assertTrue(s.contains("\"folder\":\"Internal storage/Launcher/launcher.json\""))
        assertTrue(String(StatusJson.encode("ok", emptyList(), "h", 0)).contains("\"folder\":null"))
    }
}
