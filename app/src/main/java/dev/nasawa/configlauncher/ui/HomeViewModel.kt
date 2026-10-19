package dev.nasawa.configlauncher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.Reconcile
import dev.nasawa.configlauncher.model.ResolvedLayout
import dev.nasawa.configlauncher.store.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUi(val layout: ResolvedLayout, val load: LoadState)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as ConfigLauncherApp
    val apps = graph.apps
    val store = graph.store
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = installed.asStateFlow()
    /** Home key pressed while home is already in front (MainActivity.onNewIntent): ux.md §1, §4.1. */
    val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val ui: StateFlow<HomeUi> = combine(store.state, installed) { load, inst -> HomeUi(Reconcile.resolve(load.config, inst), load) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi(Reconcile.resolve(store.state.value.config, emptyList()), store.state.value))

    init {
        viewModelScope.launch(Dispatchers.IO) {
            installed.value = apps.load()
            apps.changes().collect { installed.value = apps.load() }
        }
    }
}
