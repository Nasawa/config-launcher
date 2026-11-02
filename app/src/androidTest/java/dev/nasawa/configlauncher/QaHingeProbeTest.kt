package dev.nasawa.configlauncher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.WindowSdkExtensions
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA probe for the screen-matrix hinge shard (PR #219 review; not a product test, never asserts).
 * Reads what WindowInfoTracker really reports for MainActivity on the current emulator posture and prints one
 * `QA-HINGE` line per fact (logcat + instrumentation status) so the driver can record it as hinge.txt.
 * Run: am instrument -w -r -e class dev.nasawa.configlauncher.QaHingeProbeTest <test pkg>/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class QaHingeProbeTest {
    @Test fun dumpWindowLayoutInfo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val lines = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var act: Activity? = null
            sc.onActivity { act = it }
            val a = act ?: error("no activity")
            lines += "extension_version=" + runCatching { WindowSdkExtensions.getInstance().extensionVersion }.getOrElse { "error:${it.javaClass.simpleName}" }
            val info = runBlocking {
                withTimeoutOrNull(15_000) { WindowInfoTracker.getOrCreate(a).windowLayoutInfo(a).first() }
            }
            val wb = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(a).bounds
            val cfg = a.resources.configuration
            lines += "window=${wb.left},${wb.top},${wb.right},${wb.bottom}"
            lines += "layout_dir=${cfg.layoutDirection} screen_dp=${cfg.screenWidthDp}x${cfg.screenHeightDp} orientation=${cfg.orientation}"
            if (info == null) {
                lines += "windowLayoutInfo=timeout"
            } else {
                lines += "features=${info.displayFeatures.size}"
                for (f in info.displayFeatures) {
                    val b = f.bounds
                    lines += if (f is FoldingFeature) {
                        "feature=FoldingFeature bounds=${b.left},${b.top},${b.right},${b.bottom} state=${f.state} " +
                            "orientation=${f.orientation} isSeparating=${f.isSeparating} occlusion=${f.occlusionType}"
                    } else {
                        "feature=${f.javaClass.simpleName} bounds=${b.left},${b.top},${b.right},${b.bottom}"
                    }
                }
            }
        }
        lines.forEach { Log.i("QA-HINGE", it) }
        inst.sendStatus(0, Bundle().apply { putString("qa_hinge", lines.joinToString("\n")) })
    }
}
