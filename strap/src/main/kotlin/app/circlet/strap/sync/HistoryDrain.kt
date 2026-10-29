package app.circlet.strap.sync

import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.core.SampleSink
import app.circlet.core.StrapGeneration

// Ported from OpenStrap edge/lib/ble/ble_engine.dart — DrainController and the
// idle watchdog (kBackfillIdleTimeoutSeconds = 60, kHistoryFirstStartTimeoutSeconds = 10).
// A batch is committed, and only then acknowledged. HISTORY_COMPLETE is never acknowledged.
// Every HISTORY_FLUSH_EVERY samples are committed before the end marker so progress is visible;
// the end marker commits only what is left and is still the only thing acknowledged.
// An uncommitted remainder is dropped on idle, stall, or disconnect, so the strap re-sends it.
// A sample committed early is replaced if the strap sends that second again.

internal const val HISTORY_FLUSH_EVERY = 300
/**
 * Gen 4 accepts data or END without START. Gen 5 requires START and validates its burst count.
 *
 * [onEnd] calls [SampleSink.commit] and returns [EndResult.Ack] only after that call returns.
 * A second end with the same token does not commit again. [onComplete] never asks for an ack.
 */
class HistoryDrain(
    private val sink: SampleSink,
    var strapGeneration: StrapGeneration = StrapGeneration.GEN4,
) {
    var open: Boolean = false
        private set
    var awaitingStart: Boolean = false
        private set

    private var finished = false
    private var generation = 0L
    private val samples = ArrayList<Sample>()
    private val raws = ArrayList<RawRecord>()
    private var lastToken: ByteArray? = null
    private var traffic = 0L
    private var tallyClosed = true
    private var validationFailures = 0

    /** A batch was dropped and no start marker has arrived since, so an end marker belongs to the dropped batch. */
    private var staleEnd = false

    /** A new session. Clears the duplicate-token memory because this connection has not acked yet. */
    fun begin() {
        generation++
        open = true
        awaitingStart = true
        finished = false
        samples.clear()
        raws.clear()
        lastToken = null
        staleEnd = false
        traffic = 0
        tallyClosed = true
        validationFailures = 0
    }

    /** The drain is over without an acknowledgement. A later [begin] can start another. */
    fun abandon() {
        generation++
        samples.clear()
        raws.clear()
        open = false
        finished = true
        awaitingStart = false
    }

    /** Drop the unacked batch and keep the tokens already committed on this connection. */
    fun abortOpen() {
        generation++
        staleEnd = staleEnd || !awaitingStart || samples.isNotEmpty() || raws.isNotEmpty()
        samples.clear()
        raws.clear()
        awaitingStart = true
    }

    /** Called only after ABORT has settled; Gen 4 need not emit another START. */
    fun resumeAfterAbort() {
        if (!open || finished) return
        generation++
        staleEnd = false
        awaitingStart = true
    }

    fun onStart() {
        if (!open || finished) return
        generation++
        samples.clear()
        raws.clear()
        awaitingStart = false
        staleEnd = false
        traffic = 0
        tallyClosed = false
    }

    fun onRecord(sample: Sample?, raw: RawRecord): Boolean {
        if (!open || finished || staleEnd) return false
        if (strapGeneration == StrapGeneration.GEN5 && (awaitingStart || tallyClosed)) return false
        awaitingStart = false
        if (!tallyClosed) traffic++
        if (sample != null) samples += sample
        raws += raw
        return true
    }

    fun onTraffic() {
        if (open && !finished && !awaitingStart && !tallyClosed) traffic++
    }

    /** Samples held in the open batch and not committed yet. */
    fun bufferedSamples(): Int = if (!open || finished || awaitingStart) 0 else samples.size

    fun isCommittedToken(token: ByteArray): Boolean =
        open && !finished && !staleEnd && lastToken?.contentEquals(token) == true

    /**
     * Stores the open batch without acknowledging it.
     * Returns how many samples were committed. Zero when there is nothing to store.
     */
    suspend fun flush(): Int {
        if (!open || finished || awaitingStart) return 0
        if (samples.isEmpty() && raws.isEmpty()) return 0
        val committedSamples = samples.toList()
        val committedRaw = raws.toList()
        val owner = generation
        sink.commit(committedSamples, committedRaw)
        if (owner != generation) return 0
        samples.clear()
        raws.clear()
        return committedSamples.size
    }

    suspend fun onEnd(token: ByteArray, expectedPacketCount: Long? = null): EndResult {
        if (!open || finished || token.size != 8) return EndResult.Ignored
        if (staleEnd) return EndResult.Ignored
        val previous = lastToken
        if (previous != null && previous.contentEquals(token)) {
            // A successful GATT write does not prove the strap advanced its checkpoint.
            samples.clear()
            raws.clear()
            awaitingStart = true
            return EndResult.AckAgain(token.copyOf())
        }
        if (strapGeneration == StrapGeneration.GEN5) {
            if (awaitingStart) return EndResult.Ignored
            tallyClosed = true
            val slack = if (validationFailures >= 3) 2 else 0
            if (expectedPacketCount != null && expectedPacketCount - slack > traffic) {
                validationFailures++
                return if (validationFailures >= 15) EndResult.Stuck else EndResult.Refuse
            }
            validationFailures = 0
        }
        val committedSamples = samples.toList()
        val committedRaw = raws.toList()
        val owner = generation
        sink.commit(committedSamples, committedRaw)
        if (owner != generation) return EndResult.Ignored
        samples.clear()
        raws.clear()
        lastToken = token.copyOf()
        awaitingStart = true
        return EndResult.Ack(token.copyOf(), committedSamples.size)
    }

    suspend fun onComplete(): EndResult {
        if (!open || finished) return EndResult.Ignored
        if (staleEnd || (strapGeneration == StrapGeneration.GEN5 && (!awaitingStart || validationFailures > 0))) return EndResult.Retry
        val owner = generation
        val committed = flush()
        if (owner != generation) return EndResult.Ignored
        generation++
        samples.clear()
        raws.clear()
        open = false
        finished = true
        awaitingStart = false
        return EndResult.Completed(committed)
    }

    /** Silence mid-drain. The open batch is dropped and not acknowledged. */
    fun onIdle(): EndResult {
        if (!open || finished) return EndResult.Ignored
        abortOpen()
        return EndResult.Retry
    }

    /** No start, record, or end arrived for the request. Nothing is acknowledged. */
    fun onStall(): EndResult {
        if (!open || finished || !awaitingStart) return EndResult.Ignored
        abortOpen()
        return EndResult.Retry
    }

    /** The link dropped with a batch still open. Those records are not committed. */
    fun onDisconnect() {
        generation++
        samples.clear()
        raws.clear()
        open = false
        finished = false
        awaitingStart = false
        lastToken = null
    }
}

sealed interface EndResult {
    data object Refuse : EndResult
    data object Stuck : EndResult
    data object Ignored : EndResult
    data class Ack(val token: ByteArray, val committed: Int) : EndResult

    /** Same durable checkpoint was offered again; echo it without committing twice. */
    data class AckAgain(val token: ByteArray) : EndResult
    data class Completed(val committed: Int) : EndResult
    data object Retry : EndResult
}
