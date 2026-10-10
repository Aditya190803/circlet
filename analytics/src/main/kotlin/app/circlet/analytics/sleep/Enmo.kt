// Ported from OpenStrap analytics/lib/src/onehz/motion/enmo.dart.
// van Hees 2013 ENMO, Vähä-Ypyä 2015 MAD, and calibration-invariant dynAmp.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.percentile
import app.circlet.analytics.foundations.round6
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Per-minute motion from a 1 Hz accel stream.
 *
 * [enmo] and [mad] are only as good as [EnmoResult.gRef]. On this substrate
 * prefer [dynAmp]: a trailing per-axis mean removes constant bias and the
 * gravity projection of the current posture. A uniform gain only rescales it.
 * 1 Hz accel is an amplitude index — not steps, cadence, or METs.
 */
data class MotionMinute(
    val tsMinStartMs: Double,
    val nSamples: Int,
    val enmo: Double,
    val mad: Double,
    val meanMag: Double,
    val dynAmp: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "ts_min_start_ms" to tsMinStartMs,
        "n" to nSamples,
        "enmo_g" to round6(enmo),
        "mad_g" to round6(mad),
        "mean_mag_g" to round6(meanMag),
        "dyn_amp_g" to round6(dynAmp),
    )
}

data class EnmoResult(
    val gRef: Double,
    val minutes: List<MotionMinute>,
    val coverage: Double,
)

/**
 * Personal percentile cut-points, in g, on the moving distribution.
 * Null when too few moving minutes exist to set them. Never NaN.
 */
data class IntensityBands(
    val lightCut: Double?,
    val moderateCut: Double?,
    val vigorousCut: Double?,
    val labels: List<String>,
    val minutesInBand: Map<String, Int>,
)

/** Frozen personal cut-points. Must be strictly increasing. */
data class FrozenCuts(
    val light: Double,
    val moderate: Double,
    val vigorous: Double,
)

/**
 * Median magnitude of the stillest steps, else the overall median, else 1 g.
 * Never returns a non-positive reference.
 */
fun calibrateGRef(mags: List<Double>): Double {
    if (mags.isEmpty()) return 1.0
    if (mags.size == 1) return if (mags[0] > 0.0) mags[0] else 1.0
    val steps = ArrayList<Double>(mags.size - 1)
    for (i in 1 until mags.size) {
        steps.add(abs(mags[i] - mags[i - 1]))
    }
    val stepThresh = median(steps) ?: 0.0
    val still = ArrayList<Double>()
    for (i in 1 until mags.size) {
        if (abs(mags[i] - mags[i - 1]) <= stepThresh) still.add(mags[i])
    }
    val g = (if (still.size >= 2) median(still) else median(mags)) ?: 1.0
    return if (g > 0.0) g else 1.0
}

/** Trailing window (s) treated as gravity plus constant per-axis bias. */
const val defaultGravityWindowS = 15.0

/**
 * ENMO, MAD, and dynAmp in one pass.
 *
 * Minutes are bucketed by wall-clock `tsMs` after sorting, so the high-pass
 * is causal. Invalid samples are dropped. The trailing mean uses whatever
 * samples actually fall inside [gravityWindowS]; a gap longer than the window
 * empties it instead of carrying a stale posture across. The first sample's
 * dynAmp is 0 because it is its own mean.
 *
 * Coverage divides by the elapsed minute span (or [expectedMinutes] when the
 * caller knows the day), not by the count of minutes that happened to have a sample.
 */
fun enmoSeries(
    samples: List<AccelSample>,
    gRef: Double? = null,
    minSamplesPerMinute: Int = 30,
    gravityWindowS: Double = defaultGravityWindowS,
    expectedMinutes: Int? = null,
): EnmoResult {
    val valid = samples.filter { it.valid }.sortedBy { it.tsMs }
    if (valid.isEmpty()) return EnmoResult(gRef ?: 1.0, emptyList(), 0.0)

    val mags = ArrayList<Double>(valid.size)
    for (s in valid) {
        mags.add(sqrt(s.x * s.x + s.y * s.y + s.z * s.z))
    }
    val ref = gRef ?: calibrateGRef(mags)

    val dyn = MutableList(valid.size) { 0.0 }
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
        val nWin = i - lo + 1
        val dx = s.x - sx / nWin
        val dy = s.y - sy / nWin
        val dz = s.z - sz / nWin
        dyn[i] = sqrt(dx * dx + dy * dy + dz * dz)
    }

    val buckets = HashMap<Int, MutableList<Int>>()
    for (i in valid.indices) {
        val minIdx = floor(valid[i].tsMs / 60000.0).toInt()
        buckets.getOrPut(minIdx) { ArrayList() }.add(i)
    }

    val minutes = ArrayList<MotionMinute>()
    var covered = 0
    val keys = buckets.keys.sorted()
    for (k in keys) {
        val idxs = buckets.getValue(k)
        val magsMin = ArrayList<Double>(idxs.size)
        for (i in idxs) magsMin.add(mags[i])
        val meanMag = mean(magsMin)!!
        var enmoSum = 0.0
        for (m in magsMin) {
            val e = m - ref
            enmoSum += if (e > 0.0) e else 0.0
        }
        val enmo = enmoSum / magsMin.size
        var madSum = 0.0
        for (m in magsMin) madSum += abs(m - meanMag)
        val mad = madSum / magsMin.size
        var dynSum = 0.0
        for (i in idxs) dynSum += dyn[i]
        val dynAmp = dynSum / idxs.size
        if (idxs.size >= minSamplesPerMinute) covered++
        minutes.add(
            MotionMinute(
                k * 60000.0,
                idxs.size,
                enmo,
                mad,
                meanMag,
                dynAmp,
            ),
        )
    }
    val spanMinutes = if (minutes.isEmpty()) 0 else keys.last() - keys.first() + 1
    val denom = expectedMinutes ?: spanMinutes
    val coverage = if (denom <= 0) 0.0 else (covered.toDouble() / denom).coerceIn(0.0, 1.0)
    return EnmoResult(ref, minutes, coverage)
}

private val bandInputs = listOf("enmo_per_min")

/**
 * Relative intensity bands from per-minute ENMO. Percentile-of-you, not METs.
 *
 * Without [frozenCuts] the cut-points are the 50/75/90th percentiles of THIS
 * input's moving minutes, which relabels a rest day. Pass cut-points frozen
 * over a pooled history. Non-monotone cuts are refused, not reordered.
 * Minutes at or under [sedentaryEnmo] are sedentary.
 */
fun relativeIntensityBands(
    enmoPerMin: List<Double>,
    sedentaryEnmo: Double = 0.01,
    frozenCuts: FrozenCuts? = null,
): OsMetric<IntensityBands> {
    if (enmoPerMin.isEmpty()) {
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = bandInputs,
            note = "no ENMO minutes",
        )
    }
    if (frozenCuts != null) {
        if (!(frozenCuts.light < frozenCuts.moderate && frozenCuts.moderate < frozenCuts.vigorous)) {
            return OsMetric.absent(
                tier = Tier.RELATIVE,
                inputsUsed = bandInputs,
                note = "frozen cut-points not strictly increasing",
            )
        }
        return labelWithCuts(
            enmoPerMin,
            sedentaryEnmo,
            frozenCuts.light,
            frozenCuts.moderate,
            frozenCuts.vigorous,
            0.8,
            "RELATIVE within-user intensity vs FROZEN personal cut-points; NOT METs",
        )
    }
    val moving = enmoPerMin.filter { it > sedentaryEnmo }
    if (moving.size < 4) {
        val labels = ArrayList<String>(enmoPerMin.size)
        for (e in enmoPerMin) labels.add(if (e > sedentaryEnmo) "light" else "sedentary")
        val counts = bandCounts()
        for (l in labels) counts[l] = counts.getValue(l) + 1
        return OsMetric(
            value = IntensityBands(null, null, null, labels, counts),
            confidence = 0.25,
            tier = Tier.RELATIVE,
            inputsUsed = bandInputs,
            note = "too few moving minutes for personal cut-points; RELATIVE, not METs",
        )
    }
    return labelWithCuts(
        enmoPerMin,
        sedentaryEnmo,
        percentile(moving, 50.0)!!,
        percentile(moving, 75.0)!!,
        percentile(moving, 90.0)!!,
        (moving.size / 60.0).coerceIn(0.3, 0.8),
        "RELATIVE within-user intensity (50/75/90th moving pct OF THIS INPUT — " +
            "cut-points not frozen); NOT METs",
    )
}

private fun labelWithCuts(
    enmoPerMin: List<Double>,
    sedentaryEnmo: Double,
    light: Double,
    moderate: Double,
    vigorous: Double,
    confidence: Double,
    note: String,
): OsMetric<IntensityBands> {
    val labels = ArrayList<String>(enmoPerMin.size)
    val counts = bandCounts()
    for (e in enmoPerMin) {
        val l = when {
            e <= sedentaryEnmo -> "sedentary"
            e >= vigorous -> "vigorous"
            e >= moderate -> "moderate"
            else -> "light"
        }
        labels.add(l)
        counts[l] = counts.getValue(l) + 1
    }
    return OsMetric(
        value = IntensityBands(light, moderate, vigorous, labels, counts),
        confidence = confidence,
        tier = Tier.RELATIVE,
        inputsUsed = bandInputs,
        note = note,
    )
}

private fun bandCounts(): MutableMap<String, Int> = linkedMapOf(
    "sedentary" to 0,
    "light" to 0,
    "moderate" to 0,
    "vigorous" to 0,
)
