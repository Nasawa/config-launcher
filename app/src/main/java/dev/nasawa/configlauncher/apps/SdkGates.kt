package dev.nasawa.configlauncher.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** API-level gates for minSdk 23 (ARCHITECTURE.md §18.3). Pure functions of the SDK level so they can be unit tested. */
object SdkGates {
    const val SHORTCUT_HINT = "Shortcuts need Android 8 or later."
    const val PAUSED_HINT = "Work apps are paused. Turn them on in Settings."
    const val HOME_SETTINGS_HINT = "Open Apps → Default apps → Home app"
    const val INTERNAL_STORAGE_HINT = "Don't see your phone's storage? Tap ⋮ → Show internal storage in the picker."

    /** UserManager.isQuietModeEnabled (24). Below that, work apps are assumed not paused. */
    fun quietModeReadable(sdk: Int) = sdk >= 24
    /** UserManager.requestQuietModeEnabled (28). */
    fun canUnpause(sdk: Int) = sdk >= 28
    /** Shortcut launch/query/pin, single gate at 26 (pin flow is 26). */
    fun shortcuts(sdk: Int) = sdk >= 26
    /** Internal storage is hidden in the SAF picker by default on 23-25. */
    fun showInternalStorageHint(sdk: Int) = sdk < 26

    enum class HomeRoute { ROLE_MANAGER, HOME_SETTINGS }
    fun homeRoute(sdk: Int) = if (sdk >= 29) HomeRoute.ROLE_MANAGER else HomeRoute.HOME_SETTINGS

    /**
     * Intent that asks to become the home app: RoleManager on 29+, Settings.ACTION_HOME_SETTINGS on 23-28,
     * and Settings.ACTION_SETTINGS when that's missing (some old Samsung ROMs). Second value = hint to show.
     */
    fun homeIntent(context: Context, sdk: Int): Pair<Intent, String?> {
        if (sdk >= 29 && android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) return rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME) to null
        }
        val home = Intent(Settings.ACTION_HOME_SETTINGS)
        return if (home.resolveActivity(context.packageManager) != null) home to null
        else Intent(Settings.ACTION_SETTINGS) to HOME_SETTINGS_HINT
    }
}
