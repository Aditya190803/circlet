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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * One recovery score for the day that produced [heart] and [sleepPerformance].
 *
 * [history] is older than that day, oldest first. A driver lists score points,
 * positive when the input raised readiness. The score is personal, and calibration
 * ready, once its inputs have fourteen nights of baseline; until fourteen prior nights
 * have a composite of their own, the spread is the design one rather than the user's.
 */
fun computeRecovery(
    heart: DayHeart,
    sleepPerformance: SleepPerformance,
    history: List<DaySummary>,
): Recovery {
    val nights = history.map(::nightInputs)
    val tonight = NightInputs(
        hrv = heart.hrv.rmssdMs.value?.let { if (it > 0.0) ln(it) else null },
        rhr = heart.resting.bpm.value?.toDouble(),
        resp = heart.respiratoryRate.breathsPerMinute.value,
        sleep = sleepPerformance.percent.value?.toDouble(),
    )
    val baseline = Baselines.before(nights, nights.size)
    val zHistory = priorCompositeZ(nights)
    val metric = readinessComposite(baseline.inputsFor(tonight))
    val compositeZ = metric.value?.compositeZ
    val calibration = if (compositeZ != null) CalibrationStatus.READY else CalibrationStatus.CALIBRATING
    val score = compositeZ?.let { z ->
        val rounded = roundHalfAwayFromZero(personalRecoveryScore(z, zHistory)).toInt().coerceIn(0, 100)
        coreValue(rounded, metric.confidence)
    } ?: Metric.absent(reasonFromNote(metric.note))
    val points = scorePoints(compositeZ, zHistory, metric.drivers)
    val drivers = listOf(
        driver(
            RecoveryDriverKind.HRV,
            points["HRV"],
            tonight.hrv,
            baseline.hrv.size,
            heart.hrv.rmssdMs.absentReason,
            metric.note,
            "HRV",
        ),
        driver(
            RecoveryDriverKind.RESTING_HEART_RATE,
            points["RHR"],
            tonight.rhr,
            baseline.rhr.size,
            heart.resting.bpm.absentReason,
            metric.note,
            "RHR",
        ),
        driver(
            RecoveryDriverKind.SLEEP,
            points["sleep"],
            tonight.sleep,
            baseline.sleep.size,
            sleepPerformance.percent.absentReason ?: AbsentReason.NO_SLEEP,
            metric.note,
            "sleep",
        ),
        driver(
            RecoveryDriverKind.RESPIRATORY_RATE,
            points["RR"],
            tonight.resp,
            baseline.resp.size,
            heart.respiratoryRate.breathsPerMinute.absentReason,
            metric.note,
            "RR",
        ),
    )
    return Recovery(score, calibration, drivers)
}

/** Nights of each input a baseline keeps, newest first. Bounds the work when old history is loaded. */
internal const val recoveryBaselineNights = 60

/** Score of a night that sits exactly on this user's typical composite. */
internal const val recoveryTypicalScore = 58.0

/** Personal spreads above typical at which a night reaches the green band (67). */
internal const val recoveryGreenSpread = 0.45

private const val GREEN_FROM = 67.0

private fun logit(p: Double): Double = ln(p / (1.0 - p))

private val typicalLogit = logit(recoveryTypicalScore / 100.0)
private val spreadSlope = (logit(GREEN_FROM / 100.0) - typicalLogit) / recoveryGreenSpread

/**
 * Score for composite [z] against this user's prior composites [zHistory].
 * The night is placed in the user's own spread: u = (z − centre) / σ, with the centre
 * capped so a lasting step-down stays visible. A typical night scores [recoveryTypicalScore],
 * about a third of nights land green and about one in eight red.
 * Until fourteen prior composites exist, centre is 0 and σ is the design spread.
 */
internal fun personalRecoveryScore(z: Double, zHistory: List<Double>): Double {
    val calibrated = zHistory.size >= readinessCalibrationMinNights
    val sigma = if (calibrated) max(readinessCalibrationSigmaFloor, mad(zHistory)!!) else readinessDesignSigma
    val centre = if (calibrated) median(zHistory)!!.coerceIn(-readinessCentreCap, readinessCentreCap) else 0.0
    val u = (z - centre) / sigma
    return 100.0 / (1.0 + exp(-(typicalLogit + spreadSlope * u)))
}

private data class NightInputs(val hrv: Double?, val rhr: Double?, val resp: Double?, val sleep: Double?)

private fun nightInputs(day: DaySummary) = NightInputs(
    hrv = day.heart.hrv.rmssdMs.value?.let { if (it > 0.0) ln(it) else null },
    rhr = day.heart.resting.bpm.value?.toDouble(),
    resp = day.heart.respiratoryRate.breathsPerMinute.value,
    sleep = day.sleepPerformance.percent.value?.toDouble(),
)

/** The last [recoveryBaselineNights] values of each input before one night. */
private class Baselines(val hrv: List<Double>, val rhr: List<Double>, val resp: List<Double>, val sleep: List<Double>) {
    fun inputsFor(night: NightInputs): List<ReadinessInput> = listOf(
        hrvInput(night.hrv, hrv),
        rhrInput(night.rhr, rhr),
        respInput(night.resp, resp),
        ReadinessInput("sleep", night.sleep, sleep, goodSign = 1, weight = 0.10),
    )

    companion object {
        fun before(nights: List<NightInputs>, end: Int): Baselines {
            fun take(pick: (NightInputs) -> Double?): List<Double> {
                val out = ArrayList<Double>()
                var i = end - 1
                while (i >= 0 && out.size < recoveryBaselineNights) {
                    pick(nights[i])?.let(out::add)
                    i--
                }
                out.reverse()
                return out
            }
            return Baselines(take { it.hrv }, take { it.rhr }, take { it.resp }, take { it.sleep })
        }
    }
}

/**
 * Composite z of every prior night, oldest first, each against the baseline that preceded it.
 * Computed from the stored inputs rather than read back from stored scores: an early night's
 * stored score may be the population-norm provisional one, and inverting it would set the
 * user's typical level from strangers' numbers.
 */
private fun priorCompositeZ(nights: List<NightInputs>): List<Double> {
    val z = ArrayList<Double>()
    for (i in nights.indices) {
        readinessComposite(Baselines.before(nights, i).inputsFor(nights[i])).value?.let { z.add(it.compositeZ) }
    }
    return z.takeLast(recoveryBaselineNights)
}

private fun scorePoints(
    compositeZ: Double?,
    zHistory: List<Double>,
    drivers: List<app.circlet.analytics.foundations.Driver>?,
): Map<String, Double> {
    if (compositeZ == null || drivers.isNullOrEmpty()) return emptyMap()
    val full = personalRecoveryScore(compositeZ, zHistory)
    val points = LinkedHashMap<String, Double>()
    for (driver in drivers) {
        points[driver.label] = full - personalRecoveryScore(compositeZ - driver.contribution, zHistory)
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
