package dev.nasawa.configlauncher.settings

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrefsTest {
    private val p get() = Prefs(ApplicationProvider.getApplicationContext())

    @Test fun firstRunUntilSkippedOrFolderPicked() {
        assertFalse(p.onboarded)
        p.onboarded = true
        assertTrue(p.onboarded)
    }

    @Test fun existingFolderUserCountsAsOnboarded() {
        p.folderUri = Uri.parse("content://x/tree/primary%3Acfg")
        assertTrue(p.onboarded)
    }
}
