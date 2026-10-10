@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.circlet.strap

import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.LinkState
import app.circlet.core.Outcome
import app.circlet.core.StrapError
import app.circlet.strap.link.GattOperationQueue
import app.circlet.strap.link.NotifyRole
import app.circlet.strap.link.StrapLink
import app.circlet.strap.support.FakeHost
import app.circlet.strap.support.FakeTransport
import app.circlet.strap.support.MemoryPrefs
import app.circlet.strap.support.RecordingSink
import app.circlet.strap.support.innerOf
import app.circlet.strap.support.metaFrame
import app.circlet.strap.support.opcodeOf
import app.circlet.strap.support.recordFrame
import app.circlet.strap.support.testPorts
import app.circlet.core.ManualTimeSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrapLinkTest {
    private val strap = FoundStrap("AA:BB:CC:DD:EE:01", "WHOOP 4.0", -55)
    private val token = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

    @Test
    fun pairWritesTheClockBeforeItDrains() = runTest {
        val rig = rig()
        val outcome = rig.link.pair(strap)
        assertTrue(outcome is Outcome.Ok)
        assertEquals(LinkState.CONNECTED, rig.link.state.value.link)
        val codes = rig.transport.writes.map { opcodeOf(it) }
        assertTrue(codes.indexOf(0x0A) < codes.indexOf(0x16))
        assertEquals(listOf(false), rig.transport.connects)
        assertEquals(strap.address to true, rig.host.paired.single())
    }

    @Test
    fun anEndMarkerIsCommittedBeforeItIsAcknowledged() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.sink.gate = gate
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(0x2F, rig.sink.batches.single().second.single().bytes[0].toInt() and 0xFF)
        val ack = innerOf(acks(rig.transport).single())
        assertEquals(0x17, ack[2].toInt() and 0xFF)
        assertEquals(0x01, ack[3].toInt() and 0xFF)
        assertTrue(ack.copyOfRange(4, 12).contentEquals(token))
        assertEquals(rig.time.now(), rig.prefs.synced)
    }

    @Test
    fun aDuplicateEndMarkerDoesNotCommitTwice() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        rig.transport.emit(NotifyRole.DATA, metaFrame(2, token))
        runCurrent()
        assertEquals(1, rig.sink.batches.size)
        assertEquals(1, acks(rig.transport).size)
    }

    @Test
    fun aCompleteMarkerIsNotAcknowledged() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, metaFrame(3))
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(false, rig.link.state.value.sync.draining)
        assertEquals(rig.time.now(), rig.prefs.synced)
    }

    @Test
    fun aDropMidBatchDoesNotCommitAndTheNextConnectIsBackground() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        rig.transport.emit(NotifyRole.DATA, metaFrame(1))
        rig.transport.emit(NotifyRole.DATA, recordFrame())
        runCurrent()
        rig.transport.down()
        runCurrent()
        assertTrue(rig.sink.batches.isEmpty())
        assertTrue(acks(rig.transport).isEmpty())
        assertEquals(listOf(false, true), rig.transport.connects)
        assertEquals(LinkState.RECONNECTING, rig.link.state.value.link)
    }

    @Test
    fun silenceBeforeTheFirstStartRetriesTheDrain() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        val historical = rig.transport.writes.count { opcodeOf(it) == 0x16 }
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(historical, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertEquals(1, rig.transport.writes.count { opcodeOf(it) == 0x14 })
        advanceTimeBy(300)
        runCurrent()
        assertEquals(historical + 1, rig.transport.writes.count { opcodeOf(it) == 0x16 })
        assertTrue(rig.sink.batches.isEmpty())
    }

    @Test
    fun liveHeartRateTurnsOffWhenNobodyNeedsIt() = runTest {
        val rig = rig()
        rig.link.pair(strap)
        runCurrent()
        // A new session forces live heart rate off when nothing has asked for it.
        assertEquals(listOf(0x00), realtimeBodies(rig))
        assertTrue(rig.link.setLiveHeartRate(true) is Outcome.Ok)
        runCurrent()
        assertTrue(rig.link.setLiveHeartRate(false) is Outcome.Ok)
        runCurrent()
        assertEquals(listOf(0x00, 0x01, 0x00), realtimeBodies(rig))
    }

    @Test
    fun scanPublishesHitsAndRejectsARadioThatIsOff() = runTest {
        val rig = rig()
        runCurrent()
        assertTrue(rig.link.startScan() is Outcome.Ok)
        rig.transport.hit(strap)
        runCurrent()
        assertEquals(listOf(strap), rig.link.scanResults.value)
        assertTrue(rig.link.scanning.value)
        rig.transport.bluetooth = false
        val denied = rig.link.startScan()
        assertTrue(denied is Outcome.Err)
        assertEquals(StrapError.BluetoothOff, ((denied as Outcome.Err).error as CoreError.Strap).error)
    }

    @Test
    fun pairWithoutPermissionDoesNotConnect() = runTest {
        val rig = rig()
        rig.transport.connectPermission = false
        val outcome = rig.link.pair(strap)
        assertTrue(outcome is Outcome.Err)
        assertEquals(StrapError.PermissionMissing, ((outcome as Outcome.Err).error as CoreError.Strap).error)
        assertTrue(rig.transport.connects.isEmpty())
        assertNull(rig.prefs.address)
    }

    private fun TestScope.rig(): Rig {
        val time = ManualTimeSource(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("Pacific/Auckland"))
        val transport = FakeTransport()
        val sink = RecordingSink()
        val prefs = MemoryPrefs()
        val host = FakeHost()
        val link = StrapLink(
            transport = transport,
            queue = GattOperationQueue(Duration.ofSeconds(8)),
            ports = testPorts(sink, prefs),
            time = time,
            scope = backgroundScope,
            host = host,
        )
        return Rig(link, transport, sink, prefs, host, time)
    }

    private fun acks(transport: FakeTransport): List<ByteArray> =
        transport.writes.filter { opcodeOf(it) == 0x17 }

    private fun realtimeBodies(rig: Rig): List<Int> =
        rig.transport.writes.filter { opcodeOf(it) == 0x03 }.map { innerOf(it)[3].toInt() and 0xFF }

    private class Rig(
        val link: StrapLink,
        val transport: FakeTransport,
        val sink: RecordingSink,
        val prefs: MemoryPrefs,
        val host: FakeHost,
        val time: ManualTimeSource,
    )
}
