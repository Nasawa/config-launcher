package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #54: `.configlauncher/apps.json`, every launchable app the drawer would show, so an agent can
 * place apps by real package/activity/label. Git-ignored like status.json. Work apps are listed
 * only when work apps are included in the config (§17 Q12). #72: apps hidden from the drawer (`drawer.hidden`) are left out
 * unless [entries]' includeHidden (setting `Include hidden apps in apps.json`, default off) is on.
 */
object AppsJson {
    const val FILE = "apps.json"

    fun entries(apps: List<InstalledApp>, includeWork: Boolean, hidden: Set<String> = emptySet(), includeHidden: Boolean = false): List<JsonObject> = apps
        .filter { includeWork || it.profile != Profile.WORK }
        .filter { includeHidden || it.packageName !in hidden }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }, { it.activityName }))
        .map { a ->
            buildJsonObject {
                put("package", a.packageName)
                put("activity", a.activityName)
                put("label", a.label)
                put("profile", a.profile.wire)
                put("installTime", if (a.installTime > 0) StatusJson.iso(a.installTime) else null)
            }
        }

    fun encode(entries: List<JsonObject>, ms: Long): ByteArray = buildJsonObject {
        put("generated", StatusJson.iso(ms))
        put("apps", JsonArray(entries))
    }.toString().plus("\n").toByteArray(Charsets.UTF_8)
}
