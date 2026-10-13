package app.circlet.strap.diag

import java.io.File
import java.io.IOException

/**
 * Two-file text log: `sync.log` is appended to and becomes `sync.log.1` once it reaches
 * [maxBytes], replacing the older `sync.log.1`. Failures to write are dropped, because a
 * diagnostic must never break a sync.
 */
internal class RotatingLog(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val current get() = File(dir, "sync.log")
    private val previous get() = File(dir, "sync.log.1")

    @Synchronized
    fun append(line: String) {
        try {
            dir.mkdirs()
            if (current.length() >= maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(line + "\n")
        } catch (_: IOException) {
            return
        }
    }

    /** Every retained line, oldest first. */
    @Synchronized
    fun lines(): List<String> = try {
        listOf(previous, current).filter { it.exists() }.flatMap { it.readLines() }
    } catch (_: IOException) {
        emptyList()
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 256L * 1024
    }
}
