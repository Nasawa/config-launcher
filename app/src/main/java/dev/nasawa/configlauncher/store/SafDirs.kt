package dev.nasawa.configlauncher.store

import android.provider.DocumentsContract.Document
import java.io.IOException

/** One child row of a SAF listing. */
internal data class DocChild(val docId: String, val mime: String, val modified: Long, val size: Long)

/** The few SAF document operations [SafDirs] needs. [SafSource] backs it with DocumentsContract; tests use a fake. */
internal interface DocTree {
    /** A fresh listing of [parentDocId], by display name. */
    fun list(parentDocId: String): Map<String, DocChild>
    /** Creates [name] and returns the new document id, or null. The provider may give it a different name. */
    fun create(parentDocId: String, mime: String, name: String): String?
    /** The document's current display name, or null when the provider can't say (or it's gone). */
    fun displayName(docId: String): String?
    fun delete(docId: String): Boolean
}

/**
 * #244: find-or-create for `.configlauncher/`, its subfolders and the files in them.
 *
 * SAF providers auto-rename on a name collision (`.configlauncher (1)`), and a listing can miss a child that
 * exists (a MediaStore `msd:` tree hides the dot-folder) or one that was just created (a cached listing). So:
 * - every call is serialized process-wide (SafSource instances are short-lived, so the lock is global);
 * - a fresh listing by exact display name comes first, then a document this process already created;
 * - after a create, the returned display name is checked. If the provider renamed it, the new document is deleted
 *   (only that one, only while empty), the original is used if it's now listed, else this fails with an
 *   IOException and won't try creating that name again in this tree (see "Suppression" below).
 * Pre-existing `(N)` folders are never touched.
 *
 * Suppression (#251, Archimedes): in memory only, keyed by tree URI + parent + name, so other trees and names
 * are unaffected. No poll counter expires it. It ends when:
 * - the user picks that folder again (first run / Choose folder / Reconnect: [ConfigEngine.moveTo] and
 *   [ConfigEngine.switchSource] call [clearTree] for the picked tree);
 * - a RELOAD broadcast arrives ([ConfigEngine.reloadForAgent] calls [clearAll]): the agent's "try again";
 * - the process restarts.
 * If the user deletes the hidden original from another app, nothing notices: the folder is created again on the
 * next re-pick, RELOAD or restart. That delay is acceptable by design (no polling for it).
 * The first time a key is suppressed it is queued once for [takeNew], which the engine turns into a one-shot
 * notice plus a Warnings / status.json line ([suppressed]); clearing re-arms it.
 */
internal class SafDirs(private val tree: DocTree, private val treeKey: String) {

    fun ensure(parentDocId: String, name: String, mime: String): String = synchronized(lock) {
        val key = "$treeKey\u0000$parentDocId\u0000$name"
        tree.list(parentDocId)[name]?.let { made[key] = it.docId; return it.docId }
        made[key]?.let { id -> if (runCatching { tree.displayName(id) }.getOrNull() == name) return id; made.remove(key) }
        if (key in hidden) throw hiddenError(name)
        val id = tree.create(parentDocId, mime, name) ?: throw IOException("can't create $name")
        val actual = runCatching { tree.displayName(id) }.getOrNull()
        if (actual == null || actual == name) { made[key] = id; return id }
        discardIfEmpty(parentDocId, id, mime)
        tree.list(parentDocId)[name]?.let { made[key] = it.docId; return it.docId }
        if (hidden.add(key)) unannounced += key
        throw hiddenError(name, actual)
    }

    /** Deletes [id] (the document just created) only if it is still empty; anything else is left alone. */
    private fun discardIfEmpty(parentDocId: String, id: String, mime: String) {
        val empty = runCatching {
            if (mime == Document.MIME_TYPE_DIR) tree.list(id).isEmpty()
            else tree.list(parentDocId).values.firstOrNull { it.docId == id }?.size == 0L
        }.getOrDefault(false)
        if (empty) runCatching { tree.delete(id) }
    }

    private fun hiddenError(name: String, actual: String? = null) = IOException(
        "this folder has a $name the storage provider doesn't show" + (actual?.let { " (it made \"$it\" instead)" } ?: "") +
            "; pick the folder again from internal storage")

    /** Names suppressed in this tree right now (for the Warnings list and status.json). */
    fun suppressed(): List<String> = synchronized(lock) { hidden.filter { it.startsWith(prefix) }.map(::nameOf).sorted() }

    /** Names newly suppressed in this tree since the last call: each suppression is returned once. */
    fun takeNew(): List<String> = synchronized(lock) {
        unannounced.filter { it.startsWith(prefix) }.also { unannounced.removeAll(it.toSet()) }.map(::nameOf)
    }

    private val prefix get() = "$treeKey\u0000"

    companion object {
        /** The user picked [treeKey]'s folder again: try creating there again (and warn again if it recurs). */
        fun clearTree(treeKey: String) {
            val p = "$treeKey\u0000"
            synchronized(lock) { hidden.removeAll { it.startsWith(p) }; unannounced.removeAll { it.startsWith(p) } }
        }

        /** A RELOAD broadcast: try again everywhere. */
        fun clearAll() = synchronized(lock) { hidden.clear(); unannounced.clear() }

        private fun nameOf(key: String) = key.substringAfterLast('\u0000')

        private val lock = Any()
        /** key → document this process created or found, reused when a cached listing misses it. */
        private val made = HashMap<String, String>()
        /** keys whose create the provider renamed while the original stayed unlisted: don't create again. */
        private val hidden = HashSet<String>()
        /** [hidden] keys not yet reported through [takeNew]. */
        private val unannounced = LinkedHashSet<String>()
    }
}
