package app.circlet.core

import java.io.File

interface RecordingStorage {
    val root: String
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
    fun append(path: String, bytes: ByteArray) = write(path, (read(path) ?: byteArrayOf()) + bytes)
    fun delete(path: String): Boolean
    fun paths(): List<String>
}

/** Coordinates local step/route writers with backup capture and restore. */
object RecordingFiles {
    @Volatile var storage: RecordingStorage? = null
    fun <T> locked(block: () -> T): T = synchronized(this, block)

    private fun path(file: File, store: RecordingStorage): String? {
        val root = File(store.root).toPath().toAbsolutePath().normalize()
        val target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root)) return null
        val path = root.relativize(target).toString().replace('\\', '/')
        return path.takeIf { it.startsWith("steps/") || it.startsWith("routes/") }
    }

    fun read(file: File): ByteArray? = locked {
        storage?.let { store -> path(file, store)?.let { return@locked store.read(it) } }
        if (file.isFile) file.readBytes() else null
    }

    fun append(file: File, text: String) = locked {
        storage?.let { store -> path(file, store)?.let { store.append(it, text.toByteArray()); return@locked } }
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    fun write(file: File, bytes: ByteArray) = locked {
        storage?.let { store -> path(file, store)?.let { store.write(it, bytes); return@locked } }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun delete(file: File): Boolean = locked {
        storage?.let { store -> path(file, store)?.let { return@locked store.delete(it) } }
        file.delete()
    }

    fun list(directory: File): List<File> = locked {
        storage?.let { store ->
            val parent = path(File(directory, "_"), store)?.substringBeforeLast('/')
            if (parent != null) return@locked store.paths().filter { it.substringBeforeLast('/') == parent }.map { File(store.root, it) }
        }
        directory.listFiles().orEmpty().filter { it.isFile }
    }
}
