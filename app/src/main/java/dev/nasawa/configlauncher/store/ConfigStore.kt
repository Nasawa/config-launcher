package dev.nasawa.configlauncher.store

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import dev.nasawa.configlauncher.settings.Prefs
import kotlinx.coroutines.*
import java.io.File

/**
 * Android wiring around [ConfigEngine]: picks the source (SAF folder if granted, else app storage),
 * polls every 2 s while the home screen is visible and immediately on start, uses FileObserver
 * for app storage and a ContentObserver hint for SAF (ARCHITECTURE.md §4).
 */
class ConfigStore(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = Prefs(context)
    val localDir: File = context.getExternalFilesDir(null) ?: context.filesDir
    val engine = ConfigEngine(currentSource(), LastGoodStore(File(context.filesDir, "config")), scope = scope)
    val state get() = engine.state

    private var pollJob: Job? = null
    private var fileObserver: FileObserver? = null
    private var contentObserver: ContentObserver? = null

    private fun currentSource(): ConfigSource {
        val uri = prefs.folderUri
        if (uri != null && context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            return SafSource(context.contentResolver, uri)
        }
        return LocalFileSource(localDir)
    }

    /** Call from onStart: poll now, then every 2 s until [stop]. */
    fun start() {
        if (pollJob?.isActive == true) return
        watch(engine.source)
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) { runCatching { engine.poll() }; delay(2_000) }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        fileObserver?.stopWatching(); fileObserver = null
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }; contentObserver = null
    }

    fun pollNow() { scope.launch(Dispatchers.IO) { engine.poll(force = true) } }

    fun edit(e: dev.nasawa.configlauncher.model.Edit) { scope.launch(Dispatchers.IO) { engine.edit(e) } }
    fun useFile() { scope.launch(Dispatchers.IO) { engine.resolveUseFile() } }
    fun keepMine() { scope.launch(Dispatchers.IO) { engine.resolveKeepMine() } }

    fun useFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.folderUri = uri
        switch()
    }

    fun useAppStorage() {
        prefs.folderUri?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.folderUri = null
        switch()
    }

    private fun switch() {
        val wasRunning = pollJob != null
        stop()
        scope.launch(Dispatchers.IO) { engine.switchSource(currentSource()) }
        if (wasRunning) start()
    }

    private fun watch(src: ConfigSource) {
        when (src) {
            is LocalFileSource -> {
                src.dir.mkdirs()
                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.DELETE
                val onEvent = { path: String? -> if (path == LocalFileSource.FILE_NAME) pollNow() }
                fileObserver = (if (Build.VERSION.SDK_INT >= 29) object : FileObserver(src.dir, mask) {
                    override fun onEvent(event: Int, path: String?) = onEvent(path)
                } else @Suppress("DEPRECATION") object : FileObserver(src.dir.path, mask) {
                    override fun onEvent(event: Int, path: String?) = onEvent(path)
                }).also { it.startWatching() }
            }
            is SafSource -> {
                contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = pollNow()
                }.also { runCatching { context.contentResolver.registerContentObserver(src.childrenUri, true, it) } }
            }
        }
    }
}
