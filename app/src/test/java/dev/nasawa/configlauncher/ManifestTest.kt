package dev.nasawa.configlauncher

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** ARCHITECTURE.md §1, §8: HOME activity, and no risky permissions in v1. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManifestTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun homeIntentResolvesToMainActivity() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
        val hits = ctx.packageManager.queryIntentActivities(home, 0)
        assertEquals(listOf(MainActivity::class.java.name), hits.map { it.activityInfo.name })
    }

    @Test fun requestsNoRiskyPermissions() {
        val requested = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val forbidden = setOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.INTERNET",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_DEVICE_ADMIN",
            "android.permission.PACKAGE_USAGE_STATS",
        )
        assertTrue("forbidden permissions requested: ${requested intersect forbidden}", (requested intersect forbidden).isEmpty())
    }

    @Test fun mainActivityIsSingleTaskAndExcludedFromRecents() {
        val info = ctx.packageManager.getActivityInfo(
            android.content.ComponentName(ctx, MainActivity::class.java), 0,
        )
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}
