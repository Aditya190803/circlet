package app.circlet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandTest {
    @Test
    fun sessionInitMatchesTheCapturedHandshake() {
        val expected = listOf(
            "aa0800a823002300ada86a2d",
            "aa0800a823014c00f2b5cdce",
            "aa0800a823022200824df537",
            "aa0800a823034301c54dd63d",
            "aa0800a823041600c7c25288",
        )
        val frames = Commands.sessionInit(0)
        assertEquals(5, frames.size)
        frames.forEachIndexed { i, frame ->
            assertEquals("seq $i", expected[i], hex(frame))
        }
    }

    @Test
    fun buildersMatchOpenStrapInners() {
        assertArrayEquals(byteArrayOf(0x23, 0x05, 0x23, 0x00), inner(Commands.getHello(5)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x1A, 0x00), inner(Commands.getBattery(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x06, 0x22, 0x00), inner(Commands.getDataRange(6)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x16, 0x00), inner(Commands.sendHistoricalData(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x14, 0x00), inner(Commands.abortHistorical(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x03, 0x01), inner(Commands.toggleRealtimeHr(1, true)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x03, 0x00), inner(Commands.toggleRealtimeHr(1, false)))
        assertArrayEquals(byteArrayOf(0x23, 0x02, 0x0E, 0x01), inner(Commands.toggleGenericHrProfile(2, true)))
        assertArrayEquals(byteArrayOf(0x23, 0x02, 0x0E, 0x00), inner(Commands.toggleGenericHrProfile(2, false)))
        assertArrayEquals(
            byteArrayOf(0x23, 0x01, 0x4F, Haptics.SHORT_PULSE.toByte(), 0, 0, 0, 0),
            inner(Commands.runHaptics(1, Haptics.SHORT_PULSE)),
        )
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x7A, 0x01), inner(Commands.stopHaptics(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x43, 0x01), inner(Commands.getAlarm(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x44, 0x01), inner(Commands.runAlarm(1)))
        assertArrayEquals(byteArrayOf(0x23, 0x03, 0x45, 0x01), inner(Commands.disableAlarm(3)))
        assertArrayEquals(byteArrayOf(0x23, 0x01, 0x4C, 0x00), inner(Commands.getAdvertisingName(1)))
    }

    @Test
    fun setClockIsEpochThenFourZeroes() {
        val body = inner(Commands.setClock(0x09, 0x12345678))
        assertArrayEquals(
            byteArrayOf(
                0x23, 0x09, 0x0A,
                0x78, 0x56, 0x34, 0x12,
                0x00, 0x00, 0x00, 0x00,
                0x00,
            ),
            body,
        )
    }

    @Test
    fun setAlarmIsTheRev1WholeSecondForm() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x30, 0xD5.toByte(), 0x35, 0x6A, 0x00, 0x00, 0x00, 0x00),
            inner(Commands.setAlarm(1, 1781912880)).copyOfRange(3, 12),
        )
    }

    @Test
    fun historyAckEchoesTheEndToken() {
        val token = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x11, 0x22, 0x33, 0x44)
        val marker = ByteArray(21)
        marker[0] = 0x31
        marker[1] = 7
        marker[2] = 2
        token.copyInto(marker, 13)
        val end = Frames.wrap(marker)
        assertArrayEquals(token, end.copyOfRange(17, 25))
        val parsed = Decoder.decode(FrameReassembler().feed(end).single()) as Packet.Metadata
        assertEquals(MetadataKind.END, parsed.kind)
        assertArrayEquals(token, parsed.token)
        val ack = inner(Commands.historyAck(5, parsed.token!!))
        val expect = byteArrayOf(0x23, 5, 0x17, 0x01) + token
        assertArrayEquals(expect, ack.copyOfRange(0, 12))
    }

    @Test
    fun historyTokenMustBeEightBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            Commands.historyAck(5, byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun hapticPatternMustFitAByte() {
        assertThrows(IllegalArgumentException::class.java) { Commands.runHaptics(0, 256) }
        assertThrows(IllegalArgumentException::class.java) { Commands.runHaptics(0, -1) }
    }

    @Test
    fun advertisingNameIsLengthPrefixed() {
        val body = inner(Commands.setAdvertisingName(4, "WHOOP"))
        assertEquals(0x4D, body[2].toInt() and 0xFF)
        assertEquals(0x01, body[3].toInt() and 0xFF)
        assertEquals(5, body[4].toInt() and 0xFF)
        assertEquals("WHOOP", String(body, 5, 5))
    }

    @Test
    fun advertisingNameRejectsEmptyAndNonAscii() {
        assertThrows(IllegalArgumentException::class.java) { Commands.setAdvertisingName(1, "") }
        assertThrows(IllegalArgumentException::class.java) { Commands.setAdvertisingName(1, "a".repeat(21)) }
        assertThrows(IllegalArgumentException::class.java) { Commands.setAdvertisingName(1, "naïve") }
    }

    @Test
    fun noHarmfulCommandIsBuilt() {
        val names = Commands::class.java.declaredMethods.map { it.name }.toSet()
        val banned = setOf(
            "forceTrim",
            "reboot",
            "rebootStrap",
            "powerCycle",
            "powerCycleStrap",
            "lockDevice",
            "enterBleDfu",
            "enterDfu",
            "togglePersistentR20",
            "togglePersistentR21",
            "loadFirmware",
            "startFirmware",
        )
        assertEquals(emptySet<String>(), names.intersect(banned))
    }
}

private fun inner(frame: ByteArray): ByteArray = FrameReassembler().feed(frame).single()
