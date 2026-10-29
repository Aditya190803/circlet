// Ported from OpenStrap edge/lib/ble/ble_engine.dart — gen5 bootstrap and INIT;
// protocol/test/gen5_hello_maverick_test.dart — revision-1 HELLO fixture.
package app.circlet.strap

import app.circlet.core.*
import app.circlet.protocol.BandProfile
import app.circlet.protocol.Frames
import app.circlet.strap.link.*
import app.circlet.strap.support.*
import app.circlet.strap.wire.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class Gen5SessionTest {
    private val now = Instant.ofEpochSecond(1787822694)

    @Test fun helloPrecedesClockAndInitHasOnlyRangeAndHistory() = runTest {
        val wire = ProtocolWire()
        val transport = transport(wire, now.minusSeconds(10))
        val pauses = mutableListOf<Long>()
        val session = SessionBringUp(transport, GattOperationQueue(Duration.ofSeconds(2)), wire, Seq(), sleep = { pauses += it })
        assertTrue(session.run("AA", now, false, mutableListOf()) is Outcome.Ok)
        assertEquals(StrapGeneration.GEN5, wire.generation)
        assertEquals(listOf(0x91, 0x0A, 0x8D, 0x22, 0x16), opcodes(transport.writes))
        assertEquals(listOf(600L, 500L, 120L), pauses)
        assertTrue(opcodes(transport.writes).none { it in listOf(0x3F, 0x6B, 0x77, 0x78, 0x99, 0x9A) })
    }

    @Test fun inSyncHelloAvoidsClockWriteAndNameFailureIsAdvisory() = runTest {
        val wire = ProtocolWire()
        val transport = transport(wire, now, nameReply = false)
        val session = SessionBringUp(transport, GattOperationQueue(Duration.ofSeconds(2)), wire, Seq(), sleep = {})
        assertTrue(session.run("AA", now, false, mutableListOf()) is Outcome.Ok)
        assertEquals(listOf(0x91, 0x8D, 0x22, 0x16), opcodes(transport.writes))
    }

    @Test fun missingHelloAndUnansweredClockPreventHistory() = runTest {
        for (missingHello in listOf(true, false)) {
            val wire = ProtocolWire()
            val transport = FakeTransport().apply { strapGeneration = StrapGeneration.GEN5 }
            transport.onWrite = { frame ->
                if (!missingHello && opcodeOf(frame) == 0x91) reply(wire, frame, helloBody(now.minusSeconds(10)))
            }
            val session = SessionBringUp(transport, GattOperationQueue(Duration.ofSeconds(2)), wire, Seq(), sleep = {})
            assertTrue(session.run("AA", now, false, mutableListOf()) is Outcome.Err)
            assertTrue(opcodes(transport.writes).none { it == 0x16 })
        }
    }

    @Test fun exchangeMatchesOriginSequenceAndIgnoresDeferredReplies() = runTest {
        val wire = ProtocolWire(StrapGeneration.GEN5)
        val request = wire.hello(77)
        val result = async {
            wire.exchange(request) { frame ->
                reply(wire, frame, helloBody(now), origin = 78)
                reply(wire, frame, ByteArray(0), result = 2)
                reply(wire, frame, helloBody(now))
                true
            }
        }.await()
        assertEquals(now.toEpochMilli(), result!!.hello!!.clockMillis)
    }

    @Test fun bothGenerationsRejectDangerousFrames() {
        for (generation in StrapGeneration.entries) {
            val wire = ProtocolWire(generation)
            for (opcode in Harmful.OPCODES) {
                val frame = Frames.wrap(byteArrayOf(0x23, 1, opcode.toByte(), 0), wire.profile)
                assertThrows(IllegalStateException::class.java) { wire.vetted(frame) }
            }
        }
    }

    @Test fun secondHelloCannotCompleteTheNextCommandEvenWithReusedSequence() = runTest {
        val wire = ProtocolWire(StrapGeneration.GEN5)
        val hello = wire.hello(1)
        assertNotNull(wire.exchange(hello) { reply(wire, hello, helloBody(now)); true }!!.hello)
        val next = wire.exchange(wire.setClock(1, now.epochSecond)) { frame ->
            reply(wire, hello, helloBody(now.minusSeconds(20)))
            reply(wire, frame, byteArrayOf(1))
            true
        }
        assertEquals(1, next!!.result)
        assertNull(next.hello)
    }

    @Test fun lockedHelloStopsBeforeClockOrHistory() = runTest {
        val wire = ProtocolWire()
        val transport = FakeTransport().apply {
            strapGeneration = StrapGeneration.GEN5
            onWrite = { frame -> reply(wire, frame, helloBody(now).copyOf(111).apply {
                this[0] = 3; this[103] = 0x40
            }) }
        }
        val session = SessionBringUp(transport, GattOperationQueue(Duration.ofSeconds(2)), wire, Seq(), sleep = {})
        val outcome = session.run("AA", now, false, mutableListOf()) as Outcome.Err
        assertEquals(LOCKED_STRAP, (outcome.error as CoreError.Strap).error)
        assertEquals(listOf(0x91), opcodes(transport.writes))
    }

    private fun transport(wire: ProtocolWire, clock: Instant, nameReply: Boolean = true): FakeTransport =
        FakeTransport().apply {
            strapGeneration = StrapGeneration.GEN5
            onWrite = { frame ->
                when (opcodeOf(frame)) {
                    0x91 -> reply(wire, frame, helloBody(clock))
                    0x0A -> reply(wire, frame, byteArrayOf(1))
                    0x8D -> if (nameReply) reply(wire, frame, byteArrayOf(1, 0, 4, 87, 72, 79, 80))
                }
            }
        }

    private fun reply(wire: ProtocolWire, frame: ByteArray, body: ByteArray, result: Int = 1, origin: Int = innerOf(frame)[1].toInt() and 255) {
        val inner = byteArrayOf(0x24, 0, opcodeOf(frame).toByte(), origin.toByte(), result.toByte()) + body
        wire.feed(NotifyRole.COMMANDS, Frames.wrap(inner, BandProfile.Gen5))
    }

    private fun helloBody(clock: Instant): ByteArray = ByteArray(104).also { body ->
        body[0] = 1
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(1, 900).putInt(6, clock.epochSecond.toInt())
        "5AM0000000".toByteArray().copyInto(body, 14)
        body[91] = 50
        body[92] = 41
        body[93] = 1
    }
}
