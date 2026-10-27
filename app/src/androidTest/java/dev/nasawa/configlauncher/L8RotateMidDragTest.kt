package dev.nasawa.configlauncher

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * test-plan §20 L8 (#197): start a drag, then rotate → drag cancelled, nothing written, item in old cell.
 *
 * Seeds via the debug app's run-as layout is the matrix driver's job; this smoke launches MainActivity,
 * long-presses the first home tile if present, rotates, and asserts the activity still resumes on our package.
 * Full hash/cell assert needs the matrix fixture on-device (CI matrix + JVM cancel contract + this smoke = L8).
 */
@RunWith(AndroidJUnit4::class)
class L8RotateMidDragTest {
    @Test fun rotateDuringInteractionDoesNotCrashAndLeavesHome() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "MainActivity did not come to foreground",
                device.wait(Until.hasObject(By.pkg("dev.nasawa.configlauncher")), 8_000),
            )
            // Best-effort long-press on a labelled tile (seeded matrix builds show "QA Settings").
            val tile = device.findObject(By.text("QA Settings"))
                ?: device.findObject(By.descContains("Settings"))
            if (tile != null) {
                val r = tile.visibleBounds
                device.swipe(r.centerX(), r.centerY(), r.centerX() + 80, r.centerY() + 40, 100)
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            device.waitForIdle(2_000)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            device.waitForIdle(2_000)
            val pkg = device.currentPackageName.orEmpty()
            assertTrue(
                "expected still in configlauncher after rotate mid-drag, got $pkg",
                pkg.contains("configlauncher"),
            )
        }
    }
}
