package dev.nasawa.configlauncher.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #58: home is transparent over the system wallpaper. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 35])
class WallpaperThemeTest {
    @Test fun activityThemeShowsWallpaper() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val info = ctx.packageManager.getActivityInfo(android.content.ComponentName(ctx, MainActivity::class.java), 0)
        val a = ctx.createConfigurationContext(ctx.resources.configuration).apply { setTheme(info.themeResource.takeIf { it != 0 } ?: ctx.applicationInfo.theme) }
            .obtainStyledAttributes(intArrayOf(android.R.attr.windowShowWallpaper))
        assertTrue(a.getBoolean(0, false)); a.recycle()
    }
}
