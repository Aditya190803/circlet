package app.circlet.core

/** Coordinates local step/route writers with backup capture and restore. */
object RecordingFiles {
    fun <T> locked(block: () -> T): T = synchronized(this, block)
}
