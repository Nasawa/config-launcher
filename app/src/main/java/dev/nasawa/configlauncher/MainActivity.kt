package dev.nasawa.configlauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nasawa.configlauncher.ui.HomeScreen
import dev.nasawa.configlauncher.ui.HomeViewModel

class MainActivity : ComponentActivity() {
    // #220: build the ViewModel from this activity's own Application. The default factory goes through
    // AndroidViewModelFactory.getInstance(app), whose static keeps the first Application it sees; in a
    // Robolectric JVM (a new Application per test) that pinned later tests to the first test's store.
    private val vm: HomeViewModel by viewModels { viewModelFactory { initializer { HomeViewModel(application) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Launcher: back on home does nothing. Compose BackHandlers (drawer, dialogs) still take priority.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent { HomeScreen(vm) }
    }

    // singleTask: the Home key while we're in front arrives here (leave edit mode, go to page 1).
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) vm.homePresses.tryEmit(Unit)
    }

    // Poll immediately when the user comes home, then every 2 s while visible (ARCHITECTURE.md §4).
    override fun onStart() { super.onStart(); vm.store.start() }
    override fun onStop() { vm.store.stop(); super.onStop() }

}
