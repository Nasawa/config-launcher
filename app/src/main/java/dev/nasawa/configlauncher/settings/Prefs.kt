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
}
