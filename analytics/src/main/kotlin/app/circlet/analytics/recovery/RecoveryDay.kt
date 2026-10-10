// Day-level recovery entry. Composes OpenStrap wellness/readiness_composite.dart
// (canonical score) with the ln(RMSSD) baseline from clinical/readiness_lnrmssd.dart.
// Sleep performance takes the 0.10 weight OpenStrap gives skin temperature.
// This product does not surface temperature. Time is not read here.

package app.circlet.analytics.recovery

import app.circlet.analytics.foundations.mad
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.sleep.coreValue
import app.circlet.analytics.sleep.reasonFromNote
import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.DayHeart
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.Recovery
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.SleepPerformance
import kotlin.math.ln
import kotlin.math.max

/**
 * One recovery score for the day that produced [heart] and [sleepPerformance].
 *
 * [history] is older than that day, oldest first. A driver lists score points,
 * positive when the input raised readiness. Calibration is ready once fourteen
 * prior scores can be inverted back into the composite z this formula stores.
 * The inversion undoes the published logistic; rounding the stored score to an
 * integer loses a little of the original z.
 */
fun computeRecovery(
    heart: DayHeart,
    sleepPerformance: SleepPerformance,
    history: List<DaySummary>,
): Recovery {
    val hrvBase = ArrayList<Double>()
    val rhrBase = ArrayList<Double>()
    val respBase = ArrayList<Double>()
    val sleepBase = ArrayList<Double>()
    for (day in history) {
        day.heart.hrv.rmssdMs.value?.let { if (it > 0.0) hrvBase.add(ln(it)) }
        day.heart.resting.bpm.value?.let { rhrBase.add(it.toDouble()) }
        day.heart.respiratoryRate.breathsPerMinute.value?.let { respBase.add(it) }
        day.sleepPerformance.percent.value?.let { sleepBase.add(it.toDouble()) }
    }
    val hrv = heart.hrv.rmssdMs.value?.let { if (it > 0.0) ln(it) else null }
    val rhr = heart.resting.bpm.value?.toDouble()
    val resp = heart.respiratoryRate.breathsPerMinute.value
    val sleep = sleepPerformance.percent.value?.toDouble()
    val zHistory = reconstructCompositeZ(history)
    val metric = readinessComposite(
        inputs = listOf(
            hrvInput(hrv, hrvBase),
            rhrInput(rhr, rhrBase),
            respInput(resp, respBase),
            ReadinessInput("sleep", sleep, sleepBase, goodSign = 1, weight = 0.10),
        ),
        compositeZHistory = zHistory,
    )
    val calibration = if (zHistory.size >= readinessCalibrationMinNights) {
        CalibrationStatus.READY
    } else {
        CalibrationStatus.CALIBRATING
    }
    val score = metric.value?.let { value ->
        val rounded = roundHalfAwayFromZero(value.score).toInt().coerceIn(0, 100)
        coreValue(rounded, metric.confidence)
    } ?: Metric.absent(reasonFromNote(metric.note))
    val points = scorePoints(metric.value?.compositeZ, zHistory, metric.drivers)
    val drivers = listOf(
        driver(
            RecoveryDriverKind.HRV,
            points["HRV"],
            hrv,
            hrvBase.size,
            heart.hrv.rmssdMs.absentReason,
            metric.note,
            "HRV",
        ),
        driver(
            RecoveryDriverKind.RESTING_HEART_RATE,
            points["RHR"],
            rhr,
            rhrBase.size,
            heart.resting.bpm.absentReason,
            metric.note,
            "RHR",
        ),
        driver(
            RecoveryDriverKind.SLEEP,
            points["sleep"],
            sleep,
            sleepBase.size,
            sleepPerformance.percent.absentReason ?: AbsentReason.NO_SLEEP,
            metric.note,
            "sleep",
        ),
        driver(
            RecoveryDriverKind.RESPIRATORY_RATE,
            points["RR"],
            resp,
            respBase.size,
            heart.respiratoryRate.breathsPerMinute.absentReason,
            metric.note,
            "RR",
        ),
    )
    return Recovery(score, calibration, drivers)
}

/**
 * Prior composite z, oldest first.
 * Fewer than fourteen nights used the uncalibrated logistic, so z is logit(score/100).
 * After that, invert `score/100 = 1 / (1 + exp(-0.65·(z − centre) / sigma))`
 * with centre and sigma taken from the z's already reconstructed, the same pair
 * [calibratedReadinessScore] would have used that night.
 */
private fun reconstructCompositeZ(history: List<DaySummary>): List<Double> {
    val z = ArrayList<Double>()
    for (day in history) {
        val score = day.recovery.score.value ?: continue
        val p = score.coerceIn(1, 99) / 100.0
        val logit = ln(p / (1.0 - p))
        if (z.size < readinessCalibrationMinNights) {
            z.add(logit)
        } else {
            val sigma = max(readinessCalibrationSigmaFloor, mad(z)!!)
            val centre = median(z)!!.coerceIn(-readinessCentreCap, readinessCentreCap)
            z.add(centre + logit * sigma / readinessDesignSigma)
        }
    }
    return z
}

private fun scorePoints(
    compositeZ: Double?,
    zHistory: List<Double>,
    drivers: List<app.circlet.analytics.foundations.Driver>?,
): Map<String, Double> {
    if (compositeZ == null || drivers.isNullOrEmpty()) return emptyMap()
    val full = calibratedReadinessScore(compositeZ, zHistory).score
    val points = LinkedHashMap<String, Double>()
    for (driver in drivers) {
        val without = calibratedReadinessScore(compositeZ - driver.contribution, zHistory).score
        points[driver.label] = full - without
    }
    return points
}

private fun driver(
    kind: RecoveryDriverKind,
    contribution: Double?,
    value: Double?,
    baseline: Int,
    source: AbsentReason?,
    note: String?,
    label: String,
): RecoveryDriver {
    if (contribution != null) return RecoveryDriver(kind, contribution, null)
    val reason = when {
        value == null -> source ?: AbsentReason.NOT_ENOUGH_DATA
        baseline < readinessCompositeMinBaseline -> AbsentReason.CALIBRATING
        note?.contains("$label: baseline_dispersion_below_quantum") == true -> AbsentReason.SIGNAL_TOO_NOISY
        else -> AbsentReason.NOT_ENOUGH_DATA
    }
    return RecoveryDriver(kind, null, reason)
}
