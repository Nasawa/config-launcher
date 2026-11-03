package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.store.Issue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File

/**
 * #217 (Pragma's spec, Archimedes' ruling): ⋮ → `Show config location` hides or shows the top row's source note.
 * Default on; stored in app settings, never in launcher.json; the note comes back while the source has an issue;
 * the row, Apps and ⋮ don't move; the item is a checkbox for TalkBack; the setting survives a recreate.
 * The fixture's title bar shows only {page}: no clock slots.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class ShowSourceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)

    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
    private val file get() = File(app.filesDir, "config/source/launcher.json")
    private val note get() = app.getString(R.string.source_app_storage)
    private val item get() = app.getString(R.string.menu_show_source)

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().clear().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    /** The note's nodes in the semantics tree (unmerged, so a drawn-but-merged Text still counts). */
    private fun noteShown(): Boolean {
        compose.waitForIdle()
        return compose.onAllNodes(hasText(note), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun bounds(text: String): Rect {
        compose.waitForIdle()
        return compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot
    }

    /** Opens ⋮ unless it's already open (a checkbox item leaves the menu open, like Label backdrop). */
    private fun openMenu() {
        compose.waitForIdle()
        if (compose.onAllNodes(hasText(item)).fetchSemanticsNodes().isEmpty()) compose.onAllNodes(hasText("⋮")).onFirst().performClick()
        compose.waitForIdle()
    }
    private fun toggle() { openMenu(); compose.onNode(hasText(item)).performClick(); compose.waitForIdle() }

    private fun checkbox(state: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state) and hasText(item)

    @Test fun defaultShowsTheNote() {
        assertTrue("default pref is on", app.store.prefs.showSource)
        assertTrue("the note shows by default", noteShown())
        openMenu()
        compose.onNode(checkbox(ToggleableState.On)).assertExists()
    }

    @Test fun offHidesTheNoteKeepsTheRowAndOnBringsItBack() {
        val apps = bounds(app.getString(R.string.apps)); val dots = bounds("⋮")
        toggle()
        assertFalse("off: no note in the tree", noteShown())
        assertEquals("Apps doesn't move", apps, bounds(app.getString(R.string.apps)))
        assertEquals("⋮ doesn't move", dots, bounds("⋮"))
        openMenu()
        compose.onNode(checkbox(ToggleableState.Off)).assertExists()
        toggle()
        assertTrue("on again: the note is back", noteShown())
    }

    @Test fun toggleWritesNothingToTheLauncherConfig() {
        val bytes = file.readBytes(); val mtime = file.lastModified()
        val before = app.store.engine.state.value.config
        toggle(); toggle(); toggle()
        assertFalse(app.getSharedPreferences("settings", 0).getBoolean("showSource", true))
        assertArrayEquals("launcher.json untouched", bytes, file.readBytes())
        assertEquals(mtime, file.lastModified())
        val after = app.store.engine.state.value.config
        assertEquals(before.revision, after.revision)
        assertEquals(before, after)
    }

    @Test fun settingSurvivesRecreate() {
        toggle()
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertFalse("still off after a recreate", noteShown())
        openMenu()
        compose.onNode(checkbox(ToggleableState.Off)).assertExists()
    }

    @Test fun offButALoadErrorShowsTheNoteUntilItClears() {
        toggle()
        assertFalse(noteShown())
        val good = file.readText()
        file.writeText("{ not json")
        kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
        assertTrue("a load error is up", app.store.engine.state.value.issue is Issue.ParseError)
        assertTrue("the note comes back while the source needs attention", noteShown())
        file.writeText(good)
        kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
        assertEquals(null, app.store.engine.state.value.issue)
        assertFalse("cleared: the toggle applies again", noteShown())
    }
}
