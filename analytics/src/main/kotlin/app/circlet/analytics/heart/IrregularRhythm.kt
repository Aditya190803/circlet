// Ported from OpenStrap analytics/lib/src/onehz/clinical/irregular_rhythm.dart.
// CLINICAL — 24/7 irregular-rhythm SCREEN (NOT a diagnosis).

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.foundations.stddev
import kotlin.math.abs
import kotlin.math.sqrt

data class IrregularRhythm(
    val sd1: Double,
    val sd2: Double,
    val sd1sd2: Double,
    val pnnPct: Double,
    val nBeats: Int,
    val flag: Boolean,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "sd1_ms" to round6(sd1),
        "sd2_ms" to round6(sd2),
        "sd1_sd2" to round6(sd1sd2),
        "pnn_pct" to round6(pnnPct),
        "n_beats" to nBeats,
        "flag" to flag,
    )
}

/** Minimum clean beats required to run the screen. */
const val irregularScreenMinBeats = 500

/**
 * Pulse-derived irregular-rhythm SCREEN. Not a diagnosis. Both Poincaré
 * SD1/SD2 ≥ [sd1sd2Flag] and pNNx ≥ [pnnFlagPct] must hold. When [nnTimesMs]
 * is aligned with [rrMs], the same pair must hold in [sustainedFraction] of
 * short windows — a whole-day blend is not "sustained".
 */
fun irregularBeatScreen(
    rrMs: List<Double>,
    nnTimesMs: List<Double>? = null,
    artifactFraction: Double = 0.0,
    minBeats: Int = irregularScreenMinBeats,
    sd1sd2Flag: Double = 0.70,
    pnnThresholdMs: Double = 70.0,
    pnnFlagPct: Double = 30.0,
    maxArtifact: Double = 0.30,
    windowMinutes: Double = 5.0,
    minWindowBeats: Int = 40,
    sustainedFraction: Double = 0.5,
): OsMetric<IrregularRhythm> {
    val inputs = listOf("rr_cleaned")
    val keep = BooleanArray(rrMs.size) { rrMs[it] >= 300.0 && rrMs[it] <= 2000.0 }
    val nn = ArrayList<Double>()
    for (i in rrMs.indices) if (keep[i]) nn.add(rrMs[i])
    val hasTimes = nnTimesMs != null && nnTimesMs.size == rrMs.size
    val successive = BooleanArray(rrMs.size) { i ->
        i > 0 && keep[i] && keep[i - 1] &&
            (!hasTimes || nnTimesMs!![i] - nnTimesMs[i - 1] <= rrMs[i] + 0.5)
    }
    val nnAdjacent = ArrayList<Boolean>(nn.size)
    for (i in rrMs.indices) if (keep[i]) nnAdjacent.add(successive[i])
    val diffs = ArrayList<Double>()
    for (i in 1 until rrMs.size) {
        if (successive[i]) diffs.add(rrMs[i] - rrMs[i - 1])
    }
    if (diffs.size < minBeats - 1) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "too few clean beats for an irregular-rhythm screen",
        )
    }
    if (artifactFraction > maxArtifact) {
        val got = roundHalfAwayFromZero(artifactFraction * 100.0).toInt()
        val cap = roundHalfAwayFromZero(maxArtifact * 100.0).toInt()
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "artifact fraction $got% > $cap% — screen suppressed on noisy RR",
        )
    }
    val sdsd = stddev(diffs)
    val sdnn = stddev(nn)
    if (sdsd == null || sdnn == null) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "no successive clean beats to build a Poincare plot from",
        )
    }
    val sd1 = sdsd / sqrt(2.0)
    val v = 2.0 * sdnn * sdnn - sd1 * sd1
    val sd2 = if (v > 0.0) sqrt(v) else 0.0
    if (sd2 <= 0.0) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "no long-term variability (SD2 = 0) — the SD1/SD2 ratio is " +
                "undefined, not \"perfectly regular\"",
        )
    }
    val ratio = sd1 / sd2
    var over = 0
    for (d in diffs) if (abs(d) > pnnThresholdMs) over++
    val pnnPct = if (diffs.isEmpty()) 0.0 else 100.0 * over / diffs.size
    val aggregateHigh = ratio >= sd1sd2Flag && pnnPct >= pnnFlagPct
    val nnTimes = if (hasTimes) {
        val out = ArrayList<Double>(nn.size)
        for (i in rrMs.indices) if (keep[i]) out.add(nnTimesMs!![i])
        out
    } else {
        emptyList()
    }
    val flag = aggregateHigh && (
        !hasTimes || sustainedAcrossWindows(
            nn,
            nnTimes,
            nnAdjacent,
            sd1sd2Flag = sd1sd2Flag,
            pnnThresholdMs = pnnThresholdMs,
            pnnFlagPct = pnnFlagPct,
            windowMinutes = windowMinutes,
            minWindowBeats = minWindowBeats,
            sustainedFraction = sustainedFraction,
        )
        )
    val conf = (nn.size / 5000.0 * (1.0 - artifactFraction)).coerceIn(0.2, 0.9)
    val pnnLabel = roundHalfAwayFromZero(pnnThresholdMs).toInt()
    return OsMetric(
        value = IrregularRhythm(
            sd1 = sd1,
            sd2 = sd2,
            sd1sd2 = ratio,
            pnnPct = pnnPct,
            nBeats = nn.size,
            flag = flag,
        ),
        confidence = conf,
        tier = Tier.ESTIMATE,
        inputsUsed = inputs,
        note = "irregular-rhythm SCREEN (not a diagnosis): Poincaré SD1/SD2 + pNN" +
            "$pnnLabel. PRV not ECG — wrist pulse misses P-waves. " +
            "Discuss with a clinician only if you have symptoms.",
    )
}

private fun sustainedAcrossWindows(
    rrMs: List<Double>,
    timesMs: List<Double>,
    adjacent: List<Boolean>,
    sd1sd2Flag: Double,
    pnnThresholdMs: Double,
    pnnFlagPct: Double,
    windowMinutes: Double,
    minWindowBeats: Int,
    sustainedFraction: Double,
): Boolean {
    if (timesMs.size != rrMs.size || adjacent.size != rrMs.size || rrMs.size < 2) return false
    if (!windowMinutes.isFinite() || windowMinutes <= 0.0 || minWindowBeats < 2 ||
        !sustainedFraction.isFinite() || sustainedFraction < 0.0 || sustainedFraction > 1.0
    ) {
        return false
    }
    val windowMs = windowMinutes * 60000.0
    var windowStart = timesMs.first()
    var validWindows = 0
    var flaggedWindows = 0
    var bucket = ArrayList<Double>()
    var bucketAdjacent = ArrayList<Boolean>()
    fun flush() {
        val diffs = ArrayList<Double>()
        for (i in 1 until bucket.size) {
            if (bucketAdjacent[i]) diffs.add(bucket[i] - bucket[i - 1])
        }
        if (diffs.size >= minWindowBeats - 1) {
            validWindows++
            val sdsd = stddev(diffs)
            val sdnn = stddev(bucket)
            if (sdsd != null && sdnn != null) {
                val sd1 = sdsd / sqrt(2.0)
                val v = 2.0 * sdnn * sdnn - sd1 * sd1
                val sd2 = if (v > 0.0) sqrt(v) else 0.0
                if (sd2 > 0.0) {
                    val ratio = sd1 / sd2
                    var over = 0
                    for (d in diffs) if (abs(d) > pnnThresholdMs) over++
                    val pnn = 100.0 * over / diffs.size
                    if (ratio >= sd1sd2Flag && pnn >= pnnFlagPct) flaggedWindows++
                }
            }
        }
        bucket = ArrayList()
        bucketAdjacent = ArrayList()
    }
    for (i in rrMs.indices) {
        if (timesMs[i] - windowStart >= windowMs) {
            flush()
            windowStart = timesMs[i]
        }
        bucket.add(rrMs[i])
        bucketAdjacent.add(adjacent[i])
    }
    flush()
    if (validWindows == 0) return false
    return flaggedWindows.toDouble() / validWindows >= sustainedFraction
}
