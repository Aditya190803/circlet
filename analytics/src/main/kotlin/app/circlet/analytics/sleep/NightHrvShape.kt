// Ported from OpenStrap analytics/lib/src/onehz/sleep/night_hrv_shape.dart.
// Per-bin nocturnal RMSSD shape: a description of the night, never a cause.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.heart.RrCoverage
import app.circlet.analytics.heart.hrvTime
import app.circlet.analytics.heart.kRrCoverageCeiling
import app.circlet.analytics.heart.rrCoverage
import app.circlet.analytics.heart.rrOvercountNote
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** One ~30-min bin. A bin under the beat floor stays in the series as a hole. */
data class NightHrvBin(
    /** Bin start, seconds from the first beat of the night. */
    val startSec: Int,
    val nBeats: Int,
    val rmssdMs: Double?,
    val loMs: Double?,
    val hiMs: Double?,
) {
    val present: Boolean get() = rmssdMs != null

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "t" to startSec,
        "n_beats" to nBeats,
        "rmssd_ms" to rmssdMs?.let { round6(it) },
        "lo_ms" to loMs?.let { round6(it) },
        "hi_ms" to hiMs?.let { round6(it) },
    )
}

data class NightHrvShape(
    val bins: List<NightHrvBin>,
    /** Mean RMSSD of the present bins in the first third. Null under [MIN_BINS_PER_THIRD]. */
    val firstThirdMs: Double?,
    val lastThirdMs: Double?,
    /** [lastThirdMs] / [firstThirdMs]. Null when either side is, or the first third is 0. */
    val lastOverFirst: Double?,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "bins" to bins.map { it.toJson() },
        "first_third_ms" to firstThirdMs?.let { round6(it) },
        "last_third_ms" to lastThirdMs?.let { round6(it) },
        "last_over_first" to lastOverFirst?.let { round6(it) },
    )
}

private const val MIN_BINS_PER_THIRD = 2

/** Beats a bin needs before it publishes an RMSSD. */
const val kMinBeatsPerHrvBin = 300

private val shapeInputs = listOf("rr_cleaned", "beat_times")

/**
 * Per-bin RMSSD across the night.
 *
 * [nnMs] / [nnTimesMs] are a cleaned NN series (run `correctRr` first) and must
 * be the same length, time-ordered. [coverage] is [rrCoverage] of the raw RR
 * that NN was cleaned from. When it banks more beat-time than elapsed
 * ([kRrCoverageCeiling], [RrCoverage.overCounted]) the shape is absent, as
 * every RMSSD is. A bin under [minBeatsPerBin] abstains and stays in the series.
 * There is no time-to-max: that anchor was dropped on purpose.
 */
fun nightHrvShape(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    binMin: Double = 30.0,
    minBeatsPerBin: Int = kMinBeatsPerHrvBin,
    coverage: RrCoverage? = null,
): OsMetric<NightHrvShape> {
    // Same refusal as RrCoverage.overCounted (rrCoverage: beat-time / elapsed > kRrCoverageCeiling).
    if (coverage != null && (coverage.overCounted || coverage.coverage > kRrCoverageCeiling)) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = shapeInputs,
            note = rrOvercountNote(coverage),
        )
    }
    if (nnMs.size != nnTimesMs.size || nnMs.size < minBeatsPerBin) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = shapeInputs,
            note = "too few beats for a nightly shape " +
                "(${nnMs.size}, need ≥$minBeatsPerBin)",
        )
    }
    val binMs = binMin * 60000.0
    val t0 = nnTimesMs.first()
    val span = nnTimesMs.last() - t0
    val nBins = max(1, ceil(span / binMs).toInt())
    val binLabel = roundHalfAwayFromZero(binMin).toInt()
    if (nBins < 3) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = shapeInputs,
            note = "night spans ${dartFixed(span / 3600000.0, 1)} h — under three " +
                "$binLabel-min bins there is no shape to describe",
        )
    }

    val bins = ArrayList<NightHrvBin>(nBins)
    var k = 0
    for (b in 0 until nBins) {
        val hi = t0 + (b + 1) * binMs
        val start = k
        while (k < nnTimesMs.size && nnTimesMs[k] < hi) k++
        val n = k - start
        val startSec = roundHalfAwayFromZero(b * binMs / 1000.0).toInt()
        if (n < minBeatsPerBin) {
            bins.add(
                NightHrvBin(
                    startSec = startSec,
                    nBeats = n,
                    rmssdMs = null,
                    loMs = null,
                    hiMs = null,
                ),
            )
            continue
        }
        val m = hrvTime(
            nnMs.subList(start, k).toList(),
            nnTimesMs = nnTimesMs.subList(start, k).toList(),
        )
        val rmssd = m.value?.rmssd
        val se = if (rmssd == null) null else rmssd / sqrt(2.0 * n)
        bins.add(
            NightHrvBin(
                startSec = startSec,
                nBeats = n,
                rmssdMs = rmssd,
                loMs = if (se == null || rmssd == null) null else max(0.0, rmssd - 1.96 * se),
                hiMs = if (se == null || rmssd == null) null else rmssd + 1.96 * se,
            ),
        )
    }

    val third = bins.size / 3
    val first = meanOfPresent(bins.take(third))
    val last = meanOfPresent(bins.takeLast(third))
    val ratio = if (first == null || last == null || first == 0.0) null else last / first
    val presentBins = bins.count { it.present }
    return OsMetric(
        value = NightHrvShape(
            bins = bins,
            firstThirdMs = first,
            lastThirdMs = last,
            lastOverFirst = ratio,
        ),
        confidence = (0.8 * presentBins / bins.size).coerceIn(0.0, 0.8),
        tier = Tier.HIGH,
        inputsUsed = shapeInputs,
        note = "per-bin RMSSD ($binLabel-min bins, PRV not ECG-HRV) — a " +
            "DESCRIPTION of the night, never a cause. A low first third is equally " +
            "consistent with alcohol, a late meal, late training, a warm room, " +
            "illness onset, or nothing. Render each bin as a band, not a point.",
    )
}

private fun meanOfPresent(xs: List<NightHrvBin>): Double? {
    val v = ArrayList<Double>()
    for (b in xs) {
        if (b.present) v.add(b.rmssdMs!!)
    }
    return if (v.size < MIN_BINS_PER_THIRD) null else mean(v)
}

private fun dartFixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x == Double.POSITIVE_INFINITY) return "Infinity"
    if (x == Double.NEGATIVE_INFINITY) return "-Infinity"
    var scale = 1.0
    repeat(digits) { scale *= 10.0 }
    val neg = x < 0.0
    val scaled = roundHalfAwayFromZero(kotlin.math.abs(x) * scale).toLong()
    val pow = scale.toLong()
    val whole = scaled / pow
    val frac = (scaled % pow).toString().padStart(digits, '0')
    val sign = if (neg && scaled != 0L) "-" else ""
    return "$sign$whole.$frac"
}
