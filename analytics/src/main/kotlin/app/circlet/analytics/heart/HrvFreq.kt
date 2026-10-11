// Ported from OpenStrap analytics/lib/src/onehz/clinical/hrv_freq.dart.
// CLINICAL TIER-1 — frequency-domain HRV via Lomb-Scargle on NATIVE beat times.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.freqGrid
import app.circlet.analytics.foundations.lombScargle
import app.circlet.analytics.foundations.orderedMap
import app.circlet.analytics.foundations.round6
import kotlin.math.ceil

data class HrvFreq(
    val ulf: Double? = null,
    val vlf: Double? = null,
    val lf: Double? = null,
    val hf: Double? = null,
    val total: Double? = null,
    /** Bands [total] summed. Null exactly when [total] is null. */
    val totalBands: List<String>? = null,
    val lfhf: Double? = null,
    val nuLf: Double? = null,
    val nuHf: Double? = null,
    val hfGated: Boolean,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (ulf != null) m["ulf"] = round6(ulf)
        if (vlf != null) m["vlf"] = round6(vlf)
        if (lf != null) m["lf"] = round6(lf)
        if (hf != null) m["hf"] = round6(hf)
        if (total != null) m["total"] = round6(total)
        if (totalBands != null) m["total_bands"] = totalBands
        if (lfhf != null) m["lf_hf"] = round6(lfhf)
        if (nuLf != null) m["nu_lf"] = round6(nuLf)
        if (nuHf != null) m["nu_hf"] = round6(nuHf)
        m["hf_gated"] = hfGated
        return m
    }
}

/**
 * Frequency-domain HRV from cleaned NN (Laguna 1998 / Bigger 1992).
 * Welch-averaged Lomb–Scargle on native beat times. A band the record cannot
 * resolve is null, never 0. HF (and total, LF/HF, nu) is withheld when
 * [artifactFraction] exceeds [hfArtifactGate].
 */
/** Artifact fraction above which HF (and the figures built on it) is withheld. */
const val HF_ARTIFACT_GATE_DEFAULT = 0.15

fun hrvFreq(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    artifactFraction: Double,
    hfArtifactGate: Double = HF_ARTIFACT_GATE_DEFAULT,
    oversample: Double = 4.0,
): OsMetric<HrvFreq> = hrvFreqBands(
    nnMs,
    nnTimesMs,
    artifactFraction,
    hfArtifactGate,
    oversample,
    wantUlf = true,
    wantVlf = true,
)

/**
 * [hrvFreq] with the slow bands optional. Each band is an independent Welch
 * average, so asking only for LF and HF returns the same LF and HF the full
 * call would. The day pipeline uses this: it keeps LF/HF/LF-HF and never
 * reads ULF or VLF, and VLF's long segments are most of the work.
 */
internal fun hrvFreqBands(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    artifactFraction: Double,
    hfArtifactGate: Double = HF_ARTIFACT_GATE_DEFAULT,
    oversample: Double = 4.0,
    wantUlf: Boolean = true,
    wantVlf: Boolean = true,
): OsMetric<HrvFreq> {
    val inputs = listOf("rr_cleaned", "beat_times")
    if (nnMs.size < 16 || nnTimesMs.size != nnMs.size) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few beats for a spectral estimate",
        )
    }
    val tSec = nnTimesMs.map { it / 1000.0 }
    val spanSec = tSec.last() - tSec.first()
    if (spanSec <= 0.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "degenerate beat times",
        )
    }
    val ulf = if (wantUlf) welchBandPower(tSec, nnMs, 0.0003, 0.003, oversample = oversample) else null
    val vlf = if (wantVlf) welchBandPower(tSec, nnMs, 0.003, 0.04, oversample = oversample) else null
    val lf = welchBandPower(tSec, nnMs, 0.04, 0.15, oversample = oversample)
    val hfRaw = welchBandPower(tSec, nnMs, 0.15, 0.40, oversample = oversample)
    if (lf == null && hfRaw == null) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "record too short to resolve any HRV band",
        )
    }
    val hfGated = artifactFraction > hfArtifactGate
    val hf = if (hfGated) null else hfRaw
    var lfhf: Double? = null
    var nuLf: Double? = null
    var nuHf: Double? = null
    if (lf != null && hf != null && (lf + hf) > 0.0) {
        lfhf = if (hf == 0.0) null else lf / hf
        nuLf = 100.0 * lf / (lf + hf)
        nuHf = 100.0 * hf / (lf + hf)
    }
    // Insertion order is the published totalBands order. An unresolved band is
    // absent from the sum, never entered as 0. A gated HF withholds total.
    val bands = linkedMapOf(
        "ulf" to ulf,
        "vlf" to vlf,
        "lf" to lf,
        "hf" to hfRaw,
    )
    val resolved = ArrayList<String>()
    for ((name, power) in bands) {
        if (power != null) resolved.add(name)
    }
    val total = if (hfGated || lf == null || hfRaw == null) {
        null
    } else {
        var s = 0.0
        for (b in resolved) s += bands[b]!!
        s
    }
    val conf = ((1.0 - artifactFraction) * (if (hfGated) 0.6 else 0.9)).coerceIn(0.2, 0.9)
    val note = if (hfGated) {
        "HF suppressed: artifact fraction ${round6(artifactFraction)} " +
            "> gate — LF/VLF reported, HF/LF-HF/nu/total withheld"
    } else {
        "PRV spectrum; HF band quantization-limited at 1 Hz"
    }
    return OsMetric(
        value = HrvFreq(
            ulf = ulf,
            vlf = vlf,
            lf = lf,
            hf = hf,
            total = total,
            totalBands = if (total == null) null else resolved,
            lfhf = lfhf,
            nuLf = nuLf,
            nuHf = nuHf,
            hfGated = hfGated,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = note,
    )
}

/**
 * Welch-averaged Lomb–Scargle power in [loHz, hiHz), or null when the record
 * is too short to resolve that band. Segment length is [cyclesPerSegment]
 * periods of [loHz]; grid spacing is that resolution divided by [oversample].
 */
private fun welchBandPower(
    tSec: List<Double>,
    y: List<Double>,
    loHz: Double,
    hiHz: Double,
    cyclesPerSegment: Double = 10.0,
    oversample: Double = 4.0,
    minPointsPerSegment: Int = 16,
): Double? {
    if (loHz <= 0.0 || hiHz <= loHz || tSec.size < minPointsPerSegment) return null
    val span = tSec.last() - tSec.first()
    val segSec = cyclesPerSegment / loHz
    if (span < segSec) return null
    val df = 1.0 / segSec / oversample
    val nGrid = ceil((hiHz - loHz) / df).toInt() + 1
    val grid = freqGrid(loHz, hiHz, nGrid)
    val step = segSec / 2.0
    val starts = ArrayList<Double>()
    var start = tSec.first()
    while (start + segSec <= tSec.last()) {
        starts.add(start)
        start += step
    }
    // Segments are independent; the average is folded in order, as before.
    val powers = orderedMap(starts.size, minTotal = 8) { s ->
        segmentBandPower(tSec, y, grid, loHz, hiHz, starts[s], segSec, minPointsPerSegment)
    }
    var sum = 0.0
    var k = 0
    for (power in powers) {
        if (power != null && power.isFinite()) {
            sum += power
            k++
        }
    }
    return if (k == 0) null else sum / k
}

/**
 * One Welch segment's band power, or null when the segment is too short,
 * too thin, too gappy, or its spectrum is undefined.
 */
private fun segmentBandPower(
    tSec: List<Double>,
    y: List<Double>,
    grid: List<Double>,
    loHz: Double,
    hiHz: Double,
    start: Double,
    segSec: Double,
    minPointsPerSegment: Int,
): Double? {
    val end = start + segSec
    val from = lowerBoundTime(tSec, start)
    val ts = ArrayList<Double>()
    val ys = ArrayList<Double>()
    var i = from
    while (i < tSec.size && tSec[i] < end) {
        ts.add(tSec[i])
        ys.add(y[i])
        i++
    }
    if (ts.size < minPointsPerSegment || ts.last() - ts.first() < segSec * 0.8) return null
    var maxGap = 0.0
    for (i in 1 until ts.size) {
        val g = ts[i] - ts[i - 1]
        if (g > maxGap) maxGap = g
    }
    if (maxGap > segSec * 0.2) return null
    val ls = lombScargle(ts, ys, grid) ?: return null
    return ls.bandPower(loHz, hiHz)
}

/** First index with time >= [v]. Beat times ascend, so segments start in O(log n). */
private fun lowerBoundTime(t: List<Double>, v: Double): Int {
    var lo = 0
    var hi = t.size
    while (lo < hi) {
        val m = (lo + hi) ushr 1
        if (t[m] < v) lo = m + 1 else hi = m
    }
    return lo
}
