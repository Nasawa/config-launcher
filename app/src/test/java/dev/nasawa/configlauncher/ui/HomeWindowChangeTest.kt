package dev.nasawa.configlauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #214, the whole app (MainActivity) through a window change, LTR and RTL:
 *  (1)-(3) a rotate or a short multi-window pane that flips the rail keeps the page by id (it went to Home);
 *  (4) a frame laid out at zero width doesn't move the pager to its last page (RTL resize → Third);
 *  (5) a window change mid-drag (pointer cancel, then recreation) writes nothing.
 * The fixture's title bar shows only {page}: no clock slots.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeWindowChangeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)

    private val app get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()

    /** Seeds an onboarded app on the three-page fixture before the activity starts. */
    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    /** The page on screen, from the title bar's "<Title>, Pages" button. */
    private fun page(): String {
        compose.waitForIdle()
        val n = compose.onAllNodes(hasContentDescription(", Pages", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        val d = n.firstNotNullOf { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.firstOrNull() }
        return d.substringBefore(", Pages")
    }

    private fun goto(title: String) {
        compose.onNode(hasContentDescription(", Pages", substring = true)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText(title) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertEquals(title, page())
    }

    private fun recreate(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    /** Lays the window out at [w] x [h] px, as the old window is during a resize or density change. */
    private fun relayout(w: Int, h: Int) {
        compose.activityRule.scenario.onActivity { a ->
            val v = a.window.decorView
            v.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, w, h)
        }
        compose.waitForIdle()
    }

    private fun rotateKeepsPage(land: String, port: String) {
        goto("Second")
        recreate(port) // rail → bottom dock
        assertEquals("rotate to portrait keeps the page by id", "Second", page())
        recreate(land) // bottom dock → rail
        assertEquals("rotate back keeps the page by id", "Second", page())
    }

    // (1) / (3): useRail flips on rotate.
    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun rotateKeepsPageLtr() = rotateKeepsPage("w960dp-h600dp-land-xhdpi", "w600dp-h960dp-port-xhdpi")

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi")
    fun rotateKeepsPageRtl() = rotateKeepsPage("ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi", "ar-rEG-ldrtl-w600dp-h960dp-port-xhdpi")

    // (2): a short multi-window pane (40 % of the height) turns the rail on.
    private fun shortPaneKeepsPage(tall: String, short: String) {
        goto("Second")
        recreate(short)
        assertEquals("short pane keeps the page by id", "Second", page())
        recreate(tall)
        assertEquals("back to full height keeps the page by id", "Second", page())
    }

    @Test @Config(qualifiers = "w411dp-h914dp-port-xxhdpi")
    fun shortPaneKeepsPageLtr() = shortPaneKeepsPage("w411dp-h914dp-port-xxhdpi", "w411dp-h365dp-land-xxhdpi")

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h914dp-port-xxhdpi")
    fun shortPaneKeepsPageRtl() = shortPaneKeepsPage("ar-rEG-ldrtl-w411dp-h914dp-port-xxhdpi", "ar-rEG-ldrtl-w411dp-h365dp-land-xxhdpi")

    // (4): resize / density change with a zero-width frame, then the recreate that follows.
    private fun zeroWidthFrameKeepsPage(w: Int, h: Int, after: String) {
        goto("Second")
        relayout(0, h)
        relayout(w, h)
        assertEquals("a zero-width frame doesn't move the pager", "Second", page())
        recreate(after)
        assertEquals("and the recreate after it keeps the page", "Second", page())
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun zeroWidthFrameKeepsPageLtr() = zeroWidthFrameKeepsPage(1920, 1200, "w960dp-h300dp-land-xhdpi")

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi")
    fun zeroWidthFrameKeepsPageRtl() = zeroWidthFrameKeepsPage(1920, 1200, "ar-rEG-ldrtl-w960dp-h300dp-land-xhdpi")

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h914dp-port-xxhdpi")
    fun zeroWidthFrameKeepsPageRtlPhone() = zeroWidthFrameKeepsPage(1080, 2400, "ar-rEG-ldrtl-w411dp-h914dp-port-xxhdpi")

    // (5): rotate mid-drag. The window change cancels the pointer, then the activity is recreated.
    private fun rotateMidDragWritesNothing(port: String) {
        goto("Second")
        val before = app.store.engine.state.value.config
        compose.onAllNodes(hasText("Second tile")).onFirst().performTouchInput {
            down(center); advanceEventTime(1_000)
            moveBy(Offset(width.toFloat(), height.toFloat())); moveBy(Offset(width / 2f, height / 2f))
        }
        compose.waitForIdle()
        compose.onAllNodes(hasText("Second tile")).onFirst().performTouchInput { cancel() }
        recreate(port)
        val after = app.store.engine.state.value.config
        assertEquals("an interrupted drag writes nothing", before.revision, after.revision)
        assertEquals(before.pages, after.pages)
        assertFalse("the engine's drag hold is released", app.store.engine.dragging)
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun rotateMidDragWritesNothingLtr() = rotateMidDragWritesNothing("w600dp-h960dp-port-xhdpi")

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w960dp-h600dp-land-xhdpi")
    fun rotateMidDragWritesNothingRtl() = rotateMidDragWritesNothing("ar-rEG-ldrtl-w600dp-h960dp-port-xhdpi")
}
