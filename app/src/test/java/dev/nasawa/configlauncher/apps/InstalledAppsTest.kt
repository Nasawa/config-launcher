package dev.nasawa.configlauncher.apps

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InstalledAppsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)

    private fun activity(pkg: String, cls: String, label: String, user: UserHandle = Process.myUserHandle()): LauncherActivityInfo {
        val ai = ActivityInfo().apply {
            packageName = pkg; name = cls; nonLocalizedLabel = label
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = label }
        }
        // API 35: LauncherActivityInfo(Context, LauncherActivityInfoInternal); both constructors are hidden.
        val internalCls = Class.forName("android.content.pm.LauncherActivityInfoInternal")
        val statesCls = Class.forName("android.content.pm.IncrementalStatesInfo")
        val internal = ReflectionHelpers.callConstructor(
            internalCls,
            ClassParameter.from(ActivityInfo::class.java, ai),
            ClassParameter(statesCls, null),
            ClassParameter.from(UserHandle::class.java, user),
        )
        return ReflectionHelpers.callConstructor(
            LauncherActivityInfo::class.java,
            ClassParameter.from(Context::class.java, ctx),
            ClassParameter(internalCls, internal),
        )
    }

    @Test fun loadListsPersonalAppsSortedCaseInsensitively() {
        val shadow = shadowOf(launcherApps)
        shadow.addActivity(Process.myUserHandle(), activity("com.zeta", "com.zeta.Main", "zeta"))
        shadow.addActivity(Process.myUserHandle(), activity("com.Alpha", "com.Alpha.Main", "Alpha"))
        shadow.addActivity(Process.myUserHandle(), activity("com.beta", "com.beta.Main", "beta"))

        val apps = InstalledApps(ctx).load()

        // Robolectric's PackageManager doesn't know these packages, so labels fall back to the
        // package name; that still exercises the case-insensitive sort.
        assertEquals(listOf("com.Alpha", "com.beta", "com.zeta"), apps.map { it.packageName })
        assertEquals(setOf(dev.nasawa.configlauncher.model.Profile.PERSONAL), apps.map { it.profile }.toSet())
        assertEquals("personal:com.Alpha/com.Alpha.Main", apps.first().key)
    }

    @Test fun loadIsIdempotent() {
        val shadow = shadowOf(launcherApps)
        val a = activity("com.a", "com.a.Main", "Alpha")
        shadow.addActivity(Process.myUserHandle(), a)
        val installed = InstalledApps(ctx)
        assertEquals(1, installed.load().size)
        assertEquals(1, installed.load().size)
    }

    @Test fun twoLauncherActivitiesInOnePackageAreSeparateEntries() {
        val shadow = shadowOf(launcherApps)
        shadow.addActivity(Process.myUserHandle(), activity("com.a", "com.a.One", "One"))
        shadow.addActivity(Process.myUserHandle(), activity("com.a", "com.a.Two", "Two"))
        assertEquals(2, InstalledApps(ctx).load().map { it.key }.toSet().size)
    }
}
