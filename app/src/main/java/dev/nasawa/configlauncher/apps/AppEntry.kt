package dev.nasawa.configlauncher.apps

/** A launchable activity as reported by LauncherApps. [key] is "package/activity". */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val userSerial: Long = 0,
) {
    val key: String get() = "$packageName/$activityName"
    /** Unique across profiles; the separator prevents "p/A1"+0 colliding with "p/A"+10. */
    val gridKey: String get() = "$key#$userSerial"
}
