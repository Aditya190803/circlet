// Ported from OpenStrap analytics/lib/src/onehz/wellness/readiness_composite.dart.
// Canonical recovery: personal-baseline robust z-scores, disclosed weights, and drivers.

package app.circlet.analytics.recovery

import app.circlet.analytics.foundations.Driver
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.dispersionBelowQuantum
import app.circlet.analytics.foundations.mad
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.needBaselineNote
import app.circlet.analytics.foundations.robustZ
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundTo
import app.circlet.analytics.foundations.stddev
import app.circlet.analytics.foundations.zScore
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

// Value from OpenStrap wellness/temp_circadian.dart. Exists only so tempInput's gate matches.
const val kMinSettledFraction = 0.80

/** HRV and RHR read one autonomic state and are scored as a single input. */
val readinessAutonomicLabels: Set<String> = setOf("HRV", "RHR")

/** Combined weight of the autonomic input (HRV 0.40 + RHR 0.30). */
const val readinessAutonomicWeight = 0.70

/** Prior composite-z nights before the score mapping is personalised. */
const val readinessCalibrationMinNights = 14

/** Floor on σ̂ so a run of near-identical nights cannot blow a small z up. */
const val readinessCalibrationSigmaFloor = 0.3

/** Composite-z spread the band cut-offs assume: logistic(N(0, 0.65)). */
const val readinessDesignSigma = 0.65

/**
 * Cap on the centre removed from z. A step-down past this stays visible;
 * an uncapped centre would absorb it within about two weeks.
 */
const val readinessCentreCap = 0.5

/** Nights of a personal baseline required before an input can enter the composite. */
const val readinessCompositeMinBaseline = 14

/** Below this count, renormalising would hand the whole score to one input. */
const val readinessCompositeMinInputs = 2

/** Below this surviving weight, the same single-input problem. */
const val readinessCompositeMinWeight = 0.5

data class ReadinessInput(
    val label: String,
    val value: Double?,
    val baseline: List<Double>,
    val goodSign: Int,
    val weight: Double,
    val refusal: String? = null,
    val quantum: Double = 0.0,
)

fun hrvInput(v: Double?, base: List<Double>): ReadinessInput =
    ReadinessInput("HRV", v, base, 1, 0.40)

fun rhrInput(v: Double?, base: List<Double>): ReadinessInput =
    ReadinessInput("RHR", v, base, -1, 0.30, quantum = 1.0)

fun respInput(v: Double?, base: List<Double>): ReadinessInput =
    ReadinessInput("RR", v, base, -1, 0.20)

/**
 * Skin temperature, refused unless [settledFraction] was measured and clears
 * [minSettledFraction]. A null fraction is not "settled". A refused input
 * drops out (weights renormalise) and the reason is named on the composite.
 */
fun tempInput(
    v: Double?,
    base: List<Double>,
    settledFraction: Double? = null,
    minSettledFraction: Double = kMinSettledFraction,
): ReadinessInput {
    if (v == null) return ReadinessInput("temp", null, base, -1, 0.10)
    if (settledFraction == null) {
        return ReadinessInput(
            "temp",
            null,
            base,
            -1,
            0.10,
            refusal = "temp: no settled fraction measured for this night — an " +
                "ungated nightly mean cannot tell skin from a warming strap",
        )
    }
    if (settledFraction < minSettledFraction) {
        return ReadinessInput(
            "temp",
            null,
            base,
            -1,
            0.10,
            refusal = "temp: unsettled_skin_temp:settled=" +
                "${round6(settledFraction)},need=${round6(minSettledFraction)}",
        )
    }
    return ReadinessInput("temp", v, base, -1, 0.10, quantum = 1.0)
}

data class Readiness(
    val score: Double,
    val compositeZ: Double,
    val calibrationSigma: Double? = null,
    val calibrationNights: Int = 0,
    val calibrationCentreRaw: Double? = null,
    val calibrationCentre: Double? = null,
) {
    fun toJson(): Map<String, Any?> {
        val calibration = linkedMapOf<String, Any?>(
            "status" to if (calibrationSigma == null) "calibrating" else "calibrated",
            "nights" to calibrationNights,
        )
        if (calibrationSigma != null) calibration["sigma"] = round6(calibrationSigma)
        if (calibrationCentreRaw != null) calibration["centre_raw"] = round6(calibrationCentreRaw)
        if (calibrationCentre != null) calibration["centre"] = round6(calibrationCentre)
        return linkedMapOf(
            "score" to round6(score),
            "composite_z" to round6(compositeZ),
            "calibration" to calibration,
        )
    }
}

/** One sign-oriented z that [combineReadinessZ] can see. */
data class ReadinessComponentZ(
    val label: String,
    val weight: Double,
    val orientedZ: Double,
)

data class CombinedReadinessZ(
    val z: Double,
    val contributions: Map<String, Double>,
    val weightSum: Double,
)

/**
 * HRV/RHR present in [used] share [autonomicWeight] evenly (mean of their z's
 * times that weight). Every other label keeps its own weight. Weights are
 * renormalised over what is present.
 */
fun combineReadinessZ(
    used: List<ReadinessComponentZ>,
    autonomicWeight: Double = readinessAutonomicWeight,
): CombinedReadinessZ {
    val nAuto = used.count { it.label in readinessAutonomicLabels }
    var weightSum = if (nAuto > 0) autonomicWeight else 0.0
    val raw = LinkedHashMap<String, Double>()
    for (u in used) {
        val auto = u.label in readinessAutonomicLabels
        if (!auto) weightSum += u.weight
        raw[u.label] = (if (auto) autonomicWeight / nAuto else u.weight) * u.orientedZ
    }
    if (weightSum <= 0.0) return CombinedReadinessZ(0.0, emptyMap(), 0.0)
    val contributions = LinkedHashMap<String, Double>(raw.size)
    for ((key, value) in raw) contributions[key] = value / weightSum
    var z = 0.0
    for (value in contributions.values) z += value
    return CombinedReadinessZ(z, contributions, weightSum)
}

data class CalibratedReadinessScore(
    val score: Double,
    val sigma: Double?,
    val nights: Int,
    val centreRaw: Double?,
    val centre: Double?,
)

/**
 * Uncalibrated logistic until [zHistory] holds [readinessCalibrationMinNights].
 * After that, z is re-centred (capped) and rescaled by the user's own MAD.
 */
fun calibratedReadinessScore(z: Double, zHistory: List<Double>): CalibratedReadinessScore {
    val n = zHistory.size
    if (n < readinessCalibrationMinNights) {
        return CalibratedReadinessScore(
            score = 100.0 / (1.0 + exp(-z)),
            sigma = null,
            nights = n,
            centreRaw = null,
            centre = null,
        )
    }
    val sigma = max(readinessCalibrationSigmaFloor, mad(zHistory)!!)
    val centreRaw = median(zHistory)!!
    val centre = centreRaw.coerceIn(-readinessCentreCap, readinessCentreCap)
    return CalibratedReadinessScore(
        score = 100.0 / (1.0 + exp(-readinessDesignSigma * (z - centre) / sigma)),
        sigma = sigma,
        nights = n,
        centreRaw = centreRaw,
        centre = centre,
    )
}

/**
 * Canonical readiness. Missing inputs are dropped, never zero-filled, and a
 * single surviving input is not a composite. [compositeZHistory] is this user's
 * prior composite z's, excluding tonight.
 */
fun readinessComposite(
    inputs: List<ReadinessInput>,
    minBaseline: Int = readinessCompositeMinBaseline,
    minInputs: Int = readinessCompositeMinInputs,
    minWeightSum: Double = readinessCompositeMinWeight,
    compositeZHistory: List<Double> = emptyList(),
): OsMetric<Readiness> {
    val used = ArrayList<String>()
    val parts = ArrayList<ReadinessComponentZ>()
    val details = LinkedHashMap<String, String>()
    val refusals = ArrayList<String>()
    for (inp in inputs) {
        val refusal = inp.refusal
        if (refusal != null) refusals.add(refusal)
    }
    var anyValuePresent = false
    var bestShortHave = -1
    for (inp in inputs) {
        val v = inp.value ?: continue
        anyValuePresent = true
        val base = inp.baseline
        if (base.size < minBaseline) {
            if (base.size > bestShortHave) bestShortHave = base.size
            continue
        }
        val rz = robustZ(v, base)
        // Even a nonzero MAD is quantization noise when the SD sits under one quantum.
        if (inp.quantum > 0.0 && dispersionBelowQuantum(base, inp.quantum)) {
            val sd = stddev(base)
            refusals.add(
                "${inp.label}: baseline_dispersion_below_quantum:" +
                    "sd=${if (sd == null) "null" else round6(sd)}," +
                    "quantum=${round6(inp.quantum)},n=${base.size}",
            )
            continue
        }
        val zr = rz ?: zScore(v, base) ?: continue
        val oriented = inp.goodSign * zr
        used.add(inp.label)
        parts.add(ReadinessComponentZ(inp.label, inp.weight, oriented))
        val method = if (rz != null) {
            "robust-z (median+MAD)"
        } else {
            "z (mean+SD fallback — MAD=0 on a quantized baseline)"
        }
        details[inp.label] = "oriented $method=${round6(oriented)}"
    }
    var autonomicWeight = 0.0
    for (inp in inputs) {
        if (inp.label in readinessAutonomicLabels) autonomicWeight += inp.weight
    }
    val combined = combineReadinessZ(parts, autonomicWeight)
    val weightSum = combined.weightSum
    val suffix = if (refusals.isEmpty()) "" else " Refused: ${refusals.joinToString("; ")}."
    if (used.size < minInputs || weightSum < minWeightSum) {
        if (used.isEmpty() && anyValuePresent && bestShortHave >= 0) {
            return OsMetric.absent(
                tier = Tier.ESTIMATE,
                inputsUsed = emptyList(),
                note = needBaselineNote(bestShortHave, minBaseline) + suffix,
            )
        }
        if (used.isEmpty()) {
            return OsMetric.absent(
                tier = Tier.ESTIMATE,
                inputsUsed = emptyList(),
                note = "no readiness inputs present — \"—\" (never imputed).$suffix",
            )
        }
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = used,
            note = "need_inputs:have=${used.size},need=$minInputs," +
                "weight=${round6(weightSum)},need_weight=${round6(minWeightSum)} — " +
                "renormalising this few would hand the whole score to one input." +
                suffix,
        )
    }
    val composite = combined.z
    val normDrivers = ArrayList<Driver>(combined.contributions.size)
    for ((label, contribution) in combined.contributions) {
        normDrivers.add(Driver(label, roundTo(contribution, 6), detail = details[label]))
    }
    normDrivers.sortWith { a, b -> abs(b.contribution).compareTo(abs(a.contribution)) }
    val cal = calibratedReadinessScore(composite, compositeZHistory)
    val conf = (0.3 + 0.15 * used.size).coerceIn(0.3, 0.9)
    val sigma = cal.sigma
    val calibrationNote = if (sigma == null) {
        "calibrating:have=${cal.nights},need=$readinessCalibrationMinNights"
    } else {
        "calibrated:sigma=${round6(sigma)},n=${cal.nights}," +
            "centre=${round6(cal.centre!!)},centre_raw=${round6(cal.centreRaw!!)}"
    }
    return OsMetric(
        value = Readiness(
            score = cal.score,
            compositeZ = composite,
            calibrationSigma = cal.sigma,
            calibrationNights = cal.nights,
            calibrationCentreRaw = cal.centreRaw,
            calibrationCentre = cal.centre,
        ),
        confidence = conf,
        tier = Tier.ESTIMATE,
        inputsUsed = used,
        drivers = normDrivers,
        note = "GLASS-BOX readiness: disclosed weights autonomic (HRV+RHR, " +
            "mean of their z) 0.70 > RR 0.20 > temp 0.10, renormalized over present " +
            "inputs. Drivers are definitional within the formula (correction, not " +
            "inferred cause). " +
            "$calibrationNote.$suffix",
    )
}
