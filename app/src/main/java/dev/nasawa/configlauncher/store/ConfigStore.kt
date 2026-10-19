package dev.nasawa.configlauncher.store

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
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
    val prefs = Prefs(context)
    /** §18.5 AF-1 app-only source: app-private, separate from last-good.json and history/. */
    val localDir: File = File(context.filesDir, "config/source")
    val engine = ConfigEngine(currentSource(), LastGoodStore(File(context.filesDir, "config")), scope = scope)
        .also { it.askBeforeMerge = prefs.askBeforeMerging }
    val state get() = engine.state

    private var pollJob: Job? = null
    private var fileObserver: FileObserver? = null
    private var contentObserver: ContentObserver? = null

    private fun currentSource(): ConfigSource {
        val uri = prefs.folderUri
        // A picked folder stays the source even if its grant is gone, so B3 (Reconnect) shows
        // instead of silently switching to app storage.
        if (uri != null) return SafSource(context.contentResolver, uri)
        return LocalFileSource(localDir, sidecars = false)
    }

    /** Call from onStart: poll now, then every 2 s until [stop]. */
    fun start() {
        if (pollJob?.isActive == true) return
        watch(engine.source)
        val appOnly = prefs.folderUri == null
        pollJob = scope.launch(Dispatchers.IO) {
            syncSchemaCopy()   // §20.2: after an app update the bundled schema may be newer
            runCatching { engine.poll(force = true) } // resume always hashes (§17 Q5)
            // §18.5: app-only mode doesn't poll; nothing external writes there (debug adb edits use the FileObserver).
            if (!appOnly) while (isActive) { delay(2_000); runCatching { engine.poll() } }
        }
    }

    fun stop() {
        pollJob?.cancel(); pollJob = null
        fileObserver?.stopWatching(); fileObserver = null
        contentObserver?.let { context.contentResolver.unregisterContentObserver(it) }; contentObserver = null
    }

    fun pollNow() { scope.launch(Dispatchers.IO) { engine.poll(force = true) } }

    fun edit(e: dev.nasawa.configlauncher.model.Edit) { scope.launch(Dispatchers.IO) { engine.edit(e) } }
    /** Snackbar Undo after an automatic merge (§18.2). */
    fun retrySave() { scope.launch(Dispatchers.IO) { engine.retrySave() } }

    /**
     * Leaving edit mode (ux.md §4.1, §14 R4/R5): queue the cleanup of the pages empty right now and write now, so it goes out
     * in the same write as any pending edit (or as one write if the edits were already saved).
     */
    fun leaveEditMode() {
        // Snapshot which pages are empty right now (Grace, #70): only those are dropped, even on a later replay.
        val ids = dev.nasawa.configlauncher.model.Edits.emptyPageIds(engine.state.value.config)
        scope.launch(Dispatchers.IO) {
            if (ids.isNotEmpty()) engine.edit(dev.nasawa.configlauncher.model.Edit.DropPages(ids))
            engine.retrySave()
        }
    }

    fun undoMerge() { scope.launch(Dispatchers.IO) { engine.undoMerge() } }

    fun setAskBeforeMerging(v: Boolean) { prefs.askBeforeMerging = v; engine.askBeforeMerge = v }

    fun useFile() { scope.launch(Dispatchers.IO) { engine.resolveUseFile() } }
    fun keepMine() { scope.launch(Dispatchers.IO) { engine.resolveKeepMine() } }

    fun createFromCurrentLayout() { scope.launch(Dispatchers.IO) { engine.createFromCurrentLayout() } }

    /** §17 Q15: cloud providers may sync slowly; only local storage is the recommended case. */
    fun isCloudFolder(uri: Uri) = uri.authority != "com.android.externalstorage.documents"

    fun useFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.folderUri = uri; prefs.appOnly = false
        switch()
        scope.launch(Dispatchers.IO) { syncAgentDocs(uri); syncSchemaCopy(uri) }
    }

    /** §20.2: offline schema copy in the folder's `.configlauncher/`, hash-guarded like AGENTS.md. Folder sources only. */
    fun syncSchemaCopy(uri: Uri? = prefs.folderUri): SchemaCopy.Outcome {
        uri ?: return SchemaCopy.Outcome.FAILED
        val bundled = runCatching { context.assets.open(SchemaCopy.ASSET).use { it.readBytes() } }.getOrNull() ?: return SchemaCopy.Outcome.FAILED
        return SchemaCopy.sync(SafSource(context.contentResolver, uri), bundled)
    }

    /**
     * §20.1: after a folder is chosen or connected, put AGENTS.md there (if the setting is on). [force] is the setting's
     * *Add again* action. Folder sources only; app-only mode has no folder.
     */
    fun syncAgentDocs(uri: Uri? = prefs.folderUri, force: Boolean = false): AgentDocs.Outcome {
        uri ?: return AgentDocs.Outcome.OFF
        val bundled = runCatching { context.assets.open(AgentDocs.ASSET).use { it.readBytes() } }.getOrNull() ?: return AgentDocs.Outcome.FAILED
        val o = AgentDocs.sync(SafSource(context.contentResolver, uri), bundled, prefs.agentDocsWritten(uri), prefs.agentDocs, force)
        if (AgentDocs.remember(o)) prefs.markAgentDocsWritten(uri)
        return o
    }

    private fun bundledAgentDocs() = runCatching { context.assets.open(AgentDocs.ASSET).use { it.readBytes() } }.getOrNull()

    /** ux.md §7.1: which label the ⋮ action shows; null in app-only mode (hidden). Blocking: call off the main thread. */
    fun agentDocsMenuAction(): AgentDocs.MenuAction? {
        val uri = prefs.folderUri ?: return null
        return AgentDocs.menuAction(AgentDocs.readExisting(SafSource(context.contentResolver, uri)))
    }

    /** ux.md §7.1 `Add/Update agent instructions`. [confirmed] = the user chose Replace for an edited file. Blocking. */
    fun updateAgentDocs(confirmed: Boolean): AgentDocs.Update {
        val uri = prefs.folderUri ?: return AgentDocs.Update.FAILED
        val bundled = bundledAgentDocs() ?: return AgentDocs.Update.FAILED
        return AgentDocs.update(SafSource(context.contentResolver, uri), bundled, confirmed).also {
            if (it == AgentDocs.Update.ADDED || it == AgentDocs.Update.UPDATED) prefs.markAgentDocsWritten(uri)
        }
    }

    /** First run "Skip for now": app-only storage; a folder can be chosen later. */
    fun skipOnboarding() {
        prefs.appOnly = true; prefs.folderUri = null
        val wasRunning = pollJob != null
        stop()
        // ux.md §7: start with the starter layout (written once if the phone file doesn't exist), so B4 never shows.
        scope.launch(Dispatchers.IO) { engine.moveTo(currentSource(), useMine = false); if (wasRunning) withContext(Dispatchers.Main) { start() } }
    }

    /**
     * #74: take the persistable grant first (SafSource refuses to read without it), then inspect. Any failure is
     * [ConfigEngine.Inspection.Unreadable], never Empty, so a file we couldn't read is never written over.
     * The grant is released again unless this folder is already the source; [moveToFolder] takes it for good.
     */
    fun inspectFolder(uri: Uri): ConfigEngine.Inspection {
        val took = runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }.isSuccess
        val ins = try { engine.inspect(SafSource(context.contentResolver, uri)) }
            catch (e: Exception) { ConfigEngine.Inspection.Unreadable(e.message ?: e.javaClass.simpleName, false) }
        if (took && prefs.folderUri != uri && ins is ConfigEngine.Inspection.Unreadable) release(uri)
        return ins
    }

    /** The user backed out after [inspectFolder]: drop its grant unless the folder is the source. */
    fun forgetFolder(uri: Uri) { if (prefs.folderUri != uri) release(uri) }

    private val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun release(uri: Uri?) { uri?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, rw) } } }

    /**
     * §18.2 move the current layout into a picked folder ([useMine]) or adopt the folder's file.
     * Settings are saved only after the move succeeds; on failure the new grant is released and the
     * old source stays (Grace review). On success the old folder's grant is released.
     */
    fun moveToFolder(uri: Uri, useMine: Boolean) {
        context.contentResolver.takePersistableUriPermission(uri, rw)
        val old = prefs.folderUri
        val wasRunning = pollJob != null
        stop()
        scope.launch(Dispatchers.IO) {
            val ok = engine.moveTo(SafSource(context.contentResolver, uri), useMine)
            if (ok) { prefs.folderUri = uri; prefs.appOnly = false; if (old != uri) release(old); syncAgentDocs(uri); syncSchemaCopy(uri) }
            else if (old != uri) release(uri)
            if (wasRunning) withContext(Dispatchers.Main) { start() }
        }
    }

    fun useAppStorage(moveLayout: Boolean = true) {
        if (moveLayout) {
            val old = prefs.folderUri
            val wasRunning = pollJob != null
            stop()
            // §18.2: copy the current layout into the phone file; only then switch settings and drop the grant.
            scope.launch(Dispatchers.IO) {
                if (engine.moveTo(LocalFileSource(localDir, sidecars = false), useMine = true)) {
                    prefs.folderUri = null; prefs.appOnly = true; release(old)
                }
                if (wasRunning) withContext(Dispatchers.Main) { start() }
            }
            return
        }

        prefs.folderUri?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.folderUri = null; prefs.appOnly = true
        switch()
    }

    private fun switch() {
        val wasRunning = pollJob != null
        stop()
        scope.launch(Dispatchers.IO) { engine.switchSource(currentSource()) }
        if (wasRunning) start()
    }

    private val isDebuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun watch(src: ConfigSource) {
        when (src) {
            is LocalFileSource -> {
                if (!isDebuggable) return // FileObserver is only for adb edits on debug builds (§18.5)
                src.dir.mkdirs()
                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.DELETE
                val onEvent = { path: String? -> if (path == LocalFileSource.FILE_NAME) pollNow() }
                fileObserver = object : FileObserver(src.dir, mask) {
                    override fun onEvent(event: Int, path: String?) = onEvent(path)
                }.also { it.startWatching() }
            }
            is SafSource -> {
                contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = pollNow()
                }.also { runCatching { context.contentResolver.registerContentObserver(src.childrenUri, true, it) } }
            }
        }
    }
}
