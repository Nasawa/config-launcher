package dev.nasawa.configlauncher.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** docs/ux.md §10: user-facing text lives in strings.xml; exact copy is unchanged by the move. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserStringsTest {
    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test fun noticesResolveToTheSameCopy() {
        assertEquals("File changed again, Undo cancelled.", Msg(R.string.undo_cancelled).resolve(res))
        assertEquals("Couldn't update the backup copy (phone storage full).", Msg(R.string.backup_failed).resolve(res))
        assertEquals("Couldn't back up the folder's launcher.json: read-only", Msg(R.string.folder_backup_failed, "read-only").resolve(res))
        assertEquals("Couldn't read that folder: gone", Msg(R.string.folder_read_failed, "gone").resolve(res))
        assertEquals("launcher.json in that folder has problems, so it wasn't used.", Msg(R.string.folder_invalid).resolve(res))
        assertEquals("Couldn't move your layout: x", Msg(R.string.move_failed, "x").resolve(res))
        assertEquals("Your broken launcher.json was saved to .configlauncher/broken/.", Msg(R.string.broken_saved_folder).resolve(res))
        assertEquals("Turn on \"Include work apps in config\" to use this.", res.getString(R.string.work_disabled))
        assertEquals("No apps match \"cal\".", res.getString(R.string.search_no_match, "cal"))
    }

    @Test fun bannersAndPlurals() {
        assertEquals("launcher.json has 1 problem. Showing the last working layout.", res.getQuantityString(R.plurals.b2_invalid, 1, 1))
        assertEquals("launcher.json has 3 problems. Showing the last working layout.", res.getQuantityString(R.plurals.b2_invalid, 3, 3))
        assertEquals("Line 12, column 7: bad", res.getString(R.string.error_at, 12, 7, "bad"))   // §10 wording
        assertEquals("launcher.json changed while you were editing (1 change).", res.getQuantityString(R.plurals.b6_conflict, 1, 1))
        assertEquals("It has 1 page and 2 items. If you replace it, the folder's file is first copied to .configlauncher/backup/.",
            res.getString(R.string.move_valid, res.getQuantityString(R.plurals.move_pages, 1, 1), res.getQuantityString(R.plurals.move_items, 2, 2)))
    }

    @Test fun b5TitlesIncludingWriterRefusal() {   // HC-8b exact copy
        assertEquals("Couldn't save your change.", Issue.SaveFailed.of(IOException("disk")).title.resolve(res))
        assertEquals("Couldn't save your change: launcher.json would be over 2 MB.", Issue.SaveFailed.of(TooLargeException(2)).title.resolve(res))
        assertEquals("Couldn't undo.", Issue.SaveFailed.of(IOException("x"), R.string.b5_undo_failed).title.resolve(res))
        assertEquals("disk", Issue.SaveFailed.of(IOException("disk")).details.single())
    }

    /** Guard: no English literals passed to Text()/toast() in UI code. */
    @Test fun noHardCodedUiText() {
        val dir = listOf("src/main/java/dev/nasawa/configlauncher/ui", "app/src/main/java/dev/nasawa/configlauncher/ui").map(::File).first { it.isDirectory }
        val bad = Regex("""\b(Text|toast)\(\s*"[^"]*[A-Za-z]{2}""")
        val hits = dir.listFiles()!!.filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l.replace(Regex("""\$\{[^}]*\}|\$\w+"""), ""))) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
