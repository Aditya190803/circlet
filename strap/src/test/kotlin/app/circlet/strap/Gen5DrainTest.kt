// Ported from OpenStrap edge/lib/ble/ble_engine.dart — burstPacketCountMatches and validation limit.
package app.circlet.strap

import app.circlet.core.RawRecord
import app.circlet.core.StrapGeneration
import app.circlet.strap.support.RecordingSink
import app.circlet.strap.sync.EndResult
import app.circlet.strap.sync.HistoryDrain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Gen5DrainTest {
    private val token = ByteArray(8) { it.toByte() }
    private val raw = RawRecord(1787822694, byteArrayOf(0x2F, 18), StrapGeneration.GEN5)

    @Test fun gateIncludesNonHistoricalMembersAndDurabilityPrecedesAck() = runTest {
        val sink = RecordingSink()
        val drain = HistoryDrain(sink, StrapGeneration.GEN5)
        drain.begin()
        assertFalse(drain.onRecord(null, raw))
        drain.onStart()
        assertTrue(drain.onRecord(null, raw))
        drain.onTraffic()
        val gate = CompletableDeferred<Unit>()
        sink.gate = gate
        val end = async { drain.onEnd(token, 2) }
        assertTrue(sink.batches.isEmpty())
        gate.complete(Unit)
        assertTrue(end.await() is EndResult.Ack)
        assertTrue(drain.onEnd(token, 2) is EndResult.AckAgain)
        assertEquals(1, sink.batches.size)
    }

    @Test fun shortBurstGetsThreeStrictAttemptsThenTwoPacketSlack() = runTest {
        val sink = RecordingSink()
        val drain = HistoryDrain(sink, StrapGeneration.GEN5)
        drain.begin(); drain.onStart(); drain.onRecord(null, raw)
        repeat(3) {
            assertEquals(EndResult.Refuse, drain.onEnd(token, 3))
            drain.onTraffic()
        }
        assertTrue(sink.batches.isEmpty())
        assertTrue(drain.onEnd(token, 3) is EndResult.Ack)
    }

    @Test fun fifteenthFailureStopsAndSurplusPasses() = runTest {
        val drain = HistoryDrain(RecordingSink(), StrapGeneration.GEN5)
        drain.begin(); drain.onStart(); drain.onRecord(null, raw)
        repeat(14) { assertEquals(EndResult.Refuse, drain.onEnd(token, 10)) }
        assertEquals(EndResult.Stuck, drain.onEnd(token, 10))
        assertEquals(EndResult.Retry, drain.onComplete())
        drain.begin(); drain.onStart(); drain.onRecord(null, raw); drain.onTraffic()
        assertTrue(drain.onEnd(token, 1) is EndResult.Ack)
    }

    @Test fun earlyFlushDoesNotResetCountAndGen4KeepsAdvisoryCount() = runTest {
        for (generation in StrapGeneration.entries) {
            val sink = RecordingSink()
            val drain = HistoryDrain(sink, generation)
            drain.begin(); drain.onStart(); drain.onRecord(null, raw)
            drain.flush()
            val end = drain.onEnd(token, if (generation == StrapGeneration.GEN5) 1 else 100)
            assertTrue(end is EndResult.Ack)
            assertTrue(drain.onComplete() is EndResult.Completed)
        }
    }
}
