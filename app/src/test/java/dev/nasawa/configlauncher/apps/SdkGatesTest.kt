package dev.nasawa.configlauncher.apps

import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Profile
import dev.nasawa.configlauncher.model.ShortcutItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class SdkGatesPureTest {
    @Test fun levels() {
        assertFalse(SdkGates.quietModeReadable(23)); assertTrue(SdkGates.quietModeReadable(24))
        assertFalse(SdkGates.canUnpause(27)); assertTrue(SdkGates.canUnpause(28))
        assertFalse(SdkGates.shortcuts(25)); assertTrue(SdkGates.shortcuts(26))
        assertTrue(SdkGates.showInternalStorageHint(25)); assertFalse(SdkGates.showInternalStorageHint(26))
        assertEquals(SdkGates.HomeRoute.HOME_SETTINGS, SdkGates.homeRoute(28))
        assertEquals(SdkGates.HomeRoute.ROLE_MANAGER, SdkGates.homeRoute(29))
    }
}

/** §18.3: the real code paths must not hit missing APIs at 23, 26 and 35. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [23, 26, 35])
class SdkGatesRobolectricTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun loadAndLaunchDoNotCrash() {
        val apps = InstalledApps(ctx)
        apps.load() // profiles + quiet mode gates
        val paused = InstalledApp("p.x", "p.x.Main", "X", Profile.PERSONAL, paused = true)
        apps.launch(paused)
        val ok = apps.launch(ShortcutItem("s", "p.x", "sc1"))
        if (Build.VERSION.SDK_INT < 26) assertFalse(ok)
    }

    @Test fun homeIntentPerLevel() {
        val (intent, _) = SdkGates.homeIntent(ctx, Build.VERSION.SDK_INT)
        val allowed = mutableSetOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_SETTINGS)
        if (Build.VERSION.SDK_INT >= 29) allowed += "android.app.role.action.REQUEST_ROLE"
        assertTrue(intent.action, intent.action in allowed)
    }
}
