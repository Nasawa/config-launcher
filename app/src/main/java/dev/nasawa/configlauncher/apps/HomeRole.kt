package dev.nasawa.configlauncher.apps

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** #61 / ux.md §7 step 4 and §8 `Home app`: become the default home app via RoleManager (API 29+). */
object HomeRole {
    enum class Route { ROLE_REQUEST, HOME_SETTINGS }

    /** The system blocks the role dialog after two denials, so go to Home settings then. */
    const val MAX_DENIALS = 2

    fun route(roleAvailable: Boolean, denials: Int): Route =
        if (roleAvailable && denials < MAX_DENIALS) Route.ROLE_REQUEST else Route.HOME_SETTINGS

    /** First-run prompt: once, only when we aren't home; never again after it was shown (no nagging). */
    fun shouldPrompt(onboarded: Boolean, held: Boolean, promptShown: Boolean) = onboarded && !held && !promptShown

    private fun rm(ctx: Context) = ctx.getSystemService(RoleManager::class.java)
    fun available(ctx: Context) = runCatching { rm(ctx)?.isRoleAvailable(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    fun held(ctx: Context) = runCatching { rm(ctx)?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    fun requestIntent(ctx: Context): Intent = rm(ctx).createRequestRoleIntent(RoleManager.ROLE_HOME)
    fun settingsIntent() = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
