// Ported from OpenStrap analytics/lib/src/onehz/util.dart.
// Pure statistics used by every metric family: median/MAD, sample cadence,
// Lomb–Scargle (Press & Rybicki 1989), and the Benjamini–Hochberg FDR step-up.
// An undefined statistic is null. Nothing here substitutes a number for missing data.

package app.circlet.analytics.foundations

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean, or null if empty. */
fun mean(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    var s = 0.0
    for (x in xs) s += x
    return s / xs.size
}

/** Sample standard deviation (N−1), or null if fewer than 2 points. */
fun stddev(xs: List<Double>): Double? {
    if (xs.size < 2) return null
    val m = mean(xs)!!
    var s = 0.0
    for (x in xs) {
        val d = x - m
        s += d * d
    }
    return sqrt(s / (xs.size - 1))
}

/** Population standard deviation (N), or null if empty. */
fun stddevPop(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    val m = mean(xs)!!
    var s = 0.0
    for (x in xs) {
        val d = x - m
        s += d * d
    }
    return sqrt(s / xs.size)
}

/**
 * Linear-interpolated percentile (p in 0..100) of an already-ascending list.
 * Null if empty: an empty window is the absence of a measurement.
 */
fun percentileSorted(sorted: List<Double>, p: Double): Double? {
    if (sorted.isEmpty()) return null
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = floor(rank).toInt()
    val hi = kotlin.math.ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

/** Linear-interpolated percentile (p in 0..100); null if empty. */
fun percentile(values: List<Double>, p: Double): Double? =
    percentileSorted(values.sorted(), p)

/** Median (linear-interpolated), or null if empty. */
fun median(xs: List<Double>): Double? = percentile(xs, 50.0)

/**
 * Median absolute deviation. Scaled by 1.4826 it estimates σ for normal data.
 * The raw MAD is 0 on quantized data; callers must not divide by it.
 */
fun mad(xs: List<Double>, scaled: Boolean = true): Double? {
    if (xs.isEmpty()) return null
    val m = median(xs)!!
    val dev = xs.map { abs(it - m) }
    val raw = median(dev)!!
    return if (scaled) raw * 1.4826 else raw
}

/**
 * Fastest cadence [sampleCadenceSeconds] will vouch for, in seconds.
 * A ceiling on what has been measured, not a physical limit.
 */
const val MAX_SUPPORTED_CADENCE_SEC = 300.0

/** Gaps within this factor of the median belong to the same mode. */
private const val CADENCE_MODE_TOLERANCE = 2.0

/** The median must describe at least this share of the gaps. */
private const val CADENCE_MIN_MODE_FRACTION = 0.5

/**
 * Measured sampling cadence (seconds) of a time-ordered stream, or null.
 *
 * Abstains when there are fewer than two positive gaps, when the median gap
 * exceeds [MAX_SUPPORTED_CADENCE_SEC], or when that median describes under half
 * the gaps. There is no fallback cadence: a wrong one becomes a wrong metric.
 */
fun sampleCadenceSeconds(tsSec: List<Double>): Double? {
    if (tsSec.size < 2) return null
    val gaps = ArrayList<Double>(tsSec.size - 1)
    for (i in 1 until tsSec.size) {
        val g = tsSec[i] - tsSec[i - 1]
        if (g > 0.0) gaps.add(g)
    }
    val m = median(gaps) ?: return null
    if (m > MAX_SUPPORTED_CADENCE_SEC) return null
    var inMode = 0
    for (g in gaps) {
        if (g <= m * CADENCE_MODE_TOLERANCE && g * CADENCE_MODE_TOLERANCE >= m) {
            inMode++
        }
    }
    if (inMode < gaps.size * CADENCE_MIN_MODE_FRACTION) return null
    return m
}

/** Iglewicz–Hoaglin modified z of [x] against [sample]. Null when MAD is 0 or the sample is too small. */
fun robustZ(x: Double, sample: List<Double>): Double? = RobustScale.of(sample)?.z(x)

/**
 * Median and MAD of one sample, computed once.
 * Absent for fewer than two values or a MAD of zero, matching [robustZ].
 */
class RobustScale(val med: Double, val scale: Double) {
    fun z(x: Double): Double = (x - med) / scale

    companion object {
        fun of(sample: List<Double>): RobustScale? {
            if (sample.size < 2) return null
            val m = median(sample)!!
            val s = mad(sample)
            if (s == null || s == 0.0) return null
            return RobustScale(m, s)
        }
    }
}

/** Ordinary z-score (mean and sample SD). Null if the SD is undefined or zero. */
fun zScore(x: Double, sample: List<Double>): Double? {
    val m = mean(sample)
    val sd = stddev(sample)
    if (m == null || sd == null || sd == 0.0) return null
    return (x - m) / sd
}

/** Ordinary-least-squares slope of y versus x (x defaults to 0..n−1). Null if fewer than 2 points or x is constant. */
fun olsSlope(y: List<Double>, x: List<Double>? = null): Double? {
    val n = y.size
    if (n < 2) return null
    val xs = x ?: List(n) { it.toDouble() }
    val mx = mean(xs)!!
    val my = mean(y)!!
    var num = 0.0
    var den = 0.0
    for (i in 0 until n) {
        num += (xs[i] - mx) * (y[i] - my)
        den += (xs[i] - mx) * (xs[i] - mx)
    }
    return if (den == 0.0) null else num / den
}

data class LineFit(val slope: Double, val intercept: Double)

/** OLS line (slope and intercept). Null if degenerate. */
fun olsFit(y: List<Double>, x: List<Double>? = null): LineFit? {
    val n = y.size
    if (n < 2) return null
    val xs = x ?: List(n) { it.toDouble() }
    val s = olsSlope(y, xs) ?: return null
    val b = mean(y)!! - s * mean(xs)!!
    return LineFit(s, b)
}

/**
 * Theil–Sen slope: median of all pairwise slopes.
 * Null if fewer than 2 points or every x pair is identical.
 */
fun theilSen(y: List<Double>, x: List<Double>? = null): Double? {
    val n = y.size
    if (n < 2) return null
    val xs = x ?: List(n) { it.toDouble() }
    val slopes = ArrayList<Double>()
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val dx = xs[j] - xs[i]
            if (dx == 0.0) continue
            slopes.add((y[j] - y[i]) / dx)
        }
    }
    return if (slopes.isEmpty()) null else median(slopes)
}

/** Half-away-from-zero rounding, matching Dart `num.roundToDouble` on the values these metrics produce. */
internal fun roundHalfAwayFromZero(x: Double): Double {
    if (!x.isFinite()) return x
    val sign = if (x < 0.0) -1.0 else 1.0
    return sign * floor(abs(x) + 0.5)
}

/**
 * Six decimal places, for stable comparisons.
 * Null when [x] is not finite: NaN is not a measurement.
 */
fun round6(x: Double): Double? {
    if (!x.isFinite()) return null
    return roundHalfAwayFromZero(x * 1e6) / 1e6
}

fun roundTo(x: Double, decimals: Int): Double {
    val f = 10.0.pow(decimals)
    return roundHalfAwayFromZero(x * f) / f
}

/** One Lomb–Scargle bin. [psd] is (unit of y)² per Hz — ms²/Hz for an RR series in milliseconds on a seconds clock. */
data class LsPoint(val freqHz: Double, val psd: Double)

class LombScargle(val spectrum: List<LsPoint>) {
    /**
     * ∫ PSD df over [loHz, hiHz). Rectangular sum.
     * For RR in milliseconds this is ms², not a dimensionless periodogram.
     */
    fun bandPower(loHz: Double, hiHz: Double): Double {
        var p = 0.0
        for (i in spectrum.indices) {
            val f = spectrum[i].freqHz
            if (f < loHz || f >= hiHz) continue
            val df = if (i > 0) {
                spectrum[i].freqHz - spectrum[i - 1].freqHz
            } else if (spectrum.size > 1) {
                spectrum[1].freqHz - spectrum[0].freqHz
            } else {
                0.0
            }
            p += spectrum[i].psd * df
        }
        return p
    }

    /** Peak frequency inside [loHz, hiHz], or null if the grid has no point there. */
    fun peakFreq(loHz: Double, hiHz: Double): Double? {
        var best: Double? = null
        var bestP = Double.NEGATIVE_INFINITY
        for (pt in spectrum) {
            if (pt.freqHz < loHz || pt.freqHz > hiHz) continue
            if (pt.psd > bestP) {
                bestP = pt.psd
                best = pt.freqHz
            }
        }
        return best
    }
}

/**
 * Lomb–Scargle PSD for unevenly sampled data (Press & Rybicki 1989).
 *
 * [t] is in seconds so the grid is in Hz. The series is mean-subtracted.
 * Returns null if fewer than 4 points, zero variance, or zero time span.
 * Density is the unnormalised periodogram times 2·Δt (Δt = mean sample interval),
 * so integrating it recovers variance rather than a dimensionless power.
 */
fun lombScargle(t: List<Double>, y: List<Double>, freqsHz: List<Double>): LombScargle? {
    val n = t.size
    if (n < 4 || y.size != n || freqsHz.isEmpty()) return null
    val my = mean(y)!!
    val yc = DoubleArray(n) { y[it] - my }
    var variance = 0.0
    for (v in yc) variance += v * v
    variance /= (n - 1)
    if (variance <= 0.0) return null

    var tMin = t[0]
    var tMax = t[0]
    for (ti in t) {
        if (ti < tMin) tMin = ti
        if (ti > tMax) tMax = ti
    }
    val span = tMax - tMin
    if (span <= 0.0) return null
    val psdScale = 2.0 * span / (n - 1)

    val out = ArrayList<LsPoint>(freqsHz.size)
    for (fHz in freqsHz) {
        val w = 2.0 * PI * fHz
        if (w == 0.0) {
            out.add(LsPoint(0.0, 0.0))
            continue
        }
        var sin2 = 0.0
        var cos2 = 0.0
        for (ti in t) {
            sin2 += sin(2.0 * w * ti)
            cos2 += cos(2.0 * w * ti)
        }
        val tau = atan2(sin2, cos2) / (2.0 * w)

        var cNum = 0.0
        var cDen = 0.0
        var sNum = 0.0
        var sDen = 0.0
        for (i in 0 until n) {
            val arg = w * (t[i] - tau)
            val c = cos(arg)
            val s = sin(arg)
            cNum += yc[i] * c
            cDen += c * c
            sNum += yc[i] * s
            sDen += s * s
        }
        val term1 = if (cDen == 0.0) 0.0 else (cNum * cNum) / cDen
        val term2 = if (sDen == 0.0) 0.0 else (sNum * sNum) / sDen
        out.add(LsPoint(fHz, 0.5 * (term1 + term2) * psdScale))
    }
    return LombScargle(out)
}

/** Linear frequency grid from [loHz] to [hiHz] with [n] points, inclusive. */
fun freqGrid(loHz: Double, hiHz: Double, n: Int): List<Double> {
    if (n < 1) return emptyList()
    if (n == 1) return listOf(loHz)
    val step = (hiHz - loHz) / (n - 1)
    return List(n) { i -> loHz + step * i }
}

/** Natural log of a positive value, else null. */
fun safeLn(x: Double): Double? = if (x > 0.0) ln(x) else null

/**
 * Days since the Unix epoch for each `yyyy-MM-dd` label.
 * An unparseable label falls back to its position, which is the old positional behaviour.
 */
fun calendarDays(dates: List<String>): List<Int> {
    return dates.mapIndexed { i, label ->
        val day = label.take(10)
        val parsed = runCatching { java.time.LocalDate.parse(day) }.getOrNull()
        parsed?.toEpochDay()?.toInt() ?: i
    }
}

/**
 * Average ranks, 1-based. Ties share their mean rank so a tie is not an invented order.
 */
fun averageRanks(xs: List<Double>): List<Double> {
    val idx = xs.indices.sortedWith { a, b -> xs[a].compareTo(xs[b]) }
    val ranks = DoubleArray(xs.size)
    var i = 0
    while (i < idx.size) {
        var j = i
        while (j + 1 < idx.size && xs[idx[j + 1]] == xs[idx[i]]) j++
        val shared = (i + 1 + j + 1) / 2.0
        for (k in i..j) ranks[idx[k]] = shared
        i = j + 1
    }
    return ranks.toList()
}

/**
 * Two-sided standard-normal p, `2·(1−Φ(|z|))`.
 * Numerical Recipes `erfcc` (fractional error under 1.2e-7). A non-finite z is no evidence.
 */
fun normalTwoSidedP(zScore: Double): Double {
    if (!zScore.isFinite()) return 1.0
    val x = abs(zScore) / sqrt(2.0)
    val t = 1.0 / (1.0 + 0.5 * x)
    val ans = t * exp(
        -x * x - 1.26551223 +
            t * (
                1.00002368 +
                    t * (
                        0.37409196 +
                            t * (
                                0.09678418 +
                                    t * (
                                        -0.18628806 +
                                            t * (
                                                0.27886807 +
                                                    t * (
                                                        -1.13520398 +
                                                            t * (
                                                                1.48851587 +
                                                                    t * (
                                                                        -0.82215223 +
                                                                            t * 0.17087277
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                ),
    )
    return ans.coerceIn(0.0, 1.0)
}

/**
 * Benjamini–Hochberg (1995) step-up FDR. q-values stay aligned with [ps].
 * A null p is a test that was not run: it stays null and is not part of the family size.
 * The running minimum from the largest p downward keeps q monotone in p.
 */
fun benjaminiHochberg(ps: List<Double?>): List<Double?> {
    val idx = ps.indices.filter { ps[it] != null }.sortedBy { ps[it]!! }
    val m = idx.size
    val out = MutableList<Double?>(ps.size) { null }
    var running = 1.0
    for (k in m - 1 downTo 0) {
        val q = ps[idx[k]]!! * m / (k + 1)
        running = min(running, q)
        out[idx[k]] = running
    }
    return out
}
