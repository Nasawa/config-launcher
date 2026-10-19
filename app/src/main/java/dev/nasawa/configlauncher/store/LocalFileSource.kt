package dev.nasawa.configlauncher.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * launcher.json in a real directory we own. In the app it is the AF-1 app-only source
 * (`filesDir/config/source/launcher.json`, ARCHITECTURE.md §18.5) with [sidecars] = false: no
 * `.configlauncher/`, no status.json. Tests also use it with sidecars as a stand-in for a folder.
 * Writes are `launcher.json.tmp` in the same directory + fsync + rename (atomic; no read-back verify).
 * Debug builds: `adb shell run-as dev.nasawa.configlauncher sh -c 'cat > files/config/source/launcher.json' < launcher.json`.
 */
class LocalFileSource(val dir: File, val sidecars: Boolean = true) : ConfigSource {
    val file = File(dir, FILE_NAME)
    private val temp = File(dir, "$FILE_NAME.tmp")

    override val displayName = "App storage"
    override val reconnectable = false

    override fun stamp(): Stamp? = if (file.isFile) Stamp(file.path, file.lastModified(), file.length()) else null

    override fun read(maxBytes: Int): ByteArray? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
        return file.inputStream().use { it.readCapped(maxBytes) }
    }

    override fun write(bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create ${dir.path}")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
    }

    // App-only mode: sync-conflict notices don't apply (§18.5).
    override fun siblingNames(): List<String> = if (sidecars) dir.list()?.toList().orEmpty() else emptyList()

    override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
        if (!sidecars) throw UnsupportedOperationException("app-only mode has no .configlauncher folder (§18.5)")
        val d = File(File(dir, ".configlauncher"), subdir)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("can't create ${d.path}")
        val f = File(d, name)
        val t = File(d, "$name.tmp")
        FileOutputStream(t).use { it.write(bytes); it.fd.sync() }
        if (!t.renameTo(f)) throw IOException("could not write ${f.name}")
    }

    // Folder stand-in for tests only; app-only mode (sidecars = false) has no folder files (§20.1).
    override fun readRoot(name: String, maxBytes: Int): ByteArray? {
        if (!sidecars) throw UnsupportedOperationException("app-only mode")
        return File(dir, name).takeIf { it.isFile }?.inputStream()?.use { it.readCapped(maxBytes) }
    }

    override fun writeRoot(name: String, bytes: ByteArray) {
        if (!sidecars) throw UnsupportedOperationException("app-only mode")
        val t = File(dir, "$name.tmp")
        FileOutputStream(t).use { it.write(bytes); it.fd.sync() }
        if (!t.renameTo(File(dir, name))) throw IOException("could not write $name")
    }

    companion object { const val FILE_NAME = "launcher.json" }
}
