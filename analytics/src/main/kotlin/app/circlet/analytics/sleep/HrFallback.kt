// Ported from OpenStrap analytics/lib/src/onehz/sleep/hr_fallback.dart.
// HR-led fallback in-bed window from a sustained nocturnal heart-rate dip.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.percentile
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.min

/** A proposed in-bed window (epoch seconds). */
class HrLedWindow(
    val onsetSec: Int,
    val offsetSec: Int,
    /** Threshold (bpm) the dip had to stay under. */
    val thresholdBpm: Double,
) {
    val durationSec: Int get() = offsetSec - onsetSec
}

/**
 * Find a sleep window from a sustained nocturnal HR dip.
 *
 * [hr1hz] is 1 Hz HR (bpm; 0 / non-finite = off-skin), aligned 1:1 with [tsSec]
 * (epoch seconds). Returns the longest qualifying low-HR run, or null.
 */
fun hrLedSleepWindow(
    hr1hz: List<Double>,
    tsSec: List<Int>,
    hrBaseline: List<Double>? = null,
    minDurationSec: Int = 2 * 3600,
    bridgeGapSec: Int = 30 * 60,
    smoothSec: Double = 300.0,
    maxSampleGapSec: Int = 5 * 60,
): HrLedWindow? {
    val n = min(hr1hz.size, tsSec.size)
    if (n < minDurationSec / 2) return null

    val valid = ArrayList<Double>()
    for (i in 0 until n) {
        val h = hr1hz[i]
        if (h.isFinite() && h > 0.0) valid.add(h)
    }
    if (valid.size < minDurationSec / 4) return null
    var threshold: Double? = null
    if (hrBaseline != null) {
        val clean = hrBaseline.filter { it.isFinite() && it > 0.0 }
        val med = median(clean)
        if (med != null) threshold = med * 0.90
    }
    if (threshold == null) threshold = percentile(valid, 30.0)
    val thresholdBpm = threshold ?: return null

    val win = roundHalfAwayFromZero(smoothSec).toInt().coerceIn(1, n)
    val smooth = rollingMedianValidOnly(hr1hz, win)

    fun low(i: Int): Boolean = smooth[i] > 0.0 && smooth[i] < thresholdBpm

    var bestStart = -1
    var bestEnd = -1
    var bestDur = 0
    var i = 0
    while (i < n) {
        if (!low(i)) {
            i++
            continue
        }
        var j = i
        while (j < n) {
            if (j > i && tsSec[j] - tsSec[j - 1] > maxSampleGapSec) break
            if (low(j)) {
                j++
                continue
            }
            var k = j
            while (k < n &&
                !low(k) &&
                (tsSec[k] - tsSec[j]) < bridgeGapSec &&
                (k == 0 || tsSec[k] - tsSec[k - 1] <= maxSampleGapSec)
            ) {
                k++
            }
            if (k < n &&
                low(k) &&
                (tsSec[k] - tsSec[j]) < bridgeGapSec &&
                tsSec[k] - tsSec[k - 1] <= maxSampleGapSec
            ) {
                j = k
            } else {
                break
            }
        }
        val endIdx = (j - 1).coerceIn(0, n - 1)
        val dur = tsSec[endIdx] - tsSec[i]
        if (dur > bestDur) {
            bestDur = dur
            bestStart = i
            bestEnd = j
        }
        i = j + 1
    }

    if (bestStart < 0 || bestDur < minDurationSec) return null
    val onsetSec = tsSec[bestStart]
    val offsetSec = tsSec[(bestEnd - 1).coerceIn(0, n - 1)] + 1
    if (offsetSec <= onsetSec) return null
    return HrLedWindow(onsetSec, offsetSec, thresholdBpm)
}

/** Rolling median over a centered window, treating ≤0 / NaN as missing. */
private fun rollingMedianValidOnly(xs: List<Double>, win: Int): DoubleArray {
    val n = xs.size
    val out = DoubleArray(n)
    val half = win / 2
    for (i in 0 until n) {
        val lo = (i - half).coerceIn(0, n)
        val hi = (i + half + 1).coerceIn(0, n)
        val buf = ArrayList<Double>()
        for (k in lo until hi) {
            if (xs[k].isFinite() && xs[k] > 0.0) buf.add(xs[k])
        }
        out[i] = if (buf.isEmpty()) 0.0 else (median(buf) ?: 0.0)
    }
    return out
}
