package dev.nasawa.configlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #55: deterministic reload for scripted agent loops. Runs the ⋮ → Reload pipeline and writes
 * status.json before the broadcast finishes. Guarded by android.permission.DUMP in the manifest
 * (adb shell holds it; third-party apps can't), so other apps can't trigger or spam it.
 * Result code / data (#75): 0 unchanged, 1 reloaded, 2 rejected, 3 unavailable, 4 busy, 5 timeout ([ReloadOutcome]).
 */
class ReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as ConfigLauncherApp
        val pending = goAsync()
        app.scope.launch(Dispatchers.IO) {
            try {
                val r = withTimeoutOrNull(8_000) { app.store.engine.reloadForAgent() } ?: dev.nasawa.configlauncher.store.ReloadOutcome.TIMEOUT
                pending.resultCode = r.code
                pending.resultData = r.word
            } finally { pending.finish() }
        }
    }

    companion object { const val ACTION = "dev.nasawa.configlauncher.RELOAD" }
}
