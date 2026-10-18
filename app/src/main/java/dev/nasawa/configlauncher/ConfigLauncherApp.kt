package dev.nasawa.configlauncher

import android.app.Application
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.store.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Holds the manual DI graph (ARCHITECTURE.md §10). */
class ConfigLauncherApp : Application() {
    val scope = CoroutineScope(SupervisorJob())
    val apps by lazy { InstalledApps(this) }
    val store by lazy { ConfigStore(this, scope) }
}
