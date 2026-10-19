package dev.nasawa.configlauncher

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #55: the reload receiver is exported but only to senders holding DUMP (adb shell). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReloadReceiverTest {
    @Test fun guardedByDumpPermission() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val info = ctx.packageManager.getReceiverInfo(ComponentName(ctx, ReloadReceiver::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
    }
}
