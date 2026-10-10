// Ported from OpenStrap analytics/lib/src/onehz/sleep/cycles.dart.
// Rosenblum et al. 2024 fractal sleep cycles, adapted to per-minute RMSSD peak-to-peak intervals.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One detected sleep cycle, in minutes elapsed from sleep onset. */
data class SleepCycle(
    val startMin: Int,
    val endMin: Int,
    val lenMin: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "start_min" to startMin,
        "end_min" to endMin,
        "len_min" to lenMin,
    )
}

data class SleepCyclesResult(
    val cycles: List<SleepCycle>,
    val meanDurationMin: Double?,
    val n: Int,
    /**
     * Smoothed z-scored per-minute RMSSD, one `{t, z}` per present minute.
     * `t` is absolute epoch seconds. `z` is rounded to 3 decimal places.
     */
    val series: List<Map<String, Any>> = emptyList(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "cycles" to cycles.map { it.toJson() },
        "cycle_count" to n,
        "cycles_mean_min" to meanDurationMin,
        "series" to series,
    )
}

private const val SMOOTH_MIN = 10
private const val MIN_PEAK_DIST = 20
private const val MIN_PROMINENCE = 0.9
private const val RR_MIN = 300.0
private const val RR_MAX = 2000.0
private const val RR_STEP = 200.0

private val cycleInputs = listOf("rr_cleaned")

/**
 * Peak-to-peak cycles of per-minute RMSSD over [onsetSec, offsetSec).
 *
 * [rrTsMs] are absolute beat times in ms. A beat before onset is floored,
 * not truncated, so it cannot land in minute 0. Absent (empty result, not a
 * metric) when the window is under 1 h or fewer than 60 minutes have an RMSSD.
 */
fun detectSleepCycles(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    onsetSec: Int,
    offsetSec: Int,
): SleepCyclesResult {
    val absent = SleepCyclesResult(emptyList(), null, 0)
    if (rrMs.isEmpty() || rrTsMs.size != rrMs.size || offsetSec <= onsetSec) return absent
    val nMin = (offsetSec - onsetSec) / 60
    if (nMin < 60) return absent

    val perMin = MutableList<Double?>(nMin) { null }
    val bins = ArrayList<MutableList<Double>>(nMin)
    repeat(nMin) { bins.add(ArrayList()) }
    for (k in rrMs.indices) {
        val relSec = floor(rrTsMs[k] / 1000.0).toInt() - onsetSec
        val m = floor(relSec / 60.0).toInt()
        if (m < 0 || m >= nMin) continue
        val v = rrMs[k]
        if (v >= RR_MIN && v <= RR_MAX) bins[m].add(v)
    }
    for (m in 0 until nMin) {
        perMin[m] = rmssd(bins[m])
    }

    val sm = ArrayList<Double?>(nMin)
    for (i in 0 until nMin) {
        var s = 0.0
        var c = 0
        val lo = max(0, i - SMOOTH_MIN)
        val hi = min(nMin - 1, i + SMOOTH_MIN)
        for (j in lo..hi) {
            val v = perMin[j]
            if (v != null) {
                s += v
                c++
            }
        }
        sm.add(if (c != 0) s / c else null)
    }
    val vals = ArrayList<Double>()
    for (v in sm) if (v != null) vals.add(v)
    if (vals.size < 60) return absent

    var sum = 0.0
    for (v in vals) sum += v
    val mu = sum / vals.size
    var ss = 0.0
    for (v in vals) {
        val d = v - mu
        ss += d * d
    }
    val sd0 = sqrt(ss / vals.size)
    val sd = if (sd0 == 0.0) 1.0 else sd0
    val z = ArrayList<Double?>(nMin)
    for (x in sm) {
        z.add(if (x == null) null else (x - mu) / sd)
    }

    val peaks = findPeaks(z, MIN_PEAK_DIST, MIN_PROMINENCE)
    val cycles = ArrayList<SleepCycle>()
    for (i in 0 until peaks.size - 1) {
        val a = peaks[i]
        val b = peaks[i + 1]
        cycles.add(SleepCycle(a, b, b - a))
    }
    val meanDur = if (cycles.isEmpty()) {
        null
    } else {
        var lenSum = 0.0
        for (c in cycles) lenSum += c.lenMin
        lenSum / cycles.size
    }

    val series = ArrayList<Map<String, Any>>()
    for (m in 0 until nMin) {
        val zv = z[m] ?: continue
        val z3 = roundHalfAwayFromZero(zv * 1000.0) / 1000.0
        series.add(mapOf("t" to (onsetSec + m * 60), "z" to z3))
    }
    return SleepCyclesResult(cycles, meanDur, cycles.size, series)
}

/** Metric envelope. HRV-derived estimate, not PSG. */
fun sleepCyclesMetric(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    onsetSec: Int,
    offsetSec: Int,
): OsMetric<SleepCyclesResult> {
    val r = detectSleepCycles(rrMs, rrTsMs, onsetSec, offsetSec)
    if (r.n == 0) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = cycleInputs,
            note = "need ≥1 h sleep window with sufficient RR for cycle detection",
        )
    }
    return OsMetric(
        value = r,
        confidence = 0.5,
        tier = Tier.ESTIMATE,
        inputsUsed = cycleInputs,
        note = "fractal-cycle method (Rosenblum 2024) on per-minute RMSSD; " +
            "peak-to-peak ultradian cycles, HRV-derived ESTIMATE (not PSG)",
    )
}

private fun rmssd(rr: List<Double>): Double? {
    if (rr.size < 10) return null
    var s = 0.0
    var n = 0
    for (i in 1 until rr.size) {
        val d = rr[i] - rr[i - 1]
        if (abs(d) > RR_STEP) continue
        s += d * d
        n++
    }
    return if (n >= 1) sqrt(s / n) else null
}

/**
 * Local maxima with z-prominence ≥ [minProm], then tallest-first prune to
 * ≥ [minDist] minutes apart. Peak indices come back ascending.
 * A missing neighbour is −∞, so a hole does not veto a peak, and it is
 * skipped when the side-minimum is taken.
 */
private fun findPeaks(y: List<Double?>, minDist: Int, minProm: Double): List<Int> {
    val n = y.size
    val cand = ArrayList<Pair<Int, Double>>()
    for (i in 1 until n - 1) {
        val yi = y[i] ?: continue
        val a = y[i - 1] ?: Double.NEGATIVE_INFINITY
        val b = y[i + 1] ?: Double.NEGATIVE_INFINITY
        if (!(yi >= a && yi > b)) continue
        var l = i
        while (l > 0 && (y[l - 1] ?: Double.NEGATIVE_INFINITY) < yi) l--
        var r = i
        while (r < n - 1 && (y[r + 1] ?: Double.NEGATIVE_INFINITY) < yi) r++
        var lmin = yi
        var rmin = yi
        for (k in l..i) {
            val v = y[k]
            if (v != null && v < lmin) lmin = v
        }
        for (k in i..r) {
            val v = y[k]
            if (v != null && v < rmin) rmin = v
        }
        if (yi - max(lmin, rmin) >= minProm) cand.add(i to yi)
    }
    cand.sortByDescending { it.second }
    val kept = ArrayList<Int>()
    for ((idx, _) in cand) {
        if (kept.all { k -> abs(idx - k) >= minDist }) kept.add(idx)
    }
    kept.sort()
    return kept
}
