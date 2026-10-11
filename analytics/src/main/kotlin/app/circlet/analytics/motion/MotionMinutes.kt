package app.circlet.analytics.motion

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.mean
import app.circlet.analytics.support.median
import app.circlet.analytics.support.needBaselineNote
import app.circlet.analytics.support.percentile
import java.util.Arrays
import kotlin.math.sqrt

// dynAmp is ported from OpenStrap lib/src/onehz/motion/enmo.dart because
// steps.dart's dailyActiveMinutes consumes MotionMinute.dynAmp and nothing
// else in this task produces it. ENMO itself is van Hees 2013; dynAmp is the
// per-axis high-pass Vähä-Ypyä argued for, so a cut-point survives sensor offset.
// dailyActiveMinutes is ported from motion/steps.dart. It returns minutes of
// sustained wrist movement and deliberately no step count: gait is above the
// Nyquist frequency of a 1 Hz stream.

internal const val PERSONAL_DYN_FLOOR_QUANTILE = 0.90
internal const val PERSONAL_DYN_FLOOR_MIN_MINUTES = 2000
internal const val PERSONAL_DYN_FLOOR_MIN_DAYS = 5
internal const val DAILY_STEP_MIN_COVERED_MINUTES = 4
internal const val DEFAULT_GRAVITY_WINDOW_S = 15.0

internal data class MotionMinute(
    val tsMinStartMs: Double,
    val nSamples: Int,
    val enmo: Double,
    val mad: Double,
    val meanMag: Double,
    val dynAmp: Double,
)

internal data class EnmoResult(
    val gRef: Double,
    val minutes: List<MotionMinute>,
    val coverage: Double,
)

internal data class AccelSample(
    val tsMs: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean = true,
)

internal data class DailyMovementEstimate(
    val activeMinutes: Int,
    val dynFloorG: Double,
    val coverage: Double,
    val boutCount: Int,
)

internal fun calibrateGRef(mags: List<Double>): Double {
    if (mags.isEmpty()) return 1.0
    if (mags.size == 1) return if (mags.first() > 0) mags.first() else 1.0
    // Primitive sorts, no boxed copies: the medians interpolate exactly like
    // median(), so the reference is unchanged.
    val steps = DoubleArray(mags.size - 1) { i -> kotlin.math.abs(mags[i + 1] - mags[i]) }
    Arrays.sort(steps)
    val stepThresh = percentileSortedArray(steps)
    var stillCount = 0
    for (i in 1 until mags.size) {
        if (kotlin.math.abs(mags[i] - mags[i - 1]) <= stepThresh) stillCount++
    }
    val g = if (stillCount >= 2) {
        val still = DoubleArray(stillCount)
        var k = 0
        for (i in 1 until mags.size) {
            if (kotlin.math.abs(mags[i] - mags[i - 1]) <= stepThresh) still[k++] = mags[i]
        }
        Arrays.sort(still)
        percentileSortedArray(still)
    } else {
        val all = DoubleArray(mags.size) { mags[it] }
        Arrays.sort(all)
        percentileSortedArray(all)
    }
    return if (g > 0) g else 1.0
}

/** Median of an ascending array: the percentile() interpolation at p=50. */
private fun percentileSortedArray(sorted: DoubleArray): Double {
    if (sorted.size == 1) return sorted[0]
    val rank = 0.5 * (sorted.size - 1)
    val lo = kotlin.math.floor(rank).toInt()
    val hi = kotlin.math.ceil(rank).toInt()
    if (lo == hi) return sorted[lo]
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

internal fun enmoSeries(
    samples: List<AccelSample>,
    gRef: Double? = null,
    minSamplesPerMinute: Int = 30,
    gravityWindowS: Double = DEFAULT_GRAVITY_WINDOW_S,
    expectedMinutes: Int? = null,
): EnmoResult {
    val valid = run {
        val filtered = samples.filter { it.valid }
        if (filtered.isOrdered()) filtered else filtered.sortedBy { it.tsMs }
    }
    if (valid.isEmpty()) return EnmoResult(gRef ?: 1.0, emptyList(), 0.0)
    val mags = valid.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
    val ref = gRef ?: calibrateGRef(mags)
    val dyn = DoubleArray(valid.size)
    val windowMs = gravityWindowS * 1000.0
    var lo = 0
    var sx = 0.0
    var sy = 0.0
    var sz = 0.0
    for (i in valid.indices) {
        val s = valid[i]
        sx += s.x
        sy += s.y
        sz += s.z
        while (lo < i && (s.tsMs - valid[lo].tsMs) >= windowMs) {
            sx -= valid[lo].x
            sy -= valid[lo].y
            sz -= valid[lo].z
            lo++
        }
        val n = i - lo + 1
        val dx = s.x - sx / n
        val dy = s.y - sy / n
        val dz = s.z - sz / n
        dyn[i] = sqrt(dx * dx + dy * dy + dz * dz)
    }
    val buckets = LinkedHashMap<Int, MutableList<Int>>()
    for (i in valid.indices) {
        val minIdx = (valid[i].tsMs / 60000.0).toInt()
        buckets.getOrPut(minIdx) { ArrayList() }.add(i)
    }
    val minutes = ArrayList<MotionMinute>()
    var covered = 0
    val keys = buckets.keys.sorted()
    for (k in keys) {
        val idxs = buckets.getValue(k)
        val magsMin = idxs.map { mags[it] }
        val meanMag = mean(magsMin)!!
        var enmoSum = 0.0
        for (m in magsMin) {
            val e = m - ref
            enmoSum += if (e > 0) e else 0.0
        }
        var madSum = 0.0
        for (m in magsMin) madSum += kotlin.math.abs(m - meanMag)
        var dynSum = 0.0
        for (i in idxs) dynSum += dyn[i]
        if (idxs.size >= minSamplesPerMinute) covered++
        minutes.add(
            MotionMinute(
                k * 60000.0,
                idxs.size,
                enmoSum / magsMin.size,
                madSum / magsMin.size,
                meanMag,
                dynSum / idxs.size,
            ),
        )
    }
    val span = if (minutes.isEmpty()) 0 else keys.last() - keys.first() + 1
    val denom = expectedMinutes ?: span
    val coverage = if (denom <= 0) 0.0 else (covered.toDouble() / denom).coerceIn(0.0, 1.0)
    return EnmoResult(ref, minutes, coverage)
}

internal fun personalDynFloor(
    pooledMinuteDynAmps: List<Double>,
    quantile: Double = PERSONAL_DYN_FLOOR_QUANTILE,
    minMinutes: Int = PERSONAL_DYN_FLOOR_MIN_MINUTES,
): Double? {
    val xs = pooledMinuteDynAmps.filter { it.isFinite() && it >= 0 }
    if (xs.size < minMinutes) return null
    val q = percentile(xs, quantile.coerceIn(0.0, 1.0) * 100.0) ?: return null
    if (!q.isFinite() || q <= 0) return null
    return q
}

internal fun personalDynFloorFromDailySummaries(
    dailyHighQuantiles: List<Double>,
    minDays: Int = PERSONAL_DYN_FLOOR_MIN_DAYS,
): Double? {
    val xs = dailyHighQuantiles.filter { it.isFinite() && it > 0 }
    if (xs.size < minDays) return null
    val m = median(xs) ?: return null
    if (!m.isFinite() || m <= 0) return null
    return m
}

internal fun dailyActiveMinutes(
    motion: List<MotionMinute>,
    personalDynFloorG: Double?,
    minSamplesPerMinute: Double = 30.0,
    minBoutMin: Int = 3,
    historyDaysAvailable: Int = 0,
    expectedMinutes: Int? = null,
): Scored<DailyMovementEstimate> {
    if (motion.isEmpty()) return Scored.absent("no motion minutes")
    val floor = personalDynFloorG
    if (floor == null || !floor.isFinite() || floor <= 0) {
        return Scored.absent(needBaselineNote(historyDaysAvailable, PERSONAL_DYN_FLOOR_MIN_DAYS))
    }
    val idx = ArrayList<Int>()
    val dyns = ArrayList<Double>()
    for (i in motion.indices) {
        if (motion[i].nSamples >= minSamplesPerMinute) {
            idx.add(i)
            dyns.add(motion[i].dynAmp)
        }
    }
    val covered = idx.size
    val spanMinutes = ((motion.last().tsMinStartMs - motion.first().tsMinStartMs) / 60000.0).let {
        kotlin.math.round(it).toInt()
    } + 1
    val denom = expectedMinutes ?: spanMinutes
    val coverage = if (denom <= 0) 0.0 else (covered.toDouble() / denom).coerceIn(0.0, 1.0)
    if (covered < DAILY_STEP_MIN_COVERED_MINUTES) {
        return Scored.absent(
            "too few covered minutes to estimate activity (have=$covered, need=$DAILY_STEP_MIN_COVERED_MINUTES)",
        )
    }
    val gateOk = BooleanArray(idx.size) { dyns[it] > floor }
    var activeMin = 0
    var boutCount = 0
    var k = 0
    while (k < idx.size) {
        if (!gateOk[k]) {
            k++
            continue
        }
        var end = k
        while (end + 1 < idx.size && gateOk[end + 1] &&
            ((motion[idx[end + 1]].tsMinStartMs - motion[idx[end]].tsMinStartMs) / 60000.0).let {
                kotlin.math.round(it).toInt()
            } == 1
        ) {
            end++
        }
        if (end - k + 1 >= minBoutMin) {
            activeMin += end - k + 1
            boutCount++
        }
        k = end + 1
    }
    val conf = (0.30 * (coverage / 0.6).coerceIn(0.3, 1.0)).coerceIn(0.1, 0.30)
    return Scored.of(
        DailyMovementEstimate(activeMin, floor, coverage, boutCount),
        conf,
        "ESTIMATE: minutes of sustained wrist movement. Deliberately not a step count.",
    )
}

/** True when no sample precedes an earlier one; such an input sorts to itself. */
private fun List<AccelSample>.isOrdered(): Boolean {
    for (i in 1 until size) {
        if (this[i].tsMs < this[i - 1].tsMs) return false
    }
    return true
}
