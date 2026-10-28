// Ported from OpenStrap edge/lib/ble/ble_engine.dart — gen5 alarm pre-arm, live ownership and pairing.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.circlet.strap

import app.circlet.core.*
import app.circlet.protocol.BandProfile
import app.circlet.protocol.Frames
import app.circlet.strap.link.*
import app.circlet.strap.support.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Gen5LinkTest {
    @Test fun detectedGenerationPersistsAndAlarmClockSettlesBeforeArm() = runTest {
        val now = Instant.ofEpochSecond(1787822694)
        val time = ManualTimeSource(now, ZoneOffset.UTC)
        val prefs = MemoryPrefs()
        val transport = FakeTransport().apply { strapGeneration = StrapGeneration.GEN5 }
        val pauses = mutableListOf<Long>()
        val link = StrapLink(transport, GattOperationQueue(Duration.ofSeconds(2)),
            testPorts(RecordingSink(), prefs), time, backgroundScope, FakeHost(),
            sleep = { millis -> pauses += millis; kotlinx.coroutines.delay(millis) })
        transport.onWrite = { frame ->
            val op = opcodeOf(frame)
            if (op in listOf(0x91, 0x0A, 0x8D)) {
                val body = if (op == 0x91) ByteArray(104).also {
                    it[0] = 1
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(1, 900).putInt(6, now.epochSecond.toInt())
                    "5AM0000000".toByteArray().copyInto(it, 14)
                } else byteArrayOf(1)
                val response = byteArrayOf(0x24, 0, op.toByte(), innerOf(frame)[1], 1) + body
                transport.emit(NotifyRole.COMMANDS, Frames.wrap(response, BandProfile.Gen5))
            }
        }
        runCurrent()
        assertTrue(link.pair(FoundStrap("AA", "WHOOP", -50, StrapGeneration.GEN4)) is Outcome.Ok)
        assertEquals(StrapGeneration.GEN5, prefs.generation)
        assertEquals(StrapGeneration.GEN5, link.state.value.generation)
        val before = transport.writes.size
        assertTrue(link.arm(now.plusSeconds(3600)) is Outcome.Ok)
        assertEquals(listOf(0x0A, 0x42), opcodes(transport.writes.drop(before)))
        assertEquals(120L, pauses.last())
        link.onAlarmFired = { link.arm(now.plusSeconds(7200)); Unit }
        val beforeEvent = transport.writes.size
        transport.emit(app.circlet.strap.link.NotifyRole.EVENTS,
            Frames.wrap(byteArrayOf(0x30, 0, 57, 0), BandProfile.Gen5))
        advanceTimeBy(121)
        runCurrent()
        assertEquals(listOf(0x0A, 0x42), opcodes(transport.writes.drop(beforeEvent)))
        link.forget()
        assertNull(prefs.generation)
    }

    @Test fun workoutLiveHeartUsesOnlyTheSafeHrToggle() {
        val wire = app.circlet.strap.wire.ProtocolWire(StrapGeneration.GEN5)
        for (on in listOf(true, false)) {
            val inner = innerOf(wire.realtime(1, on))
            assertEquals(0x03, inner[2].toInt())
            assertEquals(if (on) 1 else 0, inner[3].toInt())
        }
        assertEquals(0x13, opcodeOf(wire.findBuzz(1)))
        assertEquals(0x13, opcodeOf(wire.testBuzz(1)))
    }

    @Test fun lockedSavedStrapShowsAnErrorAndStopsAutomaticRetries() = runTest {
        val now = Instant.ofEpochSecond(1787822694)
        val prefs = MemoryPrefs().apply { address = "AA"; generation = StrapGeneration.GEN5 }
        val transport = FakeTransport().apply { strapGeneration = StrapGeneration.GEN5 }
        val link = StrapLink(transport, GattOperationQueue(Duration.ofSeconds(2)),
            testPorts(RecordingSink(), prefs), ManualTimeSource(now, ZoneOffset.UTC), backgroundScope, FakeHost(),
            sleep = { kotlinx.coroutines.delay(it) })
        transport.onWrite = { frame ->
            val body = ByteArray(111).apply {
                this[0] = 3; this[103] = 0x40
                "5AM0000000".toByteArray().copyInto(this, 14)
            }
            val response = byteArrayOf(0x24, 0, 0x91.toByte(), innerOf(frame)[1], 1) + body
            transport.emit(NotifyRole.COMMANDS, Frames.wrap(response, BandProfile.Gen5))
        }
        runCurrent()
        link.start()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(LinkState.DISCONNECTED, link.state.value.link)
        assertEquals(LOCKED_STRAP, link.state.value.error)
        assertEquals(1, transport.connects.size)
        assertEquals(listOf(0x91), opcodes(transport.writes))
        assertTrue(link.syncNow() is Outcome.Err)
        transport.down()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, transport.connects.size)
        assertTrue(prefs.address == "AA")
        assertEquals(0, transport.bondCalls)
    }
}
