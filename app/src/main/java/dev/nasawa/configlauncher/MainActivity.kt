package dev.nasawa.configlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.nasawa.configlauncher.ui.HomeScreen
import dev.nasawa.configlauncher.ui.HomeViewModel

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Launcher: back on home does nothing. Compose BackHandlers (drawer, dialogs) still take priority.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent { HomeScreen(vm) }
    }

    // Poll immediately when the user comes home, then every 2 s while visible (ARCHITECTURE.md §4).
    override fun onStart() { super.onStart(); vm.store.start() }
    override fun onStop() { vm.store.stop(); super.onStop() }

}
