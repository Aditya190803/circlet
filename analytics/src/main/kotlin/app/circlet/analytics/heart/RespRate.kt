// Ported from OpenStrap analytics/lib/src/onehz/respiration/resp_rate.dart.
// RESPIRATION TIER-1 — 24/7 respiratory rate from the 1 Hz substrate.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.FusionInput
import app.circlet.analytics.foundations.LombScargle
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.freqGrid
import app.circlet.analytics.foundations.inverseVarianceFuse
import app.circlet.analytics.foundations.lombScargle
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.olsFit
import app.circlet.analytics.foundations.orderedMap
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.sampleCadenceSeconds
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Lower edge of the adult respiratory band on a 1 Hz signal: 6 br/min. */
const val respLoHz = 0.1

/** 1 Hz Nyquist cap: 30 br/min. Nothing downstream claims a higher rate. */
const val respHiHz = 0.5

/** Classic HRV HF band, 9 br/min. Not the RSA search ceiling. */
const val rsaLoHz = 0.15

/** Classic HRV HF band top, 24 br/min. Not the RSA search ceiling. */
const val rsaHiHz = 0.40

/**
 * Highest respiratory frequency an RSA window can resolve (Hz).
 * Tachogram Nyquist is 0.5 / beat interval (DeBoer, Karemaker & Strackee 1984),
 * capped at [respHiHz]. [beatIntervalSec] is the median NN, not span/(n−1).
 */
fun rsaCeilingHz(beatIntervalSec: Double): Double {
    if (!beatIntervalSec.isFinite() || beatIntervalSec <= 0.0) return respHiHz
    val nyq = 0.5 / beatIntervalSec
    return if (nyq < respHiHz) nyq else respHiHz
}

/** One respiratory-rate estimate, breaths/min, plus provenance. */
class RespEstimate(
    val brpm: Double?,
    val peakHz: Double?,
    val power: Double?,
    val source: String,
    val usableSubwindows: Int? = null,
    val subwindows: Int? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "brpm" to if (brpm == null) null else round6(brpm),
        )
        if (peakHz != null) m["peak_hz"] = round6(peakHz)
        if (power != null) m["power"] = round6(power)
        m["source"] = source
        if (usableSubwindows != null) m["usable_subwindows"] = usableSubwindows
        if (subwindows != null) m["subwindows"] = subwindows
        return m
    }
}

/** Longest span (seconds) one RSA periodogram may cover. Welch 1967. */
const val rsaSegmentSec = 300.0

/** Usable sub-windows at which confidence stops being discounted. ≈ 1 h. */
private const val rsaFullWeightSubwindows = 23.0

/**
 * RSA respiratory rate from cleaned NN beat times. The night's rate is the
 * medoid of ~[rsaSegmentSec] Lomb–Scargle peaks; withheld unless those
 * sub-windows agree. A rate at the beat-rate Nyquist is absent, not a number.
 */
fun rsaRespRate(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    artifactFraction: Double,
    tolBrpm: Double = 2.0,
    minConsensus: Double = 0.5,
    maxArtifact: Double = 0.30,
): OsMetric<RespEstimate> {
    val inputs = listOf("rr_cleaned", "beat_times")
    if (nnMs.size < 20 || nnTimesMs.size != nnMs.size) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few beats for an RSA spectral estimate (need ≥20)",
        )
    }
    if (artifactFraction > maxArtifact) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "artifact fraction ${round6(artifactFraction)} > gate " +
                "— RSA peak unreliable",
        )
    }
    val tSec = DoubleArray(nnTimesMs.size) { nnTimesMs[it] / 1000.0 }
    val spanSec = tSec.last() - tSec.first()
    if (spanSec <= 0.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "degenerate beat times",
        )
    }
    val beatSec = median(nnMs)!! / 1000.0
    val hiHz = rsaCeilingHz(beatSec)
    if (hiHz < rsaHiHz) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "sleeping heart rate ${round6(60.0 / beatSec)} bpm (median beat " +
                "interval ${round6(beatSec)} s) can resolve breathing from beat " +
                "timing only up to ${round6(hiHz * 60.0)} br/min, below the top of the " +
                "normal 9–24 br/min band, so any peak could be an alias; rate " +
                "withheld",
        )
    }
    var segSec = rsaSegmentSec
    if (spanSec < 2.0 * segSec) segSec = spanSec / 2.0
    if (segSec < 60.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "window spans ${round6(spanSec)} s — needs ≥120 s to test the " +
                "respiratory peak against itself over time",
        )
    }
    val peaks = ArrayList<Double>()
    val peakHz = ArrayList<Double>()
    val peakPwr = ArrayList<Double>()
    // Sub-windows are independent; they are scored in order and folded below
    // exactly as the sequential loop did.
    val bounds = ArrayList<Pair<Int, Int>>()
    var s = tSec.first()
    while (s + segSec <= tSec.last() + 1e-9) {
        bounds.add(lowerBound(tSec, s) to lowerBound(tSec, s + segSec))
        s += segSec / 2.0
    }
    val outcomes = orderedMap(bounds.size, minTotal = 8) { b ->
        scoreRsaWindow(tSec, nnMs, segSec, bounds[b].first, bounds[b].second)
    }
    var atCeiling = 0
    var ceilingLo = Double.POSITIVE_INFINITY
    var ceilingHi = 0.0
    var belowBand = 0
    var thin = 0
    var gappy = 0
    for (outcome in outcomes) {
        when (outcome) {
            is RsaOutcome.Peak -> {
                peaks.add(outcome.brpm)
                peakHz.add(outcome.peakHz)
                peakPwr.add(outcome.power)
            }
            RsaOutcome.Gappy -> gappy++
            RsaOutcome.Thin -> thin++
            RsaOutcome.BelowBand -> belowBand++
            is RsaOutcome.Ceiling -> {
                atCeiling++
                ceilingLo = minOf(ceilingLo, outcome.loBrpm)
                ceilingHi = maxOf(ceilingHi, outcome.hiBrpm)
            }
        }
    }
    val dropped = atCeiling + belowBand + thin + gappy
    val total = peaks.size + dropped
    if (peaks.size < 3) {
        val reasons = listOf(
            belowBand to "$belowBand of $total sub-windows had a beat rate too low to cover " +
                "the HF band (any peak could be an alias)",
            atCeiling to if (atCeiling == 0) {
                ""
            } else {
                val hiPart = if (ceilingHi > ceilingLo) "–${round6(ceilingHi)}" else ""
                "$atCeiling of $total sub-windows peaked at/above their " +
                    "resolvable ceiling (${round6(ceilingLo)}$hiPart " +
                    "br/min)"
            },
            gappy to "$gappy of $total ${round6(segSec)}s sub-windows had less than 80 % " +
                "of their time covered by clean beats (sensor gaps or rejected " +
                "beats)",
            thin to "$thin of $total sub-windows had fewer than 30 beats or no spectral " +
                "peak",
        )
        var top = reasons.first()
        for (r in reasons.drop(1)) {
            if (r.first > top.first) top = r
        }
        val why = if (dropped == 0) "only ${peaks.size} usable sub-windows" else top.second
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no stable HF respiratory peak resolved — $why",
        )
    }
    val medBrpm0 = median(peaks)!!
    var within = 0
    for (p in peaks) if (abs(p - medBrpm0) <= tolBrpm) within++
    val consensus = within.toDouble() / peaks.size
    if (consensus < minConsensus) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "HF peak unstable across the window's own sub-windows — only " +
                "$within of ${peaks.size} ${round6(segSec)}s sub-windows fall " +
                "within ${round6(tolBrpm)} br/min of the median; withheld",
        )
    }
    var best = 0
    for (i in 1 until peaks.size) {
        if (abs(peaks[i] - medBrpm0) < abs(peaks[best] - medBrpm0)) best = i
    }
    val brpm = peaks[best]
    val conf = (
        (1.0 - artifactFraction) * consensus *
            (peaks.size / rsaFullWeightSubwindows).coerceIn(0.0, 1.0)
        ).coerceIn(0.2, 0.9)
    return OsMetric(
        value = RespEstimate(
            brpm,
            peakHz[best],
            peakPwr[best],
            "rsa",
            usableSubwindows = peaks.size,
            subwindows = total,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "RSA HF-peak respiratory rate (Lomb-Scargle on native beat times, " +
            "median of ${peaks.size} ${round6(segSec)}s sub-windows, $within of " +
            "them within ${round6(tolBrpm)} br/min of it — brpm, peak_hz and power " +
            "all come from the medoid sub-window); PRV-derived; this window could " +
            "resolve up to ${round6(hiHz * 60.0)} br/min",
    )
}

/**
 * RIIV respiratory rate from the 1 Hz green PPG ADC. The stream must sample
 * at 1 Hz or faster; a slower cadence aliases every real breath into the band.
 */
fun riivRespRate(
    adc: List<Double>,
    tsSec: List<Double>,
    validFraction: Double = 1.0,
): OsMetric<RespEstimate> {
    val inputs = listOf("ppg_green", "ts")
    val n = adc.size
    if (n < 30 || tsSec.size != n) {
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = "too few green-ADC samples for RIIV (need ≥30 s)",
        )
    }
    val spanSec = tsSec.last() - tsSec.first()
    if (spanSec <= 0.0) {
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = "degenerate timestamps",
        )
    }
    val cadenceSec = sampleCadenceSeconds(tsSec)
    if (cadenceSec == null || 1.0 / (2.0 * cadenceSec) < respHiHz) {
        val note = if (cadenceSec == null) {
            "no measurable sampling cadence — cannot rule out a respiratory " +
                "alias, so RIIV is withheld"
        } else {
            "sampling at ${round6(cadenceSec)}s (Nyquist " +
                "${round6(1.0 / (2.0 * cadenceSec))} Hz) cannot represent the " +
                "$respLoHz–$respHiHz Hz respiratory band — every rate in it " +
                "would alias into the band; withheld"
        }
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = note,
        )
    }
    val fit = olsFit(adc, tsSec)
    val detr = ArrayList<Double>(n)
    for (i in 0 until n) {
        val base = if (fit == null) 0.0 else fit.slope * tsSec[i] + fit.intercept
        detr.add(adc[i] - base)
    }
    val ls = lombScargle(tsSec, detr, freqGrid(respLoHz, respHiHz, 500))
    if (ls == null) {
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = "RIIV spectrum undefined",
        )
    }
    val pk = ls.peakFreq(respLoHz, respHiHz)
    if (pk == null || pk >= respHiHz) {
        return OsMetric.absent(
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = "no respiratory-band peak (or aliased at Nyquist)",
        )
    }
    val brpm = pk * 60.0
    val conf = (0.6 * validFraction).coerceIn(0.15, 0.6)
    return OsMetric(
        value = RespEstimate(brpm, pk, powerAt(ls, pk), "riiv"),
        confidence = conf,
        tier = Tier.RELATIVE,
        inputsUsed = inputs,
        note = "RIIV band-pass (0.1–0.5 Hz) on 1 Hz green ADC; coarse intensity " +
            "proxy (not 419 Hz waveform); 1 Hz Nyquist caps rate at 30 br/min",
    )
}

/** One RSA sub-window's verdict, folded in window order by [rsaRespRate]. */
private sealed interface RsaOutcome {
    data class Peak(val brpm: Double, val peakHz: Double, val power: Double) : RsaOutcome
    data class Ceiling(val loBrpm: Double, val hiBrpm: Double) : RsaOutcome
    data object Gappy : RsaOutcome
    data object Thin : RsaOutcome
    data object BelowBand : RsaOutcome
}

private fun scoreRsaWindow(
    tSec: DoubleArray,
    nnMs: List<Double>,
    segSec: Double,
    lo: Int,
    hi: Int,
): RsaOutcome {
    val k = hi - lo
    var coveredMs = 0.0
    for (i in lo until hi) coveredMs += nnMs[i]
    if (coveredMs / 1000.0 < segSec * 0.8) return RsaOutcome.Gappy
    if (k < 30) return RsaOutcome.Thin
    val segT = ArrayList<Double>(k)
    val segNn = ArrayList<Double>(k)
    for (i in lo until hi) {
        segT.add(tSec[i])
        segNn.add(nnMs[i])
    }
    val segSpan = segT.last() - segT.first()
    val segHi = rsaCeilingHz(median(segNn)!! / 1000.0)
    if (segHi < rsaHiHz) return RsaOutcome.BelowBand
    val gridN = max(64, ceil((segHi - rsaLoHz) * 4.0 * segSpan).toInt())
    val ls = lombScargle(segT, segNn, freqGrid(rsaLoHz, segHi, gridN))
    val pk = ls?.peakFreq(rsaLoHz, segHi)
    if (ls == null || pk == null) return RsaOutcome.Thin
    if (pk >= segHi - (segHi - rsaLoHz) / (gridN - 1)) {
        return RsaOutcome.Ceiling(segHi * 60.0, segHi * 60.0)
    }
    return RsaOutcome.Peak(pk * 60.0, pk, powerAt(ls, pk))
}

/** Fused respiratory rate and the Karlen SD-gate decision. */
data class FusedResp(
    val brpm: Double?,
    val rsaBrpm: Double?,
    val riivBrpm: Double?,
    val agreed: Boolean,
    val decision: String,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "brpm" to if (brpm == null) null else round6(brpm),
        )
        if (rsaBrpm != null) m["rsa_brpm"] = round6(rsaBrpm)
        if (riivBrpm != null) m["riiv_brpm"] = round6(riivBrpm)
        m["agreed"] = agreed
        m["decision"] = decision
        return m
    }
}

/**
 * Karlen 2013 Smart-Fusion. Both present and within [sdGateBrpm]: inverse-variance
 * fuse. Disagree: keep RSA and lower confidence. One side only: pass it through.
 */
fun fuseRespRate(
    rsa: OsMetric<RespEstimate>,
    riiv: OsMetric<RespEstimate>,
    sdGateBrpm: Double = 5.0,
): OsMetric<FusedResp> {
    val inputs = ArrayList<String>(rsa.inputsUsed.size + riiv.inputsUsed.size)
    inputs.addAll(rsa.inputsUsed)
    inputs.addAll(riiv.inputsUsed)
    val rsaV = rsa.value?.brpm
    val riivV = riiv.value?.brpm
    if (rsaV == null && riivV == null) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "neither RSA nor RIIV resolved a respiratory rate",
        )
    }
    if (rsaV != null && riivV == null) {
        return OsMetric(
            value = FusedResp(rsaV, rsaV, null, agreed = false, decision = "rsa_only"),
            confidence = rsa.confidence,
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "RSA-only (RIIV absent)",
        )
    }
    if (rsaV == null && riivV != null) {
        return OsMetric(
            value = FusedResp(riivV, null, riivV, agreed = false, decision = "riiv_only"),
            confidence = riiv.confidence,
            tier = Tier.RELATIVE,
            inputsUsed = inputs,
            note = "RIIV-only (RSA absent) — relative/MED tier",
        )
    }
    val disagree = abs(rsaV!! - riivV!!)
    if (disagree > sdGateBrpm) {
        return OsMetric(
            value = FusedResp(rsaV, rsaV, riivV, agreed = false, decision = "disagree"),
            confidence = rsa.confidence * 0.6,
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "Karlen SD-gate: RSA/RIIV disagree by ${round6(disagree)} br/min " +
                "> $sdGateBrpm; fell back to RSA, lowered confidence",
        )
    }
    val fused = inverseVarianceFuse(
        listOf(
            FusionInput(rsaV, confToVar(rsa.confidence), label = "rsa"),
            FusionInput(riivV, confToVar(riiv.confidence), label = "riiv"),
        ),
    )
    val brpm = fused.value ?: rsaV
    val conf = (max(rsa.confidence, riiv.confidence) + 0.1).coerceIn(0.2, 0.95)
    return OsMetric(
        value = FusedResp(brpm, rsaV, riivV, agreed = true, decision = "fused"),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "Karlen SD-gate passed (Δ ${round6(disagree)} br/min); " +
            "inverse-variance fused RSA+RIIV",
    )
}

private fun confToVar(conf: Double): Double {
    val c = conf.coerceIn(0.05, 1.0)
    return 1.0 / (c * c)
}

/** First index of non-decreasing [a] whose value is >= [v]. */
private fun lowerBound(a: DoubleArray, v: Double): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val m = (lo + hi) ushr 1
        if (a[m] < v) lo = m + 1 else hi = m
    }
    return lo
}

private fun powerAt(ls: LombScargle, fHz: Double): Double {
    var best = 0.0
    var bestDist = Double.POSITIVE_INFINITY
    for (pt in ls.spectrum) {
        val d = abs(pt.freqHz - fHz)
        if (d < bestDist) {
            bestDist = d
            best = pt.psd
        }
    }
    return best
}
