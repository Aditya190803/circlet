package app.circlet.analytics.heart

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dart VM Random (SDK 3.12 MWC) with nextInt / nextBool / Fisher–Yates.
 * nextDouble matches foundations.DartRandom; that class does not expose the rest.
 */
internal class HeartDartRandom(seed: Long) {
    private var state: Long = setupSeed(seed)

    init {
        repeat(4) { nextState() }
    }

    fun nextInt(max: Int): Int {
        if ((max and -max) == max) {
            nextState()
            return ((state and 0xFFFFFFFFL) and (max - 1).toLong()).toInt()
        }
        val pow2_32 = 0x100000000L
        while (true) {
            nextState()
            val rnd32 = state and 0xFFFFFFFFL
            val result = rnd32 % max.toLong()
            if (rnd32 - result + max.toLong() <= pow2_32) return result.toInt()
        }
    }

    fun nextBool(): Boolean = nextInt(2) == 0

    fun nextDouble(): Double {
        val hi = nextInt(1 shl 26)
        val lo = nextInt(1 shl 27)
        return (hi * POW2_27 + lo) / POW2_53
    }

    private fun nextState() {
        val a = 0xffffda61L
        val stateLo = state and 0xFFFFFFFFL
        val stateHi = state ushr 32
        state = a * stateLo + stateHi
    }

    companion object {
        private const val POW2_27 = 134217728.0
        private const val POW2_53 = 9007199254740992.0

        private fun setupSeed(n0: Long): Long {
            var n = n0
            n = n.inv() + (n shl 21)
            n = n xor (n ushr 24)
            n *= 265
            n = n xor (n ushr 14)
            n *= 21
            n = n xor (n ushr 28)
            n += n shl 31
            if (n == 0L) n = 0x5a17
            return n
        }
    }
}

internal fun <T> MutableList<T>.dartShuffle(rnd: HeartDartRandom) {
    var length = size
    while (length > 1) {
        val pos = rnd.nextInt(length)
        length--
        val tmp = this[length]
        this[length] = this[pos]
        this[pos] = tmp
    }
}

internal fun gauss(rnd: HeartDartRandom): Double =
    sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2.0 * kotlin.math.PI * rnd.nextDouble())

internal data class TimedRr(val rr: List<Double>, val times: List<Double>)

internal fun rsaNn(hrBpm: Double, respBrpm: Double, ampMs: Double, beats: Int = 3000): List<Double> {
    val base = 60000.0 / hrBpm
    val f = respBrpm / 60.0
    val out = ArrayList<Double>(beats)
    var tSec = 0.0
    for (i in 0 until beats) {
        val v = base + ampMs * sin(2.0 * kotlin.math.PI * f * tSec)
        out.add(v)
        tSec += v / 1000.0
    }
    return out
}

internal fun beatEnds(nn: List<Double>, t0Ms: Double = 0.0): List<Double> {
    val out = ArrayList<Double>(nn.size)
    var t = t0Ms
    for (v in nn) {
        t += v
        out.add(t)
    }
    return out
}

internal fun duplicateEach(xs: List<Double>): List<Double> {
    val out = ArrayList<Double>(xs.size * 2)
    for (v in xs) {
        out.add(v)
        out.add(v)
    }
    return out
}

internal fun syntheticRsaRr(
    modHz: Double,
    hrBpm: Double = 60.0,
    ampMs: Double = 40.0,
    beats: Int = 400,
): TimedRr {
    val meanRr = 60000.0 / hrBpm
    val rr = ArrayList<Double>(beats)
    val times = ArrayList<Double>(beats)
    var t = 0.0
    for (i in 0 until beats) {
        val phase = 2.0 * kotlin.math.PI * modHz * (t / 1000.0)
        val r = meanRr + ampMs * sin(phase)
        t += r
        rr.add(r)
        times.add(t)
    }
    return TimedRr(rr, times)
}

internal fun syntheticRiivAdc(
    hz: Double,
    seconds: Int = 120,
    dc: Double = 10000.0,
    amp: Double = 200.0,
): TimedRr {
    val adc = ArrayList<Double>(seconds)
    val ts = ArrayList<Double>(seconds)
    for (s in 0 until seconds) {
        val drift = 0.5 * s
        adc.add(dc + drift + amp * sin(2.0 * kotlin.math.PI * hz * s))
        ts.add(s.toDouble())
    }
    return TimedRr(adc, ts)
}

internal fun gappyRsaNight(
    seed: Int,
    hrBpm: Double = 55.0,
    hours: Double = 7.0,
    resp0Brpm: Double = 16.0,
    resp1Brpm: Double = 14.0,
    dropouts: List<Pair<Double, Double>> = emptyList(),
): TimedRr {
    val rnd = HeartDartRandom(seed.toLong())
    val rr = ArrayList<Double>()
    val t = ArrayList<Double>()
    val base = 60000.0 / hrBpm
    val totalSec = hours * 3600.0
    val f0 = resp0Brpm / 60.0
    val f1 = resp1Brpm / 60.0
    var tMs = 0.0
    var walk = 0.0
    while (tMs < totalSec * 1000.0) {
        val sec = tMs / 1000.0
        val phase = 2.0 * kotlin.math.PI * (f0 * sec + (f1 - f0) * sec * sec / (2.0 * totalSec))
        walk = (walk + (rnd.nextDouble() - 0.5) * 12.0) * 0.98
        val v = base + walk + 40.0 * sin(phase)
        tMs += v
        val h = sec / 3600.0
        var dropped = false
        for (d in dropouts) {
            if (h >= d.first && h < d.second) {
                dropped = true
                break
            }
        }
        if (dropped) continue
        rr.add(v)
        t.add(tMs)
    }
    return TimedRr(rr, t)
}

internal fun rsaNight(seed: Int, hours: Double = 8.0): TimedRr {
    val rnd = HeartDartRandom(seed.toLong())
    val rr = ArrayList<Double>()
    val t = ArrayList<Double>()
    val totalSec = hours * 3600.0
    val f0 = 19.0 / 60.0
    val f1 = 16.0 / 60.0
    var tMs = 0.0
    var walk = 0.0
    while (tMs < totalSec * 1000.0) {
        val sec = tMs / 1000.0
        val phase = 2.0 * kotlin.math.PI * (f0 * sec + (f1 - f0) * sec * sec / (2.0 * totalSec))
        walk = (walk + (rnd.nextDouble() - 0.5) * 12.0) * 0.98
        val v = 1000.0 + walk + 40.0 * sin(phase)
        tMs += v
        rr.add(v)
        t.add(tMs)
    }
    return TimedRr(rr, t)
}
