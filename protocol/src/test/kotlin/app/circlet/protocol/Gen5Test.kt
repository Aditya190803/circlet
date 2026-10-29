// Ported from OpenStrap protocol/test/gen5_test.dart, gen5_historical_test.dart,
// gen5_command_surface_test.dart and gen5_hello_maverick_test.dart — golden vectors.
package app.circlet.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class Gen5Test {
    private val profile = BandProfile.Gen5
    private val commands = BandCommands(profile)

    @Test fun crcAndCanonicalHelloAndAck() {
        assertEquals(0xFFFF, crc16Modbus(byteArrayOf()))
        assertEquals(0x71E6, crc16Modbus(bytes("aa0108000001")))
        assertArrayEquals(bytes("aa0108000001e67123019101363e5c8d"), commands.getHello(1))
        assertArrayEquals(bytes("aa0110000001e0d12300170141b6010010000000667da4fb"),
            commands.historyAck(0, bytes("41b6010010000000")))
    }

    @Test fun uuidsAndLegacyDefault() {
        assertEquals(profile, BandProfile.fromService(UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")))
        assertNull(BandProfile.fromService(UUID.randomUUID()))
        assertEquals("fd4b0002-cce1-4033-93ce-002d5875f58a", profile.commandTo.toString())
        assertEquals("fd4b0003-cce1-4033-93ce-002d5875f58a", profile.commandFrom.toString())
        assertEquals("fd4b0004-cce1-4033-93ce-002d5875f58a", profile.events.toString())
        assertEquals("fd4b0005-cce1-4033-93ce-002d5875f58a", profile.data.toString())
        val inner = bytes("23010b")
        assertArrayEquals(Frames.wrap(inner), Frames.wrap(inner, BandProfile.Gen4))
    }

    @Test fun realInboundFramesReassembleAtEverySplit() {
        for (frame in listOf(fixture("v18"), fixture("v26"), bytes("aa011800010022e128029ea0266aae4762025b024b020000000001005ed515dc"))) {
            assertEquals(1, frame[4].toInt())
            assertEquals(0, frame[5].toInt())
            for (cut in 1 until frame.size) {
                val reassembler = FrameReassembler(profile)
                assertTrue(reassembler.feed(frame.copyOfRange(0, cut)).isEmpty())
                assertEquals(1, reassembler.feed(frame.copyOfRange(cut, frame.size)).size)
            }
            assertEquals(2, FrameReassembler(profile).feed(frame + frame).size)
        }
    }

    @Test fun corruptionResynchronizesAndEmbeddedStartBytesSurvive() {
        val good = commands.historyAck(9, ByteArray(8) { 0xAA.toByte() })
        for (at in listOf(2, 6, 8, good.lastIndex)) {
            val corrupt = good.copyOf().apply { this[at] = (this[at].toInt() xor 4).toByte() }
            val reassembler = FrameReassembler(profile)
            assertEquals(1, reassembler.feed(corrupt + good).size)
            assertTrue(reassembler.resyncs > 0)
        }
    }

    @Test fun realV18MapsBiometricsAndSignedTemperature() {
        val raw = inner(fixture("v18"))
        val summary = decodeGen5Summary(raw)!!
        val record = summary.record
        assertEquals(25443699L, record.counter)
        assertEquals(1780916150L, record.epochSecond)
        assertEquals(102, record.heartRate)
        assertEquals(listOf(602, 613), record.rrIntervalsMs)
        assertEquals(-0.7252, record.gravity[0], 0.0001)
        assertEquals(0.4944, record.gravity[1], 0.0001)
        assertEquals(0.4969, record.gravity[2], 0.0001)
        assertEquals(3057, summary.skinTemperatureCentiC)
        assertEquals(0, record.skinTempCountRaw)
        assertEquals(0, summary.bandState)
        raw[65] = 0x9C.toByte(); raw[66] = 0xFF.toByte()
        assertEquals(-100, decodeGen5Summary(raw)!!.skinTemperatureCentiC)
        raw[65] = 0x78; raw[66] = 0xEC.toByte()
        assertNull(decodeGen5Summary(raw)!!.skinTemperatureCentiC)
    }

    @Test fun badIndividualFieldsDoNotDestroyTheSecond() {
        val raw = inner(fixture("v18"))
        raw[14] = 255.toByte()
        raw[28] = 0
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putFloat(37, Float.NaN)
        val summary = decodeGen5Summary(raw)!!
        assertEquals(0, summary.record.heartRate)
        assertEquals(listOf(602, 613), summary.record.rrIntervalsMs)
        assertTrue(summary.record.gravity.isEmpty())
        assertEquals(3057, summary.skinTemperatureCentiC)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putFloat(37, 3f)
        assertEquals(3.0, decodeGen5Summary(raw)!!.record.gravity[0], 0.0)
        raw[15] = 5
        assertTrue(decodeGen5Summary(raw)!!.record.rrIntervalsMs.isEmpty())
    }

    @Test fun readableTailFloorAndUnknownVersions() {
        val raw = inner(fixture("v18"))
        assertNotNull(decodeGen5Summary(raw.copyOf(109)))
        assertNull(decodeGen5Summary(raw.copyOf(108)))
        for (version in listOf(20, 21, 22, 26)) {
            assertNull(decodeGen5Summary(raw.copyOf().apply { this[1] = version.toByte() }))
        }
        assertNull(decodeGen5Summary(inner(fixture("v26"))))
        assertEquals(102, (Decoder.decode(raw, profile) as Packet.Historical).record!!.heartRate)
        assertNull((Decoder.decode(inner(fixture("v26")), profile) as Packet.Historical).record)
    }

    @Test fun alarmBuzzNameAndInitBodies() {
        assertArrayEquals(bytes("2301430401000000"), inner(commands.getAlarm(1)))
        assertArrayEquals(bytes("23014502ff000000"), inner(commands.disableAlarm(1)))
        val buzz = inner(commands.runAlarm(1))
        assertEquals(0x13, u8(buzz, 2))
        assertArrayEquals(bytes("012f98000000000000000001"), buzz.copyOfRange(3, 15))
        assertArrayEquals(buzz, inner(commands.runHaptics(1, 2)))
        val alarm = inner(commands.setAlarm(3, 1_780_916_150))
        assertEquals(24, alarm.size)
        assertArrayEquals(bytes("0401"), alarm.copyOfRange(3, 5))
        assertEquals(1_780_916_150, u32(alarm, 5).toInt())
        assertArrayEquals(bytes("2f980000000000000000071e00"), alarm.copyOfRange(11, 24))
        assertEquals(0x8D, u8(inner(commands.getAdvertisingName(1)), 2))
        assertEquals(1, u8(inner(commands.getAdvertisingName(1)), 3))
        assertEquals(0x8C, u8(inner(commands.setAdvertisingName(1, "Circlet")), 2))
        assertEquals(listOf(0x22, 0x16), commands.sessionInit(0).map { u8(inner(it), 2) })
        assertArrayEquals(bytes("2301170000000000"), inner(commands.historyFailure(1)))
    }

    @Test fun helloAndClockUseTheirDeclaredOffsets() {
        val body = ByteArray(104)
        val view = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        body[0] = 1
        view.putInt(1, 900); view.putInt(6, 1787822694); view.putInt(10, 16384)
        "5AM0000000".toByteArray().copyInto(body, 14)
        body[91] = 50; body[92] = 41; body[93] = 1; body[102] = 1
        val hello = gen5Hello(body)!!
        assertTrue(hello.identityValid)
        assertEquals("50.41.1.0", hello.firmware)
        assertEquals(90.0, hello.batteryPercent!!, 0.0)
        assertEquals(1787822694500L, hello.clockMillis)
        assertTrue(hello.onWrist)
        assertNull(gen5Hello(body.copyOf(103)))
        view.putInt(1, 10000)
        assertNull(gen5Hello(body)!!.batteryPercent)
        assertEquals(0x66554433L, clockEpoch(0x0B, bytes("33445566")))
        assertEquals(0x66554433L, clockEpoch(0x93, bytes("01334455660000")))
        assertNull(clockEpoch(0x93, bytes("02334455660000")))
    }

    @Test fun clockFractionsAndMetadataCount() {
        val clock = inner(commands.setClock(2, 1787822694, 16384))
        assertEquals(0x0A, u8(clock, 2))
        assertEquals(1787822694L, u32(clock, 3))
        assertEquals(16384L, u32(clock, 7))
        assertTrue(runCatching { commands.setClock(2, 0, 32768) }.isFailure)
        val meta = ByteArray(24).apply { this[0] = 0x31; this[2] = 2 }
        ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN).putInt(9, 1000)
        val decoded = Decoder.decode(meta, profile) as Packet.Metadata
        assertEquals(MetadataKind.END, decoded.kind)
        assertEquals(1000L, decoded.expectedPacketCount)
        assertEquals(decoded, Packet.Metadata(MetadataKind.END, decoded.token, 1000))
        assertNotEquals(decoded, Packet.Metadata(MetadataKind.END, decoded.token, 999))
    }

    @Test fun revisionThreeLockExtension() {
        val body = ByteArray(111).apply { this[0] = 3; this[102] = 1; this[103] = 0x40 }
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(107, 1234)
        val locked = gen5Hello(body)!!
        assertEquals(true, locked.locked)
        assertEquals(1234L, locked.lockCounterSeconds)
        assertTrue(locked.onWrist)
        assertNull(gen5Hello(body.copyOf(106))!!.locked)
        body[0] = 1
        assertNull(gen5Hello(body)!!.locked)
        body[0] = 3; body[103] = 0
        assertEquals(false, gen5Hello(body)!!.locked)
    }

    private fun inner(frame: ByteArray) = FrameReassembler(profile).feed(frame).single()
    private fun fixture(name: String) = bytes(javaClass.getResource("/gen5/$name.hex")!!.readText().trim())
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
