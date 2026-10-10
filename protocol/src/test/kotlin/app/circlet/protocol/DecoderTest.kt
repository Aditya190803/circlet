package app.circlet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderTest {
    @Test
    fun responseCarriesOriginAndBody() {
        val inner = byteArrayOf(0x24, 0x01, 0x1A, 0x07, 0x01, 0x03, 0x03)
        val packet = Decoder.decode(inner) as Packet.Response
        assertEquals(0x1A, packet.opcode)
        assertEquals(7, packet.originSeq)
        assertEquals(1, packet.result)
        assertEquals(77.1, batteryPercent(packet.body)!!, 0.0)
    }

    @Test
    fun realtimeReadsHeartRateAndIntervals() {
        val inner = ByteArray(20)
        inner[0] = 0x28
        putU32(inner, 2, 1_750_000_000L)
        inner[8] = 72
        inner[9] = 2
        inner[10] = 0xE8.toByte()
        inner[11] = 0x03
        inner[12] = 0xB0.toByte()
        inner[13] = 0x04
        val packet = Decoder.decode(inner) as Packet.Realtime
        assertEquals(1_750_000_000L, packet.epochSecond)
        assertEquals(72, packet.heartRate)
        assertEquals(listOf(1000, 1200), packet.rrMs)
    }

    @Test
    fun eventUsesTheNamedIdAndLengthBoundedPayload() {
        val inner = byteArrayOf(
            0x30, 0x00,
            0x09, 0x00,
            0x78, 0x56, 0x34, 0x12,
            0x00, 0x40,
            0x02, 0x00,
            0x11, 0x22,
            0x33,
        )
        val event = Decoder.decode(inner) as Packet.Event
        assertEquals(StrapEventId.WRIST_ON, event.id)
        assertEquals(9, event.wireId)
        assertEquals(0x12345678L, event.epochSecond)
        assertArrayEquals(byteArrayOf(0x11, 0x22), event.payload)
    }

    @Test
    fun unknownEventKeepsItsWireId() {
        val inner = byteArrayOf(0x30, 0x00, 0x63, 0x00, 0x01, 0x00, 0x00, 0x00)
        val event = Decoder.decode(inner) as Packet.Event
        assertNull(event.id)
        assertEquals(99, event.wireId)
    }

    @Test
    fun metadataKinds() {
        assertEquals(MetadataKind.START, (Decoder.decode(byteArrayOf(0x31, 0, 1)) as Packet.Metadata).kind)
        assertNull((Decoder.decode(byteArrayOf(0x31, 0, 1)) as Packet.Metadata).token)
        assertEquals(MetadataKind.COMPLETE, (Decoder.decode(byteArrayOf(0x31, 0, 3)) as Packet.Metadata).kind)
        val end = ByteArray(21)
        end[0] = 0x31
        end[2] = 2
        end[13] = 9
        val meta = Decoder.decode(end) as Packet.Metadata
        assertEquals(MetadataKind.END, meta.kind)
        assertEquals(9, meta.token!![0].toInt() and 0xFF)
        assertTrue(Decoder.decode(byteArrayOf(0x31, 0, 9)) is Packet.Other)
    }

    @Test
    fun historicalKeepsRawWhenTheRecordIsTooShort() {
        val inner = byteArrayOf(0x2F, 24, 0, 0)
        val packet = Decoder.decode(inner) as Packet.Historical
        assertEquals(24, packet.version)
        assertNull(packet.record)
        assertArrayEquals(inner, packet.raw)
    }

    @Test
    fun otherTypesStayTyped() {
        assertEquals(0x32, (Decoder.decode(byteArrayOf(0x32, 1, 2)) as Packet.Other).type)
        assertNull(Decoder.decode(ByteArray(0)))
    }
}
