@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.circlet.strap

import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.strap.support.RecordingSink
import app.circlet.strap.sync.EndResult
import app.circlet.strap.sync.HistoryDrain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDrainTest {
    private val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val sample = Sample(1_700_000_000L, 60, emptyList(), null, null, null)
    private val raw = RawRecord(1_700_000_000L, byteArrayOf(0x2F, 1))

    @Test
    fun endDoesNotReturnUntilTheCommitReturns() = runTest {
        val sink = RecordingSink()
        val gate = CompletableDeferred<Unit>()
        sink.gate = gate
        val drain = open(sink)
        drain.onRecord(sample, raw)
        var result: EndResult? = null
        val job = launch { result = drain.onEnd(token) }
        runCurrent()
        assertEquals(null, result)
        assertTrue(sink.batches.isEmpty())
        gate.complete(Unit)
        job.join()
        val ack = result
        assertTrue(ack is EndResult.Ack)
        assertEquals(1, (ack as EndResult.Ack).committed)
        assertTrue(ack.token.contentEquals(token))
        assertEquals(1, sink.batches.size)
        assertEquals(listOf(sample), sink.batches.single().first)
    }

    @Test
    fun duplicateEndAfterTheAckLandedDoesNotCommitAgain() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        drain.onRecord(sample, raw)
        assertTrue(drain.onEnd(token) is EndResult.Ack)
        drain.ackLanded()
        assertTrue(drain.onEnd(token.copyOf()) is EndResult.Duplicate)
        assertEquals(1, sink.batches.size)
    }

    @Test
    fun duplicateEndBeforeTheAckLandedAsksToSendItAgain() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        drain.onRecord(sample, raw)
        assertTrue(drain.onEnd(token) is EndResult.Ack)
        val again = drain.onEnd(token)
        assertTrue(again is EndResult.AckAgain)
        assertTrue((again as EndResult.AckAgain).token.contentEquals(token))
        assertEquals(1, sink.batches.size)
    }

    @Test
    fun stallWhileWaitingForTheStartDoesNotCommit() = runTest {
        val sink = RecordingSink()
        val drain = HistoryDrain(sink)
        drain.begin()
        assertTrue(drain.onStall() is EndResult.Retry)
        assertTrue(sink.batches.isEmpty())
        drain.onStart()
        assertTrue(drain.onStall() is EndResult.Ignored)
    }

    @Test
    fun recordsBeforeTheStartAreDropped() = runTest {
        val sink = RecordingSink()
        val drain = HistoryDrain(sink)
        drain.begin()
        drain.onRecord(sample, raw)
        val ack = drain.onEnd(token)
        assertTrue(ack is EndResult.Ack)
        assertEquals(0, (ack as EndResult.Ack).committed)
        assertTrue(sink.batches.single().second.isEmpty())
    }

    @Test
    fun anEndMarkerForADroppedBatchIsIgnoredUntilTheNextStart() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        drain.onRecord(sample, raw)
        assertTrue(drain.onIdle() is EndResult.Retry)
        assertTrue(drain.onEnd(token) is EndResult.Ignored)
        assertTrue(sink.batches.isEmpty())
        drain.onStart()
        drain.onRecord(sample, raw)
        assertTrue(drain.onEnd(token) is EndResult.Ack)
        assertEquals(1, sink.batches.size)
    }

    @Test
    fun disconnectDropsTheOpenBatchAndALaterBatchCommits() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        drain.onRecord(sample, raw)
        drain.onDisconnect()
        assertTrue(sink.batches.isEmpty())
        drain.begin()
        drain.onStart()
        drain.onRecord(sample, raw)
        assertTrue(drain.onEnd(token) is EndResult.Ack)
        assertEquals(1, sink.batches.size)
    }

    @Test
    fun aFullMinuteIsCommittedBeforeTheEndAndTheEndCountsOnlyTheRest() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        repeat(60) { index ->
            drain.onRecord(sample.copy(epochSecond = sample.epochSecond + index), raw)
        }
        assertEquals(60, drain.flush())
        assertEquals(1, sink.batches.size)
        assertEquals(60, sink.batches.single().first.size)
        repeat(5) { index ->
            drain.onRecord(sample.copy(epochSecond = sample.epochSecond + 60 + index), raw)
        }
        val ack = drain.onEnd(token)
        assertTrue(ack is EndResult.Ack)
        assertEquals(5, (ack as EndResult.Ack).committed)
        assertEquals(2, sink.batches.size)
    }

    @Test
    fun completeCarriesNoToken() = runTest {
        val sink = RecordingSink()
        val drain = open(sink)
        drain.onRecord(sample, raw)
        assertTrue(drain.onComplete() is EndResult.Completed)
        assertTrue(sink.batches.isEmpty())
        assertTrue(drain.onEnd(token) is EndResult.Ignored)
    }

    private fun open(sink: RecordingSink): HistoryDrain {
        val drain = HistoryDrain(sink)
        drain.begin()
        drain.onStart()
        return drain
    }
}
