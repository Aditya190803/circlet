package app.circlet.analytics.stress

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.median
import kotlin.math.min

// Ported from OpenStrap lib/src/onehz/clinical/stress_si.dart.
// Baevsky stress index (Baevsky & Berseneva 2008):
//   SI = AMo / (2 · Mo · MxDMn)
// over ~5-minute windows (256 beats, 50% overlap). The reported value is the
// median window. Windows whose RR range is under 20 ms are quantization, not
// physiology, and are dropped.

internal data class StressIndexValue(
    val si: Double,
    val level: String,
    val modeS: Double,
    val amoPct: Double,
    val mxdmnS: Double,
)

internal data class StressWindow(val si: Double, val endTimeMs: Double)

internal fun baevskyStressIndex(
    nnMs: List<Double>,
    nnTimesMs: List<Double>? = null,
    minRangeMs: Double = 20.0,
    maxGapSec: Double = 30.0,
): Scored<StressIndexValue> {
    val windows = stressWindows(nnMs, nnTimesMs, minRangeMs, maxGapSec)
    if (nnMs.count { it in 300.0..2000.0 } < 30 && windows == null) {
        return Scored.absent("need ≥30 clean beats for a Stress Index histogram")
    }
    if (windows.isNullOrEmpty()) return Scored.absent("no valid Stress Index window")
    val si = median(windows.map { it.si })!!
    val level = when {
        si < 50 -> "low"
        si < 150 -> "normal"
        si < 500 -> "elevated"
        else -> "high"
    }
    return Scored.of(
        StressIndexValue(si, level, 0.0, 0.0, 0.0),
        (0.4 + windows.size / 60.0).coerceIn(0.4, 0.7),
        "Baevsky Stress Index — median of ~5-min-window SI",
    )
}

/**
 * One SI per surviving window, with the beat time of the window's last beat.
 * The median of these is [baevskyStressIndex].
 */
internal fun stressWindows(
    nnMs: List<Double>,
    nnTimesMs: List<Double>? = null,
    minRangeMs: Double = 20.0,
    maxGapSec: Double = 30.0,
): List<StressWindow>? {
    val useTimes = nnTimesMs != null && nnTimesMs.size == nnMs.size
    val nn = ArrayList<Double>()
    val nnT = ArrayList<Double>()
    for (i in nnMs.indices) {
        val v = nnMs[i]
        if (v < 300 || v > 2000) continue
        nn.add(v)
        if (useTimes) nnT.add(nnTimesMs[i])
    }
    if (nn.size < 30) return null
    val segStart = ArrayList<Int>()
    segStart.add(0)
    if (useTimes) {
        for (i in 1 until nnT.size) {
            if ((nnT[i] - nnT[i - 1]) / 1000.0 > maxGapSec) segStart.add(i)
        }
    }
    val win = 256
    val step = 128
    val out = ArrayList<StressWindow>()
    for (s in segStart.indices) {
        val lo = segStart[s]
        val hi = if (s + 1 < segStart.size) segStart[s + 1] else nn.size
        var start = 0
        while (start + 30 <= hi - lo) {
            val end = min(start + win, hi - lo)
            val seg = nn.subList(lo + start, lo + end)
            if (seg.size < 30) break
            val si = siOfSegment(seg, minRangeMs)
            if (si != null) {
                val endTime = if (useTimes) nnT[lo + end - 1] else (lo + end - 1).toDouble()
                out.add(StressWindow(si, endTime))
            }
            if (start + win >= hi - lo) break
            start += step
        }
    }
    return out
}

private fun siOfSegment(seg: List<Double>, minRangeMs: Double): Double? {
    val binMs = 50.0
    val counts = HashMap<Int, Int>()
    for (v in seg) {
        val b = (v / binMs).toInt()
        counts[b] = (counts[b] ?: 0) + 1
    }
    var modeBin = counts.keys.first()
    var modeCount = 0
    for ((b, c) in counts) {
        if (c > modeCount) {
            modeCount = c
            modeBin = b
        }
    }
    val modeS = ((modeBin + 0.5) * binMs) / 1000.0
    val amoPct = 100.0 * modeCount / seg.size
    val mxdmnMs = seg.max() - seg.min()
    if (modeS <= 0 || mxdmnMs < minRangeMs) return null
    val mxdmnS = mxdmnMs / 1000.0
    return amoPct / (2.0 * modeS * mxdmnS)
}
