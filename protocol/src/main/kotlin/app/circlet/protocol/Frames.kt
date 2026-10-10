// Ported from OpenStrap protocol/lib/src/framing.dart — buildFrame, FrameReassembler.
package app.circlet.protocol

/**
 * Gen 4 frame envelope.
 *
 * `AA | u16 LE size | crc8(size) | inner padded to 4 | u32 LE crc32(inner)`.
 * `size` counts the padded inner plus the 4-byte CRC. Payloads may contain `AA`;
 * reassembly is by length, and a bad header resyncs to the next start byte.
 */
object Frames {
    private const val SOF = 0xAA

    /** Wrap [inner] in a frame ready to write. The inner is zero-padded to 4 bytes before the CRC. */
    fun wrap(inner: ByteArray): ByteArray {
        val paddedLen = (inner.size + 3) and 3.inv()
        val padded = ByteArray(paddedLen)
        inner.copyInto(padded)
        val declared = paddedLen + 4
        val out = ByteArray(4 + paddedLen + 4)
        out[0] = SOF.toByte()
        out[1] = (declared and 0xFF).toByte()
        out[2] = ((declared ushr 8) and 0xFF).toByte()
        out[3] = crc8(declared and 0xFF, (declared ushr 8) and 0xFF).toByte()
        padded.copyInto(out, 4)
        val sum = crc32(padded)
        val tail = 4 + paddedLen
        out[tail] = sum.toByte()
        out[tail + 1] = (sum ushr 8).toByte()
        out[tail + 2] = (sum ushr 16).toByte()
        out[tail + 3] = (sum ushr 24).toByte()
        return out
    }
}

/**
 * Length-based reassembler. One instance per notify characteristic.
 *
 * [feed] returns the CRC-covered inner of each complete frame whose CRC-32 matches.
 * A CRC-32 failure is dropped and the stream resyncs, because a length that slipped
 * past the 8-bit header check cannot be trusted to step over.
 * [resyncs] counts headers that were skipped. It is diagnostic only.
 */
class FrameReassembler {
    private val buf = ArrayList<Int>(256)
    var resyncs: Int = 0
        private set

    fun feed(chunk: ByteArray): List<ByteArray> {
        for (b in chunk) buf.add(b.toInt() and 0xFF)
        val out = ArrayList<ByteArray>()
        while (buf.size >= 8) {
            if (buf[0] != 0xAA) {
                if (!resync()) break
                continue
            }
            val declared = buf[1] or (buf[2] shl 8)
            val total = 4 + declared
            if (declared < 4 || total > 4096) {
                if (!resync()) break
                continue
            }
            if (buf[3] != crc8(buf[1], buf[2])) {
                if (!resync()) break
                continue
            }
            if (buf.size < total) break
            val innerLen = declared - 4
            val inner = ByteArray(innerLen)
            for (i in 0 until innerLen) inner[i] = buf[4 + i].toByte()
            val stored = (buf[4 + innerLen].toLong() and 0xFF) or
                ((buf[5 + innerLen].toLong() and 0xFF) shl 8) or
                ((buf[6 + innerLen].toLong() and 0xFF) shl 16) or
                ((buf[7 + innerLen].toLong() and 0xFF) shl 24)
            if (crc32(inner) != stored) {
                if (!resync()) break
                continue
            }
            out.add(inner)
            buf.subList(0, total).clear()
            var pad = 0
            while (pad < buf.size && buf[pad] == 0) pad++
            if (pad > 0) buf.subList(0, pad).clear()
        }
        if (buf.size > 8192) buf.clear()
        return out
    }

    fun reset() {
        buf.clear()
        resyncs = 0
    }

    private fun resync(): Boolean {
        resyncs++
        var next = -1
        for (i in 1 until buf.size) {
            if (buf[i] == 0xAA) {
                next = i
                break
            }
        }
        if (next < 0) {
            buf.clear()
            return false
        }
        buf.subList(0, next).clear()
        return true
    }
}
