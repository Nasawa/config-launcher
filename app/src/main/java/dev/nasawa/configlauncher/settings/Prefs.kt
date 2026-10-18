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

    /** First run finished (folder picked or "Skip for now", §18.2). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false) || folderUri != null
        set(v) = sp.edit { putBoolean("onboarded", v) }
}
