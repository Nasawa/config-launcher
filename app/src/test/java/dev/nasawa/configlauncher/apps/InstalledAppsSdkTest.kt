package dev.nasawa.configlauncher.apps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Profile
import dev.nasawa.configlauncher.model.ShortcutItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** minSdk 29: the real LauncherApps/UserManager paths run at the floor and at target. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 35])
class InstalledAppsSdkTest {
    @Test fun loadAndLaunchDoNotCrash() {
        val apps = InstalledApps(ApplicationProvider.getApplicationContext<Application>())
        apps.load()
        apps.launch(InstalledApp("p.x", "p.x.Main", "X", Profile.PERSONAL, paused = true))
        apps.launch(ShortcutItem("s", "p.x", "sc1"))
    }
}
