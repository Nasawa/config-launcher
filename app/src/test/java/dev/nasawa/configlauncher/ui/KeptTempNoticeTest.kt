package dev.nasawa.configlauncher.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.ConfigSource
import dev.nasawa.configlauncher.store.Issue
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * #262 (Grace, #251 GO at 5e2d541): a save temp the start sweep kept as the only copy of an edit was named only in
 * the Warnings list and status.json. The list is hidden while a problem banner is up, and status.json can't be
 * written on msd:12, so the banner now names it, exactly while a temp is kept.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KeptTempNoticeTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val restoreFailed = "Couldn't finish saving launcher.json after the app was stopped: the new version is kept in .launcher.json.696580f1.tmp. Copy it over launcher.json to keep your last edit."
    private val valid = """{"schemaVersion":1,"pages":[{"id":"home","items":[]}]}"""

    /** msd:12: the sweep's kept-temp lines, and a `.configlauncher` that can't be written (no status.json). */
    private class Msd(val inner: LocalFileSource, var kept: List<String>) : ConfigSource by inner {
        override fun tempWarnings() = kept
        override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) = throw IOException("hidden by the provider")
    }

    @Test fun anEmptiedLauncherJsonShowsTheKeptTempInTheBanner() = runBlocking {
        val dir = tmp.newFolder(); val src = LocalFileSource(dir)
        src.file.writeText("") // the kill truncated it and the restore failed
        val msd = Msd(src, listOf(restoreFailed))
        val e = ConfigEngine(msd, LastGoodStore(tmp.newFolder()), 0)
        e.poll(force = true)
        val issue = e.state.value.issue
        assertNotNull("the problem banner is up", issue)
        assertEquals(listOf(restoreFailed), e.keptTemps.value)
        assertEquals(listOf(restoreFailed), BannerNotes.keptTemps(issue, e.keptTemps.value))
        assertFalse("status.json couldn't be written", java.io.File(dir, ".configlauncher/status.json").exists())

        // Fixed, and the next start's sweep deleted the temp: the line goes.
        src.file.writeText(valid); msd.kept = emptyList()
        e.poll(force = true)
        assertNull(e.state.value.issue)
        assertEquals(emptyList<String>(), e.keptTemps.value)
    }

    @Test fun withNoBannerTheLineIsInTheWarningsListInstead() = runBlocking {
        // An AGENTS.md temp kept while launcher.json is fine: no banner, so the Warnings list (shown then) names it.
        val dir = tmp.newFolder(); val src = LocalFileSource(dir); src.file.writeText(valid)
        val agents = "Couldn't finish saving AGENTS.md after the app was stopped: the new version is kept in .AGENTS.md.3d4068d8.tmp. Copy it over AGENTS.md to keep your last edit."
        val e = ConfigEngine(Msd(src, listOf(agents)), LastGoodStore(tmp.newFolder()), 0)
        e.poll(force = true)
        assertNull(e.state.value.issue)
        assertTrue(e.warnings.value.toString(), agents in e.warnings.value)
        assertEquals("not shown twice", emptyList<String>(), BannerNotes.keptTemps(null, e.keptTemps.value))
    }

    @Test fun nothingKeptNothingShown() {
        assertEquals(emptyList<String>(), BannerNotes.keptTemps(Issue.ReadFailed("x"), emptyList()))
    }

    @Test fun theBannerShowsTheKeptTempWithoutOpeningTheDetails() {
        val issue = Issue.ParseError(listOf("unexpected end of input", "second problem"))
        rule.setContent {
            IssueBanner(issue, reconnectable = true, kept = listOf(restoreFailed), locate = { l -> l.map { null } },
                onReconnect = {}, onCreate = {}, onUseAppStorage = {}, onRetry = {})
        }
        rule.onNodeWithText("Can't read launcher.json. Showing the last working layout.").assertIsDisplayed()
        rule.onNodeWithText("▲ $restoreFailed").assertIsDisplayed()
        // Only the first detail line while folded; the kept-temp line isn't one of them.
        assertEquals(0, rule.onAllNodesWithText("• second problem").fetchSemanticsNodes().size)
    }

    @Test fun noKeptTempNoExtraLine() {
        rule.setContent {
            IssueBanner(Issue.ReadFailed("gone"), reconnectable = true, locate = { l -> l.map { null } },
                onReconnect = {}, onCreate = {}, onUseAppStorage = {}, onRetry = {})
        }
        assertEquals(0, rule.onAllNodesWithText("▲", substring = true).fetchSemanticsNodes().size)
    }
}
