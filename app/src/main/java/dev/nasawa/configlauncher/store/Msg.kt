package dev.nasawa.configlauncher.store

import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * A user-facing message as a string resource plus format args (docs/ux.md §10: no English in Kotlin).
 * The engine stays resource-free at runtime; the UI resolves it.
 */
data class Msg(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    fun resolve(r: Resources): String = r.getString(id, *args.toTypedArray())
}

/** A reason from an exception for a message argument (never null/blank). */
internal fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/** Thrown by the writer when the canonical output would exceed the cap (§17 Q16). */
class TooLargeException(val limitMb: Int) : java.io.IOException("launcher.json would be over $limitMb MB")
