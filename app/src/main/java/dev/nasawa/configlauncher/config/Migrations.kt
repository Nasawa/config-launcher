package dev.nasawa.configlauncher.config

import kotlinx.serialization.json.*

/** Schema migrations. v1 is the only version; older/newer versions are reported, never guessed. */
object Migrations {
    const val CURRENT = 1

    data class Result(val tree: JsonElement, val error: String? = null)

    fun migrate(tree: JsonElement): Result {
        val o = tree as? JsonObject ?: return Result(tree)
        val v = (o["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: return Result(tree) // missing/odd version is reported by the validator
        return when {
            v == CURRENT -> Result(tree)
            v > CURRENT -> Result(tree, "schemaVersion $v is newer than this launcher supports ($CURRENT); update the app")
            else -> Result(tree, "schemaVersion $v is not supported")
        }
    }
}
