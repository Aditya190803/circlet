package app.circlet.core

/**
 * Lifetime counts of how history drains ended, kept on the phone.
 * [samplesReceived] counts samples committed to storage by drains.
 */
data class SyncCounters(
    val completed: Long = 0,
    val stalled: Long = 0,
    val disconnected: Long = 0,
    val ackFailed: Long = 0,
    val decodeFailed: Long = 0,
    val samplesReceived: Long = 0,
)

/** Read side of the sync diagnostics. The log holds timestamps and counts only. */
interface SyncDiagnostics {
    fun counters(): SyncCounters

    /** The newest [max] log lines, oldest first. */
    fun recentLines(max: Int): List<String>

    /** Every retained log line, oldest first, as text to export. */
    fun exportText(): String
}

/** Used when no strap backend is open. */
object NoSyncDiagnostics : SyncDiagnostics {
    override fun counters(): SyncCounters = SyncCounters()
    override fun recentLines(max: Int): List<String> = emptyList()
    override fun exportText(): String = ""
}
