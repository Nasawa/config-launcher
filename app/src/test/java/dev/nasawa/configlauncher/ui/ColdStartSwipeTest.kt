package dev.nasawa.configlauncher.ui

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.Origin
import kotlinx.coroutines.sync.Mutex
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
 * #191, the whole app: the first swipe-up after a cold start opens the drawer. The app starts with the file not
 * yet loaded: the engine is held, so its first poll waits and the built-in empty home is up (no last-good copy), as
 * it is while a cold start loads. Its first touch arrives the way it does on a busy cold start: the down handled late, then the
 * lift 600 ms later in event time, far up the screen, with no moves between (API 29 `input swipe` waits for the
 * down to be handled). Event times only; no clock is advanced.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-xxhdpi")
class ColdStartSwipeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed).around(compose)

    /** Onboarded (app storage) with the three-page fixture on disk, but not polled, and the engine held. */
    private object Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().putBoolean("appOnly", true).putBoolean("homePromptShown", true)
                    .putBoolean("drawerKeyboard", false).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                check(loading.tryLock(Seed)) { "engine busy before the activity started" }
                try { base.evaluate() } finally { if (loading.holdsLock(Seed)) loading.unlock(Seed) }
            }
        }
    }

    private companion object {
        /** The engine's lock: while the test holds it, the activity's first poll (the cold-start load) waits. */
        val loading: Mutex get() = ConfigEngine::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            .get(ApplicationProvider.getApplicationContext<ConfigLauncherApp>().store.engine) as Mutex
    }

    private val engine get() = ApplicationProvider.getApplicationContext<ConfigLauncherApp>().store.engine

    private fun drawerOpen() = compose.onAllNodes(hasText("Search apps"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun motion(action: Int, downAt: Long, at: Long, x: Float, y: Float) =
        MotionEvent.obtain(downAt, at, action, x, y, 0).also { it.source = InputDevice.SOURCE_TOUCHSCREEN }

    @Test fun firstSwipeAfterAColdStartOpensTheDrawer() {
        assertFalse(drawerOpen())
        assertEquals("still loading: the built-in home is up", Origin.DEFAULT, engine.state.value.origin)
        compose.activityRule.scenario.onActivity { a ->
            val v = a.window.decorView
            // Empty grid space in both the built-in home and the fixture's home page (rows 2-4 are empty).
            val x = v.width / 2f; val from = v.height * 0.62f; val to = v.height * 0.25f
            val t0 = android.os.SystemClock.uptimeMillis()
            listOf(
                motion(MotionEvent.ACTION_DOWN, t0, t0, x, from),
                motion(MotionEvent.ACTION_UP, t0, t0 + 600, x, to),
            ).forEach { e -> v.dispatchTouchEvent(e); e.recycle() }
        }
        compose.waitForIdle()
        assertTrue("the first swipe-up opens the drawer", drawerOpen())
        assertEquals("the swipe came before the load", Origin.DEFAULT, engine.state.value.origin)
        // The load then finishes under the open drawer.
        loading.unlock(Seed)
        kotlinx.coroutines.runBlocking { engine.poll(force = true) }
        compose.waitForIdle()
        assertEquals(Origin.FILE, engine.state.value.origin)
        assertTrue("the drawer stays open through the load", drawerOpen())
    }
}
