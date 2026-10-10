package app.circlet.strap.sync

import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.core.SampleSink

// Ported from OpenStrap edge/lib/ble/ble_engine.dart — DrainController and the
// idle watchdog (kBackfillIdleTimeoutSeconds = 60, kHistoryFirstStartTimeoutSeconds = 10).
// A batch is committed, and only then acknowledged. HISTORY_COMPLETE is never acknowledged.
// An open batch is dropped on idle, stall, or disconnect, so the strap re-sends it.
/**
 * History batches between a start marker and an end marker.
 *
 * [onEnd] calls [SampleSink.commit] and returns [EndResult.Ack] only after that call returns.
 * A second end with the same token does not commit again. [onComplete] never asks for an ack.
 */
class HistoryDrain(
    private val sink: SampleSink,
) {
    var open: Boolean = false
        private set
    var awaitingStart: Boolean = false
        private set

    private var finished = false
    private val samples = ArrayList<Sample>()
    private val raws = ArrayList<RawRecord>()
    private var lastToken: ByteArray? = null
    private var oweAck = false

    /** A new session. Clears the duplicate-token memory because this connection has not acked yet. */
    fun begin() {
        open = true
        awaitingStart = true
        finished = false
        samples.clear()
        raws.clear()
        lastToken = null
        oweAck = false
    }

    /** The drain is over without an acknowledgement. A later [begin] can start another. */
    fun abandon() {
        samples.clear()
        raws.clear()
        open = false
        finished = true
        awaitingStart = false
        oweAck = false
    }

    /** Drop the unacked batch and keep the tokens already committed on this connection. */
    fun abortOpen() {
        samples.clear()
        raws.clear()
        awaitingStart = true
    }

    fun onStart() {
        if (!open || finished) return
        samples.clear()
        raws.clear()
        awaitingStart = false
    }

    fun onRecord(sample: Sample?, raw: RawRecord) {
        if (!open || finished || awaitingStart) return
        if (sample != null) samples += sample
        raws += raw
    }

    suspend fun onEnd(token: ByteArray): EndResult {
        if (!open || finished || token.size != 8) return EndResult.Ignored
        val previous = lastToken
        if (previous != null && previous.contentEquals(token)) {
            return if (oweAck) EndResult.AckAgain(token.copyOf()) else EndResult.Duplicate
        }
        val committedSamples = samples.toList()
        val committedRaw = raws.toList()
        sink.commit(committedSamples, committedRaw)
        samples.clear()
        raws.clear()
        lastToken = token.copyOf()
        oweAck = true
        awaitingStart = true
        return EndResult.Ack(token.copyOf(), committedSamples.size)
    }

    /** The acknowledgement write completed. A repeat of this token is not written again. */
    fun ackLanded() {
        oweAck = false
    }

    fun onComplete(): EndResult {
        samples.clear()
        raws.clear()
        open = false
        finished = true
        awaitingStart = false
        return EndResult.Completed
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
        samples.clear()
        raws.clear()
        open = false
        finished = false
        awaitingStart = false
        lastToken = null
        oweAck = false
    }
}

sealed interface EndResult {
    data object Ignored : EndResult
    data object Duplicate : EndResult
    data class Ack(val token: ByteArray, val committed: Int) : EndResult

    /** Same token as the batch already committed, whose acknowledgement has not landed. */
    data class AckAgain(val token: ByteArray) : EndResult
    data object Completed : EndResult
    data object Retry : EndResult
}
