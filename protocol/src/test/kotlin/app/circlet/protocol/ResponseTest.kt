package app.circlet.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseTest {
    @Test
    fun helloFixtures() {
        val bodies = listOf(
            "a001048303000000b196e201b0020000344332323438303932003865323738326237" +
                "346634303238346333663437363138623062613234373663356431366363303533" +
                "313862373532316431353635650600000002000000100000002900000011000000" +
                "060000000000000008060000000000000011000000020000000200000000000000",
            "0001048303000000b196e20150460000344332323438303932003865323738326237" +
                "346634303238346333663437363138623062613234373663356431366363303533" +
                "313862373532316431353635650600000002000000100000002900000011000000" +
                "060000000000000008060000000000000011000000020000000200000000000000",
            "a00104c503000000ef77256a087d0000344332323438303932003865323738326237" +
                "346634303238346333663437363138623062613234373663356431366363303533" +
                "313862373532316431353635650600000002000000100000002900000011000000" +
                "060000000000000008060000000000000011000000020000000200000000000000",
        )
        val batteries = listOf(89.9, 89.9, 96.5)
        bodies.forEachIndexed { i, hex ->
            val hello = hello(fromHex(hex).copyOfRange(2, fromHex(hex).size))
            assertEquals("4C2248092", hello.serial)
            assertNull(hello.charging)
            assertNull(hello.wristOn)
            assertEquals(batteries[i], hello.batteryPercent!!, 0.0)
            assertEquals(true, hello.firmware!!.startsWith("8e2782b7"))
        }
    }

    @Test
    fun shortHelloHasNoSerial() {
        val payload = fromHex(
            "0291021f038d025e01000b010c020c0100000000160001510c000000000000",
        )
        val hello = hello(payload.copyOfRange(2, payload.size))
        assertNull(hello.serial)
    }

    @Test
    fun batteryIsTheTenthsWord() {
        assertEquals(77.1, batteryPercent(byteArrayOf(0x03, 0x03))!!, 0.0)
        assertEquals(0.0, batteryPercent(byteArrayOf(0x00, 0x00))!!, 0.0)
        assertEquals(100.0, batteryPercent(byteArrayOf(0xE8.toByte(), 0x03))!!, 0.0)
        assertNull(batteryPercent(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        assertNull(batteryPercent(byteArrayOf(0x01)))
    }

    @Test
    fun advertisingNames() {
        assertEquals(
            "Abdul's WHOOP",
            advertisingName(fromHex("01010e416264756c27732057484f4f50000000")),
        )
        assertEquals("PROBE", advertisingName(fromHex("01010550524f4245fffe2a3f")))
        assertEquals("ABCDE", advertisingName(fromHex("0101064142ff43444500")))
    }

    @Test
    fun alarmForms() {
        val simple = alarmTime(byteArrayOf(0x01, 0x78, 0x56, 0x34, 0x12, 0x00, 0x00))
        assertEquals(0x12345678L, simple!!.epochSeconds)
        assertNull(simple.active)
        val rich = alarmTime(byteArrayOf(0x04, 0x01, 0x78, 0x56, 0x34, 0x12))
        assertEquals(0x12345678L, rich!!.epochSeconds)
        assertEquals(true, rich.active)
        assertEquals(false, alarmTime(byteArrayOf(0x04, 0x00, 0x78, 0x56, 0x34, 0x12))!!.active)
        assertNull(alarmTime(byteArrayOf(0x09, 0x01, 0x02, 0x03, 0x04, 0x05)))
    }

    @Test
    fun dataRangeReadsTheFixedOffsets() {
        val body = ByteArray(65)
        body[0] = 1
        putU32(body, 9, 40)
        putU32(body, 13, 7)
        putU32(body, 17, 2)
        putU32(body, 21, 100)
        putU32(body, 25, 12)
        putU32(body, 29, 3)
        putU32(body, 33, 1_750_000_000L)
        putU32(body, 57, 1_760_000_000L)
        val range = dataRange(body)!!
        assertEquals(1_750_000_000L, range.oldestEpochSeconds)
        assertEquals(1_760_000_000L, range.newestEpochSeconds)
        assertEquals(40L, range.backlog!!.writtenPage)
        assertEquals(100L, range.backlog!!.capacityPages)
        assertEquals(12L, range.backlog!!.usedRecords)
        assertEquals(7L, range.backlog!!.trimPage)
        assertEquals(2L, range.backlog!!.wrapCount)
        assertEquals(3L, range.backlog!!.freeRecords)
    }
}

internal fun fromHex(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}
