// Ported from OpenStrap protocol/lib/src/control.dart — u16/u32/i16 readers.
package app.circlet.protocol

internal fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

internal fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

internal fun i16(b: ByteArray, o: Int): Int {
    val v = u16(b, o)
    return if (v and 0x8000 != 0) v - 0x10000 else v
}

internal fun u32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)

/** Little-endian IEEE-754 binary32, as a Double, without rounding. */
internal fun f32(b: ByteArray, o: Int): Double {
    val bits = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits).toDouble()
}

internal fun putU32(dst: ByteArray, at: Int, value: Long) {
    dst[at] = value.toByte()
    dst[at + 1] = (value shr 8).toByte()
    dst[at + 2] = (value shr 16).toByte()
    dst[at + 3] = (value shr 24).toByte()
}

internal fun hex(b: ByteArray): String {
    val out = CharArray(b.size * 2)
    val digits = "0123456789abcdef"
    for (i in b.indices) {
        val v = b[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}

/**
 * JavaScript `Math.round`: half toward +infinity.
 * OpenStrap's accel path uses this, not half-away-from-zero.
 */
internal fun jsRound(v: Double): Double {
    if (!v.isFinite()) return v
    return kotlin.math.floor(v + 0.5)
}

internal fun roundPlaces(v: Double, decimals: Int): Double {
    if (!v.isFinite()) return v
    var p = 1.0
    repeat(decimals) { p *= 10.0 }
    return jsRound(v * p) / p
}

/** Half away from zero, matching Dart `roundToDouble` used for battery percent. */
internal fun roundAway(v: Double, decimals: Int): Double {
    if (!v.isFinite()) return 0.0
    var p = 1.0
    repeat(decimals) { p *= 10.0 }
    val x = v * p
    val r = if (x >= 0) kotlin.math.floor(x + 0.5) else kotlin.math.ceil(x - 0.5)
    return r / p
}
