package dev.nasawa.configlauncher.store

import dev.nasawa.configlauncher.config.ConfigCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * launcher.json in app-specific storage (no permission needed; reachable with
 * `adb push launcher.json /sdcard/Android/data/dev.nasawa.configlauncher/files/`).
 * Writes are temp file + fsync + rename, which is atomic on the same filesystem.
 */
class LocalFileSource(val dir: File) : ConfigSource {
    val file = File(dir, FILE_NAME)
    private val temp = File(File(dir, ".configlauncher"), "$FILE_NAME.tmp")

    override val displayName = "App storage"

    override fun stamp(): Stamp? = if (file.isFile) Stamp(file.path, file.lastModified(), file.length()) else null

    override fun read(maxBytes: Int): ByteArray? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) throw IOException("launcher.json is larger than ${maxBytes / 1024} KiB")
        return file.inputStream().use { it.readCapped(maxBytes) }
    }

    override fun write(bytes: ByteArray) {
        temp.parentFile!!.mkdirs()
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        if (ConfigCodec.sha256(temp.readBytes()) != ConfigCodec.sha256(bytes)) throw IOException("temp file verification failed")
        if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
    }

    override fun writeSidecar(subdir: String, name: String, bytes: ByteArray) {
        val d = File(File(dir, ".configlauncher"), subdir)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("can't create ${d.path}")
        val f = File(d, name)
        val t = File(d, "$name.tmp")
        FileOutputStream(t).use { it.write(bytes); it.fd.sync() }
        if (!t.renameTo(f)) throw IOException("could not write ${f.name}")
    }

    companion object { const val FILE_NAME = "launcher.json" }
}
