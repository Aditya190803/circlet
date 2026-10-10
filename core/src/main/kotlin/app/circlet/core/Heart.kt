package app.circlet.core

import java.time.Duration
import java.time.Instant

/**
 * Resting heart rate for a day, in beats per minute.
 * Taken from the slowest part of the main sleep when that sleep exists.
 */
data class RestingHeartRate(
    val bpm: Metric<Int>,
)

/**
 * Frequency-domain heart-rate variability for the detail screen.
 * Powers are milliseconds squared. [lfHfRatio] is LF / HF, dimensionless.
 */
data class FrequencyDomain(
    val lfMs2: Double,
    val hfMs2: Double,
    val lfHfRatio: Double,
)

/**
 * Heart-rate variability.
 * [rmssdMs] and [sdnnMs] are milliseconds.
 * [frequency] is null when the spectrum was not computed.
 */
data class Hrv(
    val rmssdMs: Metric<Double>,
    val sdnnMs: Metric<Double>,
    val frequency: FrequencyDomain?,
)

/**
 * Respiratory rate derived from beat intervals, in breaths per minute.
 */
data class RespiratoryRate(
    val breathsPerMinute: Metric<Double>,
)

/**
 * A short HRV reading taken while awake.
 * [duration] is how long the check ran.
 */
data class HrvSpotCheck(
    val id: String,
    val at: Instant,
    val duration: Duration,
    val hrv: Hrv,
)

/**
 * The heart numbers for one local day.
 * [averageBpm] and [maxBpm] cover sampled minutes of that calendar day, in beats per minute.
 */
data class DayHeart(
    val resting: RestingHeartRate,
    val hrv: Hrv,
    val respiratoryRate: RespiratoryRate,
    val averageBpm: Metric<Int>,
    val maxBpm: Metric<Int>,
)
