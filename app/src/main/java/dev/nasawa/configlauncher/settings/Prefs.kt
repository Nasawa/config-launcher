package dev.nasawa.configlauncher.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Small settings store. SharedPreferences for the prototype (ARCHITECTURE.md plans DataStore). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var folderUri: Uri?
        get() = sp.getString("folderUri", null)?.let(Uri::parse)
        set(v) = sp.edit { if (v == null) remove("folderUri") else putString("folderUri", v.toString()) }

    /** §18.2 Q-C2: focus drawer search and show the keyboard on open (default on; off for wall displays). */
    /** #61: the first-run "Set as home app" prompt was shown (never shown again). */
    var homePromptShown: Boolean
        get() = sp.getBoolean("homePromptShown", false)
        set(v) = sp.edit { putBoolean("homePromptShown", v) }

    /** #61: role-request denials; after [dev.nasawa.configlauncher.apps.HomeRole.MAX_DENIALS] we open Home settings. */
    var homeRoleDenials: Int
        get() = sp.getInt("homeRoleDenials", 0)
        set(v) = sp.edit { putInt("homeRoleDenials", v) }

    var drawerKeyboard: Boolean
        get() = sp.getBoolean("drawerKeyboard", true)
        set(v) = sp.edit { putBoolean("drawerKeyboard", v) }

    /**
     * §18.5 persisted "no folder" state: true once the user chose app-only (Skip for now / Use phone
     * storage). Distinct from never onboarded, so onboarding doesn't ask again.
     */
    var appOnly: Boolean
        get() = sp.getBoolean("appOnly", false)
        set(v) = sp.edit { putBoolean("appOnly", v) }

    /** First run finished: a folder is connected or app-only was chosen. */
    val onboarded: Boolean get() = appOnly || folderUri != null

    /** §18.1 Q-C4: rounded 40 % black scrim behind home labels (default off). App setting, not in launcher.json. */
    var labelBackdrop: Boolean
        get() = sp.getBoolean("labelBackdrop", false)
        set(v) = sp.edit { putBoolean("labelBackdrop", v) }

    /** §18.2: restore the B6 Use file / Keep mine prompt instead of the automatic merge (default off). */
    var askBeforeMerging: Boolean
        get() = sp.getBoolean("askBeforeMerging", false)
        set(v) = sp.edit { putBoolean("askBeforeMerging", v) }

    /** §20.1 *Add agent instructions to folder* (default on). */
    var agentDocs: Boolean
        get() = sp.getBoolean("agentDocs", true)
        set(v) = sp.edit { putBoolean("agentDocs", v) }

    /** §20.1: folders (tree URIs) that got an AGENTS.md once, so a deleted one isn't recreated. */
    fun agentDocsWritten(uri: Uri) = uri.toString() in sp.getStringSet("agentDocsWritten", emptySet())!!
    fun markAgentDocsWritten(uri: Uri) = sp.edit { putStringSet("agentDocsWritten", sp.getStringSet("agentDocsWritten", emptySet())!! + uri.toString()) }
}
