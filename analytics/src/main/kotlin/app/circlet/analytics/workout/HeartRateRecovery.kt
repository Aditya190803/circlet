package app.circlet.analytics.workout

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.median
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Ported from OpenStrap lib/src/onehz/workout/hr_recovery.dart.
// Cole / Lauer heart-rate recovery: peak heart rate in the last 30 seconds of
// exercise minus the heart rate 60 seconds later. The exponential time
// constant is fitted beside that drop and published only when the residual
// stays under 15% of the amplitude.

internal data class HrRecoveryValue(
    val peakHr: Double,
    val hrAt60s: Double,
    val dropBpm: Double,
    val dropPct: Double,
    val tauSec: Double? = null,
    val tauStartHr: Double? = null,
    val tauAmpBpm: Double? = null,
    val tauAsymptoteBpm: Double? = null,
    val tauResidualRatio: Double? = null,
    val tauAbsenceReason: String? = null,
)

private data class TauFit(
    val tau: Double,
    val amp: Double,
    val asymptote: Double,
    val residualRatio: Double,
)

internal fun hrRecovery(
    hrTailBpm: List<Int>,
    endIndex: Int? = null,
    recoverySec: Int = 60,
    peakWindowSec: Int = 30,
    tsSec: List<Int>? = null,
    maxGapSec: Int = 30,
    tauWindowSec: Int = 180,
    tauMaxResidualRatio: Double = 0.15,
): Scored<HrRecoveryValue> {
    val end = endIndex ?: 0
    if (hrTailBpm.isEmpty() || end < 0 || end >= hrTailBpm.size) {
        return Scored.absent("no HR tail for HRR")
    }
    val times = if (tsSec != null && tsSec.size == hrTailBpm.size) tsSec else null
    val peakLo: Int
    val peakWindowFull: Boolean
    if (times != null) {
        var i = end
        while (i > 0 &&
            times[end] - times[i - 1] <= peakWindowSec &&
            times[i] - times[i - 1] <= maxGapSec
        ) {
            i--
        }
        peakLo = i
        peakWindowFull = peakLo == 0 || times[end] - times[peakLo] >= peakWindowSec
    } else {
        peakLo = (end - peakWindowSec).coerceIn(0, hrTailBpm.lastIndex)
        peakWindowFull = false
    }
    var peak = 0.0
    for (i in peakLo..end) {
        val h = hrTailBpm[i]
        if (h > peak) peak = h.toDouble()
    }
    val target: Int
    if (times != null) {
        val wantTs = times[end] + recoverySec
        var t = -1
        for (i in end until times.size) {
            if (i > end && times[i] - times[i - 1] > maxGapSec) break
            t = i
            if (times[i] >= wantTs) break
        }
        if (t < 0) return Scored.absent("HR tail does not reach +${recoverySec}s without a gap — no HRR")
        if (t > end && wantTs - times[t - 1] < times[t] - wantTs) t--
        if (kotlin.math.abs(times[t] - wantTs) > 3) {
            return Scored.absent("no HR sample within 3 s of +${recoverySec}s — no HRR")
        }
        target = t
    } else {
        target = end + recoverySec
    }
    if (target >= hrTailBpm.size) {
        return Scored.absent("HR tail too short to reach +${recoverySec}s recovery point")
    }
    val lo: Int
    val hi: Int
    if (times != null) {
        var l = target
        while (l > 0 && times[target] - times[l - 1] <= 3) l--
        var h = target
        while (h + 1 < times.size && times[h + 1] - times[target] <= 3) h++
        lo = l
        hi = h
    } else {
        lo = (target - 3).coerceIn(0, hrTailBpm.lastIndex)
        hi = (target + 3).coerceIn(0, hrTailBpm.lastIndex)
    }
    val recWin = (lo..hi).map { hrTailBpm[it].toDouble() }.filter { it > 0 }
    if (peak <= 0 || recWin.isEmpty()) {
        return Scored.absent("off-skin or missing HR around the recovery point")
    }
    val hrAt60 = median(recWin)!!
    val drop = peak - hrAt60
    if (drop <= 0) return Scored.absent("HR did not descend after exercise end — likely still active")
    val pct = if (peak > 0) 100.0 * drop / peak else 0.0
    var seen = 0
    var valid = 0
    for (i in peakLo..end) {
        seen++
        if (hrTailBpm[i] > 0) valid++
    }
    for (i in lo..hi) {
        seen++
        if (hrTailBpm[i] > 0) valid++
    }
    val validFrac = if (seen == 0) 0.0 else valid.toDouble() / seen
    val conf = (0.3 + 0.4 * validFrac + if (times != null && peakWindowFull) 0.2 else 0.0).coerceIn(0.2, 0.9)

    var tauWhy: String? = null
    var fit: TauFit? = null
    if (times == null) {
        tauWhy = "tau_needs_timestamps"
    } else {
        val t0 = times[end]
        val tt = ArrayList<Double>()
        val yy = ArrayList<Double>()
        for (i in end until times.size) {
            if (i > end && times[i] - times[i - 1] > maxGapSec) break
            val dt = times[i] - t0
            if (dt > tauWindowSec) break
            if (hrTailBpm[i] <= 0) continue
            tt.add(dt.toDouble())
            yy.add(hrTailBpm[i].toDouble())
        }
        if (tt.isEmpty() || tt.last() < tauWindowSec / 2.0) {
            tauWhy = "tau_tail_short:span=${if (tt.isEmpty()) 0 else tt.last().roundToInt()}s"
        } else {
            fit = fitTau(tt, yy, 5.0, 200.0, tauMaxResidualRatio)
            if (fit == null) tauWhy = "tau_fit_rejected"
        }
    }
    return Scored.of(
        HrRecoveryValue(
            peakHr = peak,
            hrAt60s = hrAt60,
            dropBpm = drop,
            dropPct = pct,
            tauSec = fit?.tau,
            tauStartHr = fit?.let { it.asymptote + it.amp },
            tauAmpBpm = fit?.amp,
            tauAsymptoteBpm = fit?.asymptote,
            tauResidualRatio = fit?.residualRatio,
            tauAbsenceReason = tauWhy,
        ),
        conf,
        "HRR-${recoverySec}s = peak − HR@+${recoverySec}s",
    )
}

private fun fitTau(
    tSec: List<Double>,
    hr: List<Double>,
    minTau: Double,
    maxTau: Double,
    maxResidualRatio: Double,
): TauFit? {
    val m = tSec.size
    if (m < 30) return null
    var bestSse: Double? = null
    var bestTau = 0.0
    var bestA = 0.0
    var bestB = 0.0
    var tau = minTau
    while (tau <= maxTau) {
        var se = 0.0
        var see = 0.0
        var sy = 0.0
        var sey = 0.0
        for (i in 0 until m) {
            val e = exp(-tSec[i] / tau)
            se += e
            see += e * e
            sy += hr[i]
            sey += e * hr[i]
        }
        val den = m * see - se * se
        if (kotlin.math.abs(den) >= 1e-12) {
            val b = (m * sey - se * sy) / den
            val a = (sy - b * se) / m
            var sse = 0.0
            for (i in 0 until m) {
                val r = hr[i] - (a + b * exp(-tSec[i] / tau))
                sse += r * r
            }
            if (bestSse == null || sse < bestSse) {
                bestSse = sse
                bestTau = tau
                bestA = a
                bestB = b
            }
        }
        tau += 1.0
    }
    val sse = bestSse ?: return null
    if (bestB <= 0) return null
    if (bestTau <= minTau || bestTau >= maxTau) return null
    val ratio = sqrt(sse / m) / bestB
    if (!ratio.isFinite() || ratio > maxResidualRatio) return null
    return TauFit(bestTau, bestB, bestA, ratio)
}
