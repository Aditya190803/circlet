package app.circlet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {
    @Test
    fun roundTripKeepsThePaddedInner() {
        val inner = byteArrayOf(0x23, 0x01, 0x22, 0x00)
        val back = FrameReassembler().feed(Frames.wrap(inner))
        assertEquals(1, back.size)
        assertArrayEquals(inner, back[0])
    }

    @Test
    fun payloadContainingStartByteSurvives() {
        val token = ByteArray(8) { 0xAA.toByte() }
        val stream = Commands.historyAck(1, token) + Commands.getHello(2)
        val frames = FrameReassembler().feed(stream)
        assertEquals(2, frames.size)
        assertEquals(0x17, frames[0][2].toInt() and 0xFF)
        assertArrayEquals(token, frames[0].copyOfRange(4, 12))
        assertEquals(0x23, frames[1][2].toInt() and 0xFF)
    }

    @Test
    fun reassemblesEverySplit() {
        val frame = Commands.getDataRange(4)
        for (cut in 1 until frame.size) {
            val re = FrameReassembler()
            assertTrue(re.feed(frame.copyOfRange(0, cut)).isEmpty())
            val rest = re.feed(frame.copyOfRange(cut, frame.size))
            assertEquals("cut $cut", 1, rest.size)
            assertEquals(4, rest[0][1].toInt() and 0xFF)
        }
    }

    @Test
    fun crcFailureIsDroppedAndTheNextFrameSurvives() {
        val first = Commands.getHello(3)
        val second = Commands.getBattery(4)
        val stream = first + second
        stream[first.size - 1] = (stream[first.size - 1].toInt() xor 0xFF).toByte()
        val re = FrameReassembler()
        val frames = re.feed(stream)
        assertTrue(re.resyncs > 0)
        assertEquals(1, frames.size)
        assertEquals(4, frames[0][1].toInt() and 0xFF)
        assertEquals(0x1A, frames[0][2].toInt() and 0xFF)
    }

    @Test
    fun badLengthResyncsOntoTheFollowingFrame() {
        val first = Frames.wrap(ByteArray(64) { 0x11 })
        val second = Commands.getHello(9)
        val stream = first + second
        stream[1] = (stream[1].toInt() xor 0x01).toByte()
        val re = FrameReassembler()
        val frames = re.feed(stream)
        assertTrue(re.resyncs > 0)
        assertEquals(1, frames.size)
        assertEquals(9, frames[0][1].toInt() and 0xFF)
    }

    @Test
    fun droppedByteCostsOneFrame() {
        val first = Frames.wrap(ByteArray(32) { 0x22 })
        val second = Commands.getHello(6)
        val stream = (first + second).toMutableList()
        stream.removeAt(2)
        val frames = FrameReassembler().feed(stream.toByteArray())
        assertEquals(listOf(6), frames.map { it[1].toInt() and 0xFF })
    }

    @Test
    fun resetDropsAPartialFrame() {
        val frame = Commands.getHello(1)
        val re = FrameReassembler()
        assertTrue(re.feed(frame.copyOfRange(0, 3)).isEmpty())
        re.reset()
        assertTrue(re.feed(frame.copyOfRange(3, frame.size)).isEmpty())
        assertEquals(1, re.feed(frame).size)
    }
}

internal operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    copyInto(out)
    other.copyInto(out, size)
    return out
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
