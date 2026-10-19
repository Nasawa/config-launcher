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
import android.util.LruCache
import androidx.core.graphics.createBitmap
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Profile
import dev.nasawa.configlauncher.model.ShortcutItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Installed launchable apps via LauncherApps (no QUERY_ALL_PACKAGES). Personal profile plus the
 * first managed (work) profile that isn't Samsung Secure Folder; private space and other profiles are never exposed (OQ-31).
 */
class InstalledApps(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val users = ConcurrentHashMap<Profile, UserHandle>()
    private val icons = LruCache<String, Bitmap>(300)

    private fun profiles(): Map<Profile, UserHandle> {
        val me = Process.myUserHandle()
        val out = mutableMapOf(Profile.PERSONAL to me)
        WorkProfile.pick(launcherApps.profiles.filter { it != me && isManaged(it) }) { u ->
            runCatching { launcherApps.getActivityList(null, u).map { it.componentName.packageName } }.getOrDefault(emptyList())
        }?.let { out[Profile.WORK] = it }
        return out
    }

    /** API 35+ can tell work from private space/clone; before that the only extra profile type is work. */
    private fun isManaged(user: UserHandle): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 35) launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_MANAGED
        else true

    fun load(): List<InstalledApp> {
        infos.clear(); users.clear()
        val out = mutableListOf<InstalledApp>()
        for ((profile, user) in profiles()) {
            users[profile] = user
            val paused = profile == Profile.WORK && runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false)
            for (info in launcherApps.getActivityList(null, user)) {
                val app = InstalledApp(info.componentName.packageName, info.componentName.className, info.label.toString(), profile, paused)
                infos[app.key] = info
                out += app
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun icon(app: InstalledApp, sizePx: Int): Bitmap? {
        icons.get(app.key)?.let { return it }
        val d = infos[app.key]?.getBadgedIcon(0) ?: return null
        val bmp = createBitmap(sizePx, sizePx)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
        icons.put(app.key, bmp)
        return bmp
    }

    /** System "App info" for [packageName] in [profile] (ux.md item sheet). Returns false if it can't open. */
    fun appInfo(packageName: String, activity: String?, profile: Profile): Boolean = try {
        val user = users[profile] ?: Process.myUserHandle()
        val cls = activity ?: infos.values.firstOrNull { it.componentName.packageName == packageName }?.componentName?.className ?: ""
        launcherApps.startAppDetailsActivity(ComponentName(packageName, cls), user, null, null); true
    } catch (_: Exception) { false }

    /** Launch in the app's own profile. Returns false (never throws) if it can't start. */
    fun launch(app: InstalledApp): Boolean {
        val user = users[app.profile] ?: return false
        if (app.paused) { runCatching { userManager.requestQuietModeEnabled(false, user) }; return true }
        return try {
            launcherApps.startMainActivity(ComponentName(app.packageName, app.activityName), user, null, null); true
        } catch (_: Exception) { false }
    }

    /** Pinned/dynamic shortcut; needs the default-launcher role (shortcut host access). */
    fun launch(s: ShortcutItem): Boolean = try {
        launcherApps.startShortcut(s.packageName, s.shortcutId, null, null, users[s.profile ?: Profile.PERSONAL] ?: Process.myUserHandle())
        true
    } catch (_: Exception) { false }

    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) { icons.evictAll(); trySend(Unit) }
            override fun onPackageAdded(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(p: String?, u: UserHandle?) { icons.evictAll(); trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesSuspended(p: Array<out String>?, u: UserHandle?) { trySend(Unit) }
            override fun onPackagesUnsuspended(p: Array<out String>?, u: UserHandle?) { trySend(Unit) }
        }
        launcherApps.registerCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(cb) }
    }
}
