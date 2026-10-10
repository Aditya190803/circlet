// Ported from OpenStrap protocol/lib/src/live.dart — decodeRecord, realtimeRr, frameAccel.
package app.circlet.protocol

import kotlin.math.min
import kotlin.math.sqrt

internal const val R10_MIN_LENGTH = 685
private const val MAX_RR_PER_RECORD = 8

internal data class DecodedRecord(
    val ts: Long,
    val hr: Int,
    val activity: Double?,
    val stepsInc: Int?,
    val wristOn: Boolean,
    val recType: Int,
)

internal data class RealtimeRr(val ts: Long, val intervals: List<Int>)

internal data class ImuFrame(
    val ts: Long,
    val idx: Int,
    val mags: List<Double>,
)

private data class Motion(val activity: Double, val steps: Int)

internal fun decodeRecord(b: ByteArray): DecodedRecord? {
    if (b.size < 4) return null
    val pkt = u8(b, 0)
    val rec = u8(b, 1)
    if (pkt == 0x28) {
        if (b.size < 9) return null
        val hr = u8(b, 8)
        return DecodedRecord(u32(b, 2), hr, null, null, hr > 0, 28)
    }
    if (pkt == 0x33) return null
    if (b.size < 18) return null
    if (rec in KNOWN_RECORD_VERSIONS) {
        val d = parseR24(b) ?: return null
        return DecodedRecord(d.epochSecond, d.heartRate, null, null, d.heartRate > 0, rec)
    }
    if (rec == 10 && (pkt == 0x2B || b.size >= R10_MIN_LENGTH)) {
        val hr = u8(b, 17)
        val motion = r10Motion(b)
        return DecodedRecord(u32(b, 7), hr, motion?.activity, motion?.steps, hr > 0, 10)
    }
    return null
}

internal fun realtimeRr(b: ByteArray): RealtimeRr? {
    if (b.size < 12) return null
    val pkt = u8(b, 0)
    val rec = u8(b, 1)
    val tsOff: Int
    val countOff: Int
    val maxRr: Int
    if (pkt == 0x28) {
        tsOff = 2
        countOff = 9
        maxRr = 4
    } else if (rec == 10 && (pkt == 0x2B || b.size >= R10_MIN_LENGTH)) {
        tsOff = 7
        countOff = 18
        maxRr = MAX_RR_PER_RECORD
    } else {
        return null
    }
    if (countOff + 1 >= b.size) return null
    val ts = u32(b, tsOff)
    if (ts <= 0L) return null
    val n = u8(b, countOff)
    if (n == 0 || n > maxRr) return null
    val intervals = ArrayList<Int>(n)
    val first = countOff + 1
    var i = 0
    while (i < n && first + 2 * i + 2 <= b.size) {
        val v = i16(b, first + 2 * i)
        if (v in MIN_RR_MS..MAX_RR_MS) intervals.add(v)
        i++
    }
    return if (intervals.isEmpty()) null else RealtimeRr(ts, intervals)
}

internal fun frameAccel(b: ByteArray): ImuFrame? {
    if (b.size < 32) return null
    val pkt = u8(b, 0)
    val rec = u8(b, 1)
    if (pkt == 0x33 && b.size >= 84) {
        val ts = u32(b, 4)
        if (ts <= 0L) return null
        val idx = u16(b, 14)
        val mags = ArrayList<Double>(10)
        for (i in 0 until 10) {
            val x = i16(b, 24 + 2 * i)
            val y = i16(b, 24 + 2 * (10 + i))
            val z = i16(b, 24 + 2 * (20 + i))
            mags.add(sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z) / 4096.0)
        }
        return ImuFrame(ts, idx, mags)
    }
    if (rec == 0x0A && b.size >= R10_MIN_LENGTH) {
        val ts = u32(b, 7)
        if (ts <= 0L) return null
        val mags = ArrayList<Double>(100)
        for (i in 0 until 100) {
            val x = i16(b, 85 + 2 * i)
            val y = i16(b, 285 + 2 * i)
            val z = i16(b, 485 + 2 * i)
            mags.add(sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z) / 4096.0)
        }
        return ImuFrame(ts, 0, mags)
    }
    return null
}

private fun r10Motion(b: ByteArray): Motion? {
    if (b.size < R10_MIN_LENGTH) return null
    val scale = 1.0 / 4096.0
    fun axis(off: Int): IntArray {
        val out = IntArray(100)
        var n = 0
        for (i in 0 until 100) {
            val o = off + 2 * i
            if (o + 2 <= b.size) out[n++] = i16(b, o)
        }
        return if (n == 100) out else out.copyOf(n)
    }
    val ax = axis(85)
    val ay = axis(285)
    val az = axis(485)
    val n = min(ax.size, min(ay.size, az.size))
    if (n == 0) return null
    val mags = DoubleArray(n)
    for (i in 0 until n) {
        val x = ax[i] * scale
        val y = ay[i] * scale
        val z = az[i] * scale
        mags[i] = sqrt(x * x + y * y + z * z)
    }
    var sum = 0.0
    for (v in mags) sum += v
    val mean = sum / n
    var varSum = 0.0
    for (v in mags) {
        val d = v - mean
        varSum += d * d
    }
    val std = sqrt(varSum / n)
    val activity = jsRound(std * 1000.0) / 1000.0
    if (std < 0.05 || n < 24) return Motion(activity, 0)

    val w = 9
    val x = DoubleArray(n)
    for (i in 0 until n) {
        var s = 0.0
        var c = 0
        val from = maxOf(0, i - w)
        val to = min(n - 1, i + w)
        for (j in from..to) {
            s += mags[j]
            c++
        }
        x[i] = mags[i] - s / c
    }
    var x0sum = 0.0
    for (v in x) x0sum += v
    val x0 = x0sum / n
    var denom = 0.0
    for (i in 0 until n) {
        val d = x[i] - x0
        denom += d * d
    }
    if (denom <= 1e-9) return Motion(activity, 0)
    var bestLag = 0
    var bestR = 0.0
    val lagLimit = min(40, n - 1)
    for (lag in 7..lagLimit) {
        var num = 0.0
        val last = n - lag
        for (i in 0 until last) num += (x[i] - x0) * (x[i + lag] - x0)
        val r = num / denom
        if (r > bestR) {
            bestR = r
            bestLag = lag
        }
    }
    if (bestLag == 0 || bestR < 0.45) return Motion(activity, 0)
    val steps = jsRound(n.toDouble() / bestLag).toInt()
    return Motion(activity, steps)
}
