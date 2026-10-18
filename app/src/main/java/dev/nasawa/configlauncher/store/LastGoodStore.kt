package dev.nasawa.configlauncher.store

import java.io.File
import java.io.FileOutputStream

/**
 * App-private last-good copy and history (AQ-7, OQ-16): filesDir/config/last-good.json,
 * written temp + rename; the replaced copy rotates into history/ (newest [keep] kept).
 */
open class LastGoodStore(private val dir: File, private val keep: Int = 10, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, "last-good.json")
    private val history = File(dir, "history")

    fun load(): ByteArray? = file.takeIf { it.isFile }?.readBytes()

    open fun save(bytes: ByteArray) {
        dir.mkdirs()
        if (file.isFile && file.readBytes().contentEquals(bytes)) return
        if (file.isFile) snapshot(file.readBytes())
        val tmp = File(dir, "last-good.json.tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        tmp.renameTo(file)
    }

    /** Store a version that is being replaced or discarded so it can be restored later. */
    fun snapshot(bytes: ByteArray) {
        history.mkdirs()
        var name = "${clock()}.json"
        var n = 1
        while (File(history, name).exists()) name = "${clock()}-${n++}.json"
        File(history, name).writeBytes(bytes)
        // Names are <epoch-ms>[-n].json, so lexical order is chronological.
        historyFiles().drop(keep).forEach { it.delete() }
    }

    fun historyFiles(): List<File> = history.listFiles()?.sortedByDescending { it.name }.orEmpty()
}
