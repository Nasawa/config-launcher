package dev.nasawa.configlauncher.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.FolderSkip
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** docs/ux.md §9.1 table, exact strings from strings.xml. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MergeCopyTest {
    private val copy = MergeCopy(ApplicationProvider.getApplicationContext<Application>().resources)
    private fun t(k: Int, s: Int, f: FolderSkip? = null) = copy.text(MergeNotice(k, s, f, undo = k > 0))

    @Test fun allKept() {
        assertEquals("Layout updated from launcher.json. Kept your change.", t(1, 0))
        assertEquals("Layout updated from launcher.json. Kept your 2 changes.", t(2, 0))
    }

    @Test fun mixedShortForm() {
        assertEquals("Layout updated from launcher.json. Kept 1 change, skipped 1.", t(1, 1))
        assertEquals("Layout updated from launcher.json. Kept 3 changes, skipped 1.", t(3, 1))
        assertEquals("Layout updated from launcher.json. Kept 1 change, skipped 3.", t(1, 3))
        val nn = t(2, 2)
        assertEquals("Layout updated from launcher.json. Kept 2 changes, skipped 2.", nn)
        assertEquals(61, nn.length)
    }

    @Test fun allSkippedPluralAgreement() {
        assertEquals("1 change skipped because the item was removed in the file.", t(0, 1))
        assertEquals("2 changes skipped because the items were removed in the file.", t(0, 2))
    }

    @Test fun folderOnlyWhenItIsTheOnlyChange() {
        assertEquals("Folder \"Work\" wasn't recreated because its other apps were removed or moved in the file. \"Chrome\" was placed on its own.",
            t(0, 1, FolderSkip("Work", "Chrome")))
        assertEquals("Folder \"Work\" wasn't recreated because its apps were removed or moved in the file.", t(0, 1, FolderSkip("Work", null)))
        // Not the only change: folded into the counts.
        assertEquals("Layout updated from launcher.json. Kept 1 change, skipped 1.", t(1, 1, FolderSkip("Work", null)))
        assertEquals("2 changes skipped because the items were removed in the file.", t(0, 2, FolderSkip("Work", null)))
    }

    @Test fun talkBackLongForm() {
        assertEquals("Layout updated from launcher.json. Kept 2 changes. Skipped 2 changes because their items were removed or moved in the file.",
            copy.spoken(MergeNotice(2, 2, null, true)))
        assertEquals("Layout updated from launcher.json. Kept 1 change. Skipped 1 change because its item was removed or moved in the file.",
            copy.spoken(MergeNotice(1, 1, null, true)))
        // All-kept and all-skipped announce the visible text.
        assertEquals(t(2, 0), copy.spoken(MergeNotice(2, 0, null, true)))
        assertEquals(t(0, 2), copy.spoken(MergeNotice(0, 2, null, false)))
    }

    @Test fun durationAndSilence() {
        assertEquals(6_000L, MergeCopy.durationMs(MergeNotice(1, 0, null, true)))
        assertEquals(4_000L, MergeCopy.durationMs(MergeNotice(0, 1, null, false)))
        assertTrue("Keep mine, all kept", MergeNotice(2, 0, null, undo = false).silent)
        assertFalse(MergeNotice(1, 1, null, undo = false).silent)
    }
}
