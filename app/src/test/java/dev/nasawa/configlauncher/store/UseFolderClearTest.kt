package dev.nasawa.configlauncher.store

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * #251 (Grace R4): Reconnect ([ConfigStore.useFolder]) clears the picked tree's suppression synchronously, before the
 * AGENTS.md / schema syncs it launches, not only later inside [ConfigEngine.switchSource].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UseFolderClearTest {
    @Test fun useFolderClearsTheTreeBeforeAnyLaunchedWork() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://com.android.providers.downloads.documents/tree/msd%3A251")
        val t = SafDirsTest.FakeTree().apply { add("root", ".configlauncher", Document.MIME_TYPE_DIR); unlisted += ".configlauncher" }
        assertThrows(IOException::class.java) { SafDirs(t, uri.toString()).ensure("root", ".configlauncher", Document.MIME_TYPE_DIR) }
        assertEquals(listOf(".configlauncher"), SafDirs(t, uri.toString()).suppressed())
        // A scope whose coroutines never run: only what useFolder does synchronously counts.
        val store = ConfigStore(app, CoroutineScope(Job().apply { cancel() }))
        store.useFolder(uri)
        assertTrue(SafDirs(t, uri.toString()).suppressed().isEmpty())
    }
}
