package app.circlet.analytics.wellness

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.calendarDays
import app.circlet.analytics.support.mad
import app.circlet.analytics.support.mdc
import app.circlet.analytics.support.mean
import app.circlet.analytics.support.median
import app.circlet.analytics.support.robustBaseline
import app.circlet.analytics.support.stddev
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

// Ported from OpenStrap lib/src/onehz/wellness/changepoint.dart.
// Online two-sided CUSUM (Page 1954) plus greedy binary segmentation with a
// BIC penalty on the Gaussian change-in-mean cost (an approximation to
// Killick, Fearnhead & Eckley 2012, not exact PELT).

internal data class CusumDetection(val index: Int, val direction: Int, val stat: Double)

internal data class Segmentation(
    val changePoints: List<Int>,
    val segmentMeans: List<Double>,
    val segments: List<List<Int>>,
    val penalty: Double,
)

internal fun cusumChangePoints(
    x: List<Double>,
    dates: List<String>? = null,
    k: Double = 0.5,
    h: Double = 5.0,
    minBaseline: Int = 10,
): List<CusumDetection> {
    if (x.size <= minBaseline) return emptyList()
    val day = if (dates != null && dates.size == x.size) calendarDays(dates) else null
    val out = ArrayList<CusumDetection>()
    var regimeStart = 0
    var up = 0.0
    var dn = 0.0
    for (i in x.indices) {
        if (day != null && i > 0 && day[i] - day[i - 1] > 1) {
            up = 0.0
            dn = 0.0
            regimeStart = i
        }
        val baseline = x.subList(regimeStart, i)
        if (baseline.size < minBaseline) continue
        val c = median(baseline)!!
        val s = mad(baseline) ?: 0.0
        if (s <= 0 || !s.isFinite()) continue
        val z = (x[i] - c) / s
        up = max(0.0, up + z - k)
        dn = max(0.0, dn - z - k)
        if (up > h || dn > h) {
            val upward = up > h
            out.add(CusumDetection(i, if (upward) 1 else -1, if (upward) up else dn))
            up = 0.0
            dn = 0.0
            regimeStart = i
        }
    }
    return out
}

internal fun segmentChangePoints(
    x: List<Double>,
    minSeg: Int = 7,
    penaltyK: Double = 2.0,
    penaltyOverride: Double? = null,
): Scored<Segmentation> {
    val n = x.size
    if (n < 2 * minSeg) {
        return Scored.absent("series shorter than 2× min-segment; no change-point search")
    }
    val prefix = DoubleArray(n + 1)
    val prefixSq = DoubleArray(n + 1)
    for (i in 0 until n) {
        prefix[i + 1] = prefix[i] + x[i]
        prefixSq[i + 1] = prefixSq[i] + x[i] * x[i]
    }
    val fullVar = stddev(x) ?: 1.0
    val sigma2 = fullVar * fullVar
    val penalty = penaltyOverride ?: (penaltyK * (if (sigma2 <= 0) 1.0 else sigma2) * ln(n.toDouble()))
    val cps = ArrayList<Int>()
    binSeg(prefix, prefixSq, 0, n, minSeg, penalty, cps)
    cps.sort()
    var dropped = 0
    var changed = true
    while (changed && cps.isNotEmpty()) {
        changed = false
        val edges = ArrayList<Int>(cps.size + 2)
        edges.add(0)
        edges.addAll(cps)
        edges.add(n)
        for (i in 1 until edges.size - 1) {
            val lo = edges[i - 1]
            val cut = edges[i]
            val hi = edges[i + 1]
            val before = x.subList(lo, cut)
            val step = abs(mean(x.subList(cut, hi))!! - mean(before)!!)
            val gate = mdc(robustBaseline(before, minSeg))
            if (gate == null || step < gate) {
                cps.removeAll { it == cut }
                dropped++
                changed = true
                break
            }
        }
    }
    val bounds = ArrayList<Int>(cps.size + 2)
    bounds.add(0)
    bounds.addAll(cps)
    bounds.add(n)
    val segments = ArrayList<List<Int>>()
    val means = ArrayList<Double>()
    for (i in 0 until bounds.size - 1) {
        val lo = bounds[i]
        val hi = bounds[i + 1]
        segments.add(listOf(lo, hi))
        means.add(mean(x.subList(lo, hi))!!)
    }
    return Scored.of(
        Segmentation(cps.toList(), means, segments, penalty),
        0.6,
        "GREEDY binary segmentation w/ BIC-penalized change-in-mean; below-MDC boundaries dropped=$dropped",
    )
}

private fun segSse(prefix: DoubleArray, prefixSq: DoubleArray, lo: Int, hi: Int): Double {
    val n = hi - lo
    if (n <= 0) return 0.0
    val sum = prefix[hi] - prefix[lo]
    val sumSq = prefixSq[hi] - prefixSq[lo]
    return sumSq - sum * sum / n
}

private fun binSeg(
    prefix: DoubleArray,
    prefixSq: DoubleArray,
    lo: Int,
    hi: Int,
    minSeg: Int,
    penalty: Double,
    out: MutableList<Int>,
) {
    val n = hi - lo
    if (n < 2 * minSeg) return
    val base = segSse(prefix, prefixSq, lo, hi)
    var best = -1
    var bestGain = 0.0
    var s = lo + minSeg
    while (s <= hi - minSeg) {
        val gain = base - segSse(prefix, prefixSq, lo, s) - segSse(prefix, prefixSq, s, hi)
        if (gain > bestGain) {
            bestGain = gain
            best = s
        }
        s++
    }
    if (best < 0 || bestGain <= penalty) return
    out.add(best)
    binSeg(prefix, prefixSq, lo, best, minSeg, penalty, out)
    binSeg(prefix, prefixSq, best, hi, minSeg, penalty, out)
}
