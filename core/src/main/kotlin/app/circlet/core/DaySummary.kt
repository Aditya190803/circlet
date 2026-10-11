package app.circlet.core

import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Everything the day screen needs for one local date.
 *
 * Sleep, recovery, strain, stress, heart, steps, and VO2max carry their own
 * absence. [naps] and [activities] are empty when there are none.
 * [heartRateByMinute] has 1440 entries on a computed day (index 0 is 00:00,
 * null is a minute with no sample) and is empty when the day was not computed.
 * [calories] are total kilocalories for the local day.
 * Calibration lives on [Recovery.calibration].
 * [skinTemperature] is null when that night was not scored. [references] is empty
 * until a day has been compared with a baseline and with published ranges.
 */
data class DaySummary(
    val date: LocalDate,
    val sleep: Metric<SleepSession>,
    val naps: List<SleepSession>,
    val recovery: Recovery,
    val strain: Strain,
    val stress: DayStress,
    val heart: DayHeart,
    val steps: Steps,
    val calories: Metric<Int>,
    val activities: List<Activity>,
    val heartRateByMinute: List<Int?>,
    val sleepNeed: SleepNeed,
    val sleepDebt: SleepDebt,
    val sleepPerformance: SleepPerformance,
    val sleepConsistency: SleepConsistency,
    val targetStrain: TargetStrain,
    val vo2Max: Vo2Max,
    val wellness: List<WellnessFlag>,
    val skinTemperature: SkinTemperature? = null,
    val references: List<MetricReference> = emptyList(),
)

/**
 * One public [MetricKind] to [DaySummary] field mapping, in the units the kind
 * documents. The fake backend, the insight engine, and the UI share this; an
 * absent source metric stays absent here.
 */
fun DaySummary.reading(kind: MetricKind): Metric<Double> = when (kind) {
    MetricKind.SLEEP_PERFORMANCE -> sleepPerformance.percent.asDouble()
    MetricKind.SLEEP_DURATION -> sleep.value?.timeAsleep?.let { Metric.of(it.toMinutes().toDouble(), sleep.confidence) }
        ?: Metric.absent(sleep.absentReason ?: AbsentReason.NO_SLEEP, sleep.confidence)
    MetricKind.SLEEP_EFFICIENCY -> sleep.value?.efficiency?.asDouble()
        ?: Metric.absent(sleep.absentReason ?: AbsentReason.NO_SLEEP, sleep.confidence)
    MetricKind.SLEEP_NEED -> sleepNeed.duration.minutes()
    MetricKind.SLEEP_DEBT -> sleepDebt.shortfall.minutes()
    MetricKind.SLEEP_CONSISTENCY -> sleepConsistency.index
    MetricKind.RECOVERY -> recovery.score.asDouble()
    MetricKind.STRAIN -> strain.score
    MetricKind.STRESS -> stress.score
    MetricKind.RESTING_HEART_RATE -> heart.resting.bpm.asDouble()
    MetricKind.HRV -> heart.hrv.rmssdMs
    MetricKind.RESPIRATORY_RATE -> heart.respiratoryRate.breathsPerMinute
    MetricKind.STEPS -> steps.count.asDouble()
    MetricKind.CALORIES -> calories.asDouble()
    MetricKind.VO2_MAX -> vo2Max.mlPerKgMin
    MetricKind.SKIN_TEMPERATURE -> skinTemperature?.deviation
        ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
}

private fun Metric<Int>.asDouble(): Metric<Double> =
    if (value != null) Metric.of(value.toDouble(), confidence) else Metric.absent(checkNotNull(absentReason), confidence)

private fun Metric<Duration>.minutes(): Metric<Double> =
    if (value != null) Metric.of(value.toMinutes().toDouble(), confidence) else Metric.absent(checkNotNull(absentReason), confidence)

/**
 * A day with every metric absent for the same [reason].
 * Lists are empty. Zone minutes are zero. [calibration] is reported as given
 * so a calibrated user can still have an empty day.
 */
fun emptyDay(
    date: LocalDate,
    reason: AbsentReason,
    calibration: CalibrationStatus = CalibrationStatus.CALIBRATING,
): DaySummary {
    val absentInt = Metric.absent<Int>(reason)
    val absentDouble = Metric.absent<Double>(reason)
    val absentDuration = Metric.absent<java.time.Duration>(reason)
    return DaySummary(
        date = date,
        sleep = Metric.absent(reason),
        naps = emptyList(),
        recovery = Recovery(
            score = absentInt,
            calibration = calibration,
            drivers = RecoveryDriverKind.entries.map { kind ->
                RecoveryDriver(kind, contribution = null, absentReason = reason)
            },
        ),
        strain = Strain(score = absentDouble, zoneMinutes = ZoneMinutes.ZERO),
        stress = DayStress(score = absentDouble, timeline = emptyList()),
        heart = DayHeart(
            resting = RestingHeartRate(absentInt),
            hrv = Hrv(rmssdMs = absentDouble, sdnnMs = absentDouble, frequency = null),
            respiratoryRate = RespiratoryRate(absentDouble),
            averageBpm = absentInt,
            maxBpm = absentInt,
        ),
        steps = Steps(absentInt),
        calories = absentInt,
        activities = emptyList(),
        heartRateByMinute = emptyList(),
        sleepNeed = SleepNeed(duration = absentDuration, recommendedBedtime = null),
        sleepDebt = SleepDebt(shortfall = absentDuration),
        sleepPerformance = SleepPerformance(absentInt),
        sleepConsistency = SleepConsistency(absentDouble),
        targetStrain = TargetStrain(absentDouble),
        vo2Max = Vo2Max(absentDouble),
        wellness = emptyList(),
    )
}

/**
 * Day, range, trend, records, weekly recap, and HRV spot checks.
 * Observation is a [Flow]. One-shot reads return [Outcome].
 * [observeRange] is inclusive of both dates and emits dates in ascending order.
 * An inverted range emits an empty list.
 */
interface MetricsRepository {
    fun observeDay(date: LocalDate): Flow<DaySummary>

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DaySummary>>

    suspend fun trend(metric: MetricKind, range: TrendRange): Outcome<List<TrendPoint>>

    suspend fun records(): Outcome<List<PersonalRecord>>

    /** [weekStart] is the Monday that opens the week. The result covers seven days from that date. */
    suspend fun weeklyRecap(weekStart: LocalDate): Outcome<WeeklyRecap>

    fun observeSpotChecks(date: LocalDate): Flow<List<HrvSpotCheck>>
}
