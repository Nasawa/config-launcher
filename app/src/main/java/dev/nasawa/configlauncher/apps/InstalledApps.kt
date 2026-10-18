package dev.nasawa.configlauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

/** Installed launchable apps across profiles, via LauncherApps (no QUERY_ALL_PACKAGES needed). */
class InstalledApps(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val infos = ConcurrentHashMap<String, Pair<LauncherActivityInfo, UserHandle>>()
    private val icons = ConcurrentHashMap<String, Bitmap>()

    fun load(): List<AppEntry> {
        infos.clear()
        val out = mutableListOf<AppEntry>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val e = AppEntry(
                    info.componentName.packageName,
                    info.componentName.className,
                    info.label.toString(),
                    if (user == Process.myUserHandle()) 0 else serial,
                )
                infos[e.key + "#" + e.userSerial] = info to user
                out += e
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun icon(app: AppEntry, sizePx: Int): Bitmap? {
        val id = app.key + "#" + app.userSerial
        icons[id]?.let { return it }
        val info = infos[id]?.first ?: return null
        val d = info.getBadgedIcon(0)
        val bmp = createBitmap(sizePx, sizePx)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
        icons[id] = bmp
        return bmp
    }

    /** Launch in the entry's own profile; never silently fall back to the personal profile. */
    fun launch(app: AppEntry) {
        val user = infos[app.key + "#" + app.userSerial]?.second
            ?: if (app.userSerial == 0L) Process.myUserHandle() else userManager.getUserForSerialNumber(app.userSerial) ?: return
        launcherApps.startMainActivity(
            ComponentName(app.packageName, app.activityName), user, null, null,
        )
    }

    /** Emits Unit whenever packages change. */
    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) { icons.clear(); trySend(Unit) }
            override fun onPackageAdded(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(p: String?, u: UserHandle?) { icons.clear(); trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
        }
        launcherApps.registerCallback(cb)
        awaitClose { launcherApps.unregisterCallback(cb) }
    }
}
