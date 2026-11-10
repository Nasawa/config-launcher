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
 *   IOException and won't try creating that name again in this tree until the process restarts.
 * Pre-existing `(N)` folders are never touched.
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
        hidden += key
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

    companion object {
        private val lock = Any()
        /** key → document this process created or found, reused when a cached listing misses it. */
        private val made = HashMap<String, String>()
        /** keys whose create the provider renamed while the original stayed unlisted: don't create again. */
        private val hidden = HashSet<String>()
    }
}
