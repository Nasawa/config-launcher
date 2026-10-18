package dev.nasawa.configlauncher.apps

/**
 * §18.6 Q-v3-4: pre-One UI 8 Samsung exposes Secure Folder as a managed profile. Skip any managed
 * profile whose launchable activities include the Secure Folder package; a real work profile wins.
 * A Secure Folder profile is treated like private space: never shown, written or draggable.
 * The heuristic is unverified on hardware (test plan SF-A51).
 */
object WorkProfile {
    const val SECURE_FOLDER = "com.samsung.knox.securefolder"

    fun isSecureFolder(packages: Collection<String>) = SECURE_FOLDER in packages

    fun <U> pick(managed: List<U>, packagesOf: (U) -> Collection<String>): U? =
        managed.firstOrNull { !isSecureFolder(packagesOf(it)) }
}
