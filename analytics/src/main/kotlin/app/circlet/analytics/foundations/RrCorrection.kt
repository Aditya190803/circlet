// Ported from OpenStrap analytics/lib/src/onehz/foundations/rr_correction.dart.
// Lipponen & Tarvainen 2019 RR artefact correction (dRR / mRR / sRR, Kubios-style),
// with Peltola 2012's correction policy: spline-correct an isolated beat, drop a run.
// The output clock is wall time. A sensor dropout is re-anchored from the beat timestamps;
// summing RR across a hole the band never reported splices the record.

package app.circlet.analytics.foundations

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BeatClass {
    NORMAL,
    ECTOPIC,
    LONG_SHORT,
    MISSED,
    EXTRA,
}

data class RrCorrectionResult(
    /** Cleaned NN intervals, milliseconds. Isolated artefacts are spline-corrected; runs are dropped. */
    val nn: List<Double>,
    /**
     * Beat-end time of each kept NN, milliseconds from the start of the first input beat.
     * Pass [correctRr]'s timestamps or this is only a cumulative sum and dropouts are spliced out.
     */
    val nnTimesMs: List<Double>,
    val classes: List<BeatClass>,
    val cleanFraction: Double,
    val droppedCount: Int,
    val correctedCount: Int,
)

/**
 * Lipponen–Tarvainen correction.
 *
 * [rrTsMs] must be the beat timestamps that came with [rrMs] (same length, epoch ms).
 * Without them the clock is Σrr. Inside a contiguous run the clock still sums RR, because
 * the stored timestamps are whole seconds and the intervals are the only sub-second information.
 * A wall step that exceeds the interval by more than [reanchorGapMs] is a dropout and re-anchors.
 */
fun correctRr(
    rrMs: List<Double>,
    rrTsMs: List<Double>? = null,
    alpha: Double = 5.2,
    windowBeats: Int = 91,
    minThresholdMs: Double = 100.0,
    reanchorGapMs: Double = 1000.0,
): RrCorrectionResult {
    val n = rrMs.size
    val tAll = beatTimes(rrMs, rrTsMs, reanchorGapMs)
    if (n == 0) {
        return RrCorrectionResult(
            nn = emptyList(),
            nnTimesMs = emptyList(),
            classes = emptyList(),
            cleanFraction = 0.0,
            droppedCount = 0,
            correctedCount = 0,
        )
    }
    if (n < 3) {
        val classes = rrMs.map { rr ->
            if (rr >= 300.0 && rr <= 2000.0) BeatClass.NORMAL else BeatClass.LONG_SHORT
        }
        val nn = ArrayList<Double>()
        val times = ArrayList<Double>()
        for (i in 0 until n) {
            if (classes[i] == BeatClass.NORMAL) {
                nn.add(rrMs[i])
                times.add(tAll[i])
            }
        }
        return RrCorrectionResult(
            nn = nn,
            nnTimesMs = times,
            classes = classes,
            cleanFraction = nn.size.toDouble() / n,
            droppedCount = n - nn.size,
            correctedCount = 0,
        )
    }

    val dRr = DoubleArray(n)
    for (i in 1 until n) dRr[i] = rrMs[i] - rrMs[i - 1]
    val th1 = slidingThreshold(dRr, windowBeats, alpha, minThresholdMs)

    val med = slidingMedian(rrMs, windowBeats)
    val mRr = DoubleArray(n) { i ->
        val d = rrMs[i] - med[i]
        if (d < 0.0) d * 2.0 else d
    }
    val th2 = slidingThreshold(mRr, windowBeats, alpha, minThresholdMs)

    val classes = Array(n) { BeatClass.NORMAL }
    for (i in 0 until n) {
        val hardLong = rrMs[i] > 2000.0
        val hardShort = rrMs[i] < 300.0
        val bigJump = abs(dRr[i]) > th1[i]
        val bigDev = abs(mRr[i]) > th2[i]
        classes[i] = when {
            hardLong || (bigDev && mRr[i] > 0.0) ->
                if (med[i] > 0.0 && rrMs[i] > 1.5 * med[i]) BeatClass.MISSED else BeatClass.LONG_SHORT
            hardShort || (bigDev && mRr[i] < 0.0) ->
                if (med[i] > 0.0 && rrMs[i] < 0.6 * med[i]) BeatClass.EXTRA else BeatClass.LONG_SHORT
            bigJump -> BeatClass.ECTOPIC
            else -> BeatClass.NORMAL
        }
    }

    // A recovery beat after one artefact is the other half of a compensatory pair.
    // Demote it so the event stays a single isolated beat rather than a two-beat run.
    for (k in 1 until n) {
        if (classes[k] != BeatClass.ECTOPIC) continue
        val prevBad = classes[k - 1] == BeatClass.EXTRA ||
            classes[k - 1] == BeatClass.MISSED ||
            classes[k - 1] == BeatClass.ECTOPIC ||
            classes[k - 1] == BeatClass.LONG_SHORT
        if (!prevBad) continue
        val oppositeSign = dRr[k] * dRr[k - 1] < 0.0
        val valueNormal = med[k] > 0.0 &&
            rrMs[k] >= 300.0 &&
            rrMs[k] <= 2000.0 &&
            abs(rrMs[k] - med[k]) <= 0.2 * med[k]
        if (oppositeSign && valueNormal) classes[k] = BeatClass.NORMAL
    }

    val isArtifact = BooleanArray(n) { classes[it] != BeatClass.NORMAL }
    val nn = ArrayList<Double>()
    val times = ArrayList<Double>()
    var dropped = 0
    var corrected = 0
    var i = 0
    while (i < n) {
        if (!isArtifact[i]) {
            nn.add(rrMs[i])
            times.add(tAll[i])
            i++
            continue
        }
        var j = i
        while (j < n && isArtifact[j]) j++
        val runLen = j - i
        if (runLen == 1) {
            val corr = splineCorrect(rrMs, isArtifact, i)
            if (corr != null) {
                // The interpolated NN replaces the value. The beat keeps its real time:
                // advancing by the shorter corrected interval would delete record.
                nn.add(corr)
                times.add(tAll[i])
                corrected++
            } else {
                dropped++
            }
        } else {
            dropped += runLen
        }
        i = j
    }

    val normalCount = classes.count { it == BeatClass.NORMAL }
    return RrCorrectionResult(
        nn = nn,
        nnTimesMs = times,
        classes = classes.toList(),
        cleanFraction = normalCount.toDouble() / n,
        droppedCount = dropped,
        correctedCount = corrected,
    )
}

/**
 * Beat-end time for every input beat, relative to the start of beat 0.
 * Re-anchors when this beat's own wall step exceeds its interval by [reanchorGapMs].
 * The test is local, so accumulated cumsum-versus-wall drift inside a run does not fire it.
 * There is no global RR clock scale: gen4's Σrr deficit is not decidable from the export.
 */
private fun beatTimes(
    rrMs: List<Double>,
    rrTsMs: List<Double>?,
    reanchorGapMs: Double,
): DoubleArray {
    val n = rrMs.size
    val t = DoubleArray(n)
    if (n == 0) return t
    val wall = rrTsMs != null && rrTsMs.size == n
    val origin = if (wall) rrTsMs[0] - rrMs[0] else 0.0
    var cur = 0.0
    for (i in 0 until n) {
        val dropout = wall && i > 0 &&
            (rrTsMs[i] - rrTsMs[i - 1]) - rrMs[i] > reanchorGapMs
        val anchored = if (wall) rrTsMs[i] - origin else 0.0
        cur = if (dropout && anchored > cur) anchored else cur + rrMs[i]
        t[i] = cur
    }
    return t
}

/**
 * α times the quartile deviation of the signed series. The deviation is taken on the
 * signed values: folding onto |dRR| collapses it and the detector becomes a fixed 100 ms cut.
 */
private fun slidingThreshold(
    x: DoubleArray,
    win: Int,
    alpha: Double,
    floorMs: Double,
): DoubleArray {
    val n = x.size
    val out = DoubleArray(n)
    val half = win / 2
    val seg = ArrayList<Double>(win)
    for (i in 0 until n) {
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        seg.clear()
        for (k in lo..hi) seg.add(x[k])
        val q1 = percentile(seg, 25.0) ?: 0.0
        val q3 = percentile(seg, 75.0) ?: 0.0
        val qd = (q3 - q1) / 2.0
        out[i] = max(alpha * qd, floorMs)
    }
    return out
}

private fun slidingMedian(x: List<Double>, win: Int): DoubleArray {
    val n = x.size
    val out = DoubleArray(n)
    val half = win / 2
    val seg = ArrayList<Double>(win)
    for (i in 0 until n) {
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        seg.clear()
        for (k in lo..hi) {
            if (k == i) continue
            seg.add(x[k])
        }
        out[i] = median(seg) ?: x[i]
    }
    return out
}

/** Catmull–Rom at t = 0.5 between the nearest normal beats. Null when an anchor side is missing. */
private fun splineCorrect(rr: List<Double>, isArtifact: BooleanArray, idx: Int): Double? {
    val left = ArrayList<Double>(2)
    var k = idx - 1
    while (k >= 0 && left.size < 2) {
        if (!isArtifact[k]) left.add(0, rr[k])
        k--
    }
    val right = ArrayList<Double>(2)
    k = idx + 1
    while (k < rr.size && right.size < 2) {
        if (!isArtifact[k]) right.add(rr[k])
        k++
    }
    if (left.isEmpty() || right.isEmpty()) return null
    val p1 = left.last()
    val p2 = right.first()
    val p0 = if (left.size >= 2) left.first() else p1
    val p3 = if (right.size >= 2) right.last() else p2
    val t = 0.5
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * (
        (2 * p1) +
            (-p0 + p2) * t +
            (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
            (-p0 + 3 * p1 - 3 * p2 + p3) * t3
        )
}
