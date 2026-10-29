package app.circlet.strap

import app.circlet.core.CoreError
import app.circlet.core.Outcome
import app.circlet.core.StrapError
import app.circlet.strap.link.BondStatus
import app.circlet.strap.link.ConnectStatus
import app.circlet.strap.link.GATT_HELD_BY_OTHER
import app.circlet.strap.link.GattOperationQueue
import app.circlet.strap.link.Seq
import app.circlet.strap.link.SessionBringUp
import app.circlet.strap.support.FakeTransport
import app.circlet.strap.support.innerOf
import app.circlet.strap.support.opcodeOf
import app.circlet.strap.support.opcodes
import app.circlet.strap.support.u32le
import app.circlet.strap.wire.ProtocolWire
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBringUpTest {
    private val now = Instant.ofEpochSecond(1_760_227_200L)

    @Test
    fun opensInOpenStrapOrderAndSetsTheClockBeforeHistory() = runTest {
        val transport = FakeTransport()
        val trace = mutableListOf<String>()
        var armed = false
        val outcome = session(transport).run(ADDRESS, now, autoConnect = false, trace) {
            armed = true
            assertTrue(opcodes(transport.writes).none { it == 0x16 })
            assertEquals(0x43, opcodeOf(transport.writes.last()))
        }
        assertTrue(outcome is Outcome.Ok)
        assertTrue(armed)
        assertEquals(
            listOf(
                "connect", "bond", "mtu", "discover",
                "subscribe-commands", "subscribe-events", "subscribe-data",
                "set-clock", "get-hello", "get-battery", "get-advertising-name",
                "get-data-range", "get-alarm", "send-historical",
            ),
            trace,
        )
        assertEquals(listOf(0x0A, 0x23, 0x1A, 0x4C, 0x22, 0x43, 0x16), opcodes(transport.writes))
        val clock = innerOf(transport.writes.first())
        assertEquals(0xA0, clock[1].toInt() and 0xFF)
        assertEquals(now.epochSecond, u32le(clock, 3))
        assertEquals(0, transport.bondCalls)
        assertEquals(listOf(false), transport.connects)
    }

    @Test
    fun bondsWhenTheStrapIsNotBonded() = runTest {
        val transport = FakeTransport().apply { alreadyBonded = false }
        val outcome = session(transport).run(ADDRESS, now, autoConnect = true, mutableListOf())
        assertTrue(outcome is Outcome.Ok)
        assertEquals(1, transport.bondCalls)
        assertEquals(listOf(true), transport.connects)
    }

    @Test
    fun bondFailureStopsBeforeTheMtu() = runTest {
        val transport = FakeTransport().apply {
            alreadyBonded = false
            bondStatus = BondStatus.FAILED
        }
        val trace = mutableListOf<String>()
        val outcome = session(transport).run(ADDRESS, now, false, trace)
        assertEquals(StrapError.BondFailed, strapError(outcome))
        assertEquals(listOf("connect", "bond"), trace)
        assertTrue(transport.writes.isEmpty())
    }

    @Test
    fun bondHeldByAnotherAppIsRejected() = runTest {
        val transport = FakeTransport().apply {
            alreadyBonded = false
            bondStatus = BondStatus.HELD
        }
        val outcome = session(transport).run(ADDRESS, now, false, mutableListOf())
        val error = strapError(outcome)
        assertTrue(error is StrapError.Rejected)
        assertEquals("The strap is connected to another app", (error as StrapError.Rejected).detail)
    }

    @Test
    fun bluetoothOffAndMissingPermissionAreTyped() = runTest {
        val off = FakeTransport().apply { connectStatus = ConnectStatus.Off }
        assertEquals(StrapError.BluetoothOff, strapError(session(off).run(ADDRESS, now, false, mutableListOf())))
        val denied = FakeTransport().apply { connectStatus = ConnectStatus.Denied }
        assertEquals(
            StrapError.PermissionMissing,
            strapError(session(denied).run(ADDRESS, now, false, mutableListOf())),
        )
    }

    @Test
    fun gattStatus133IsHeldByAnotherApp() = runTest {
        val transport = FakeTransport().apply { connectStatus = ConnectStatus.Down(GATT_HELD_BY_OTHER) }
        val error = strapError(session(transport).run(ADDRESS, now, false, mutableListOf()))
        assertTrue(error is StrapError.Rejected)
        assertEquals("The strap is connected to another app", (error as StrapError.Rejected).detail)
    }

    @Test
    fun otherConnectFailureIsLinkLost() = runTest {
        val transport = FakeTransport().apply { connectStatus = ConnectStatus.Down(8) }
        assertEquals(StrapError.LinkLost, strapError(session(transport).run(ADDRESS, now, false, mutableListOf())))
    }

    @Test
    fun missingWhoopServiceIsRejected() = runTest {
        val transport = FakeTransport().apply { discoverValue = false }
        val error = strapError(session(transport).run(ADDRESS, now, false, mutableListOf()))
        assertTrue(error is StrapError.Rejected)
        assertEquals("This device is not a WHOOP 4.0 or 5.0 / MG", (error as StrapError.Rejected).detail)
    }

    @Test
    fun discoverTimeoutIsTimedOut() = runTest {
        val transport = FakeTransport().apply { discoverDelayMs = 10_000 }
        val queue = GattOperationQueue(Duration.ofMillis(50))
        val outcome = SessionBringUp(transport, queue, ProtocolWire(), Seq(), gap = Duration.ZERO)
            .run(ADDRESS, now, false, mutableListOf())
        assertEquals(StrapError.TimedOut, strapError(outcome))
    }

    @Test
    fun mtuFailureStillOpensTheSession() = runTest {
        val transport = FakeTransport().apply { mtuFails = true }
        val outcome = session(transport).run(ADDRESS, now, false, mutableListOf())
        assertTrue(outcome is Outcome.Ok)
        assertEquals(0x16, opcodeOf(transport.writes.last()))
    }

    private fun session(transport: FakeTransport) = SessionBringUp(
        transport = transport,
        queue = GattOperationQueue(Duration.ofSeconds(2)),
        wire = ProtocolWire(),
        seq = Seq(),
        gap = Duration.ZERO,
    )

    private fun strapError(outcome: Outcome<Unit>): StrapError? =
        ((outcome as? Outcome.Err)?.error as? CoreError.Strap)?.error

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
