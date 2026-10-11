package app.circlet.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/**
 * A metric the week and month charts can plot.
 * The unit of [TrendPoint.value] is fixed per kind:
 * sleep performance and efficiency and recovery are percent;
 * sleep duration, need, and debt are minutes;
 * consistency is the 0–100 regularity index;
 * strain is 0–21;
 * stress is 0–100;
 * resting heart rate is beats per minute;
 * HRV is RMSSD in milliseconds;
 * respiratory rate is breaths per minute;
 * steps are a count;
 * calories are kilocalories;
 * VO2max is ml/kg/min;
 * skin temperature is a deviation, in standard deviations of the user's own nights.
 */
enum class MetricKind {
    SLEEP_PERFORMANCE,
    SLEEP_DURATION,
    SLEEP_EFFICIENCY,
    SLEEP_NEED,
    SLEEP_DEBT,
    SLEEP_CONSISTENCY,
    RECOVERY,
    STRAIN,
    STRESS,
    RESTING_HEART_RATE,
    HRV,
    RESPIRATORY_RATE,
    STEPS,
    CALORIES,
    VO2_MAX,
    SKIN_TEMPERATURE,
}

/** How far back a trend looks from today. A week is 7 local days and a month is 30. */
enum class TrendRange {
    WEEK,
    MONTH,
}

/**
 * One day on a trend.
 * [value] uses the unit documented on [MetricKind]. An absent source metric stays absent here.
 */
data class TrendPoint(
    val date: LocalDate,
    val value: Metric<Double>,
)

/** A best (or, for resting heart rate, lowest) value the history contains. */
enum class RecordKind {
    /** 0–21. */
    HIGHEST_STRAIN,

    /** Minutes asleep. */
    LONGEST_SLEEP,

    /** Recovery score, 0–100. */
    HIGHEST_RECOVERY,

    /** RMSSD, milliseconds. */
    HIGHEST_HRV,

    /** Beats per minute. */
    LOWEST_RESTING_HEART_RATE,

    /** Step count. */
    MOST_STEPS,

    /** Beats per minute. */
    HIGHEST_HEART_RATE,

    /** Minutes. */
    LONGEST_ACTIVITY,
}

/**
 * One personal record.
 * [value] uses the unit on [kind]. [activityId] is set when the record came from a workout.
 */
data class PersonalRecord(
    val kind: RecordKind,
    val value: Double,
    val date: LocalDate,
    val activityId: String?,
)

/**
 * The seven local days that start on [weekStart], plus the averages the recap shows.
 * Averages skip absent days. Sleep debt is the summed shortfall across the week.
 * Recovery and sleep performance averages are in percent. Strain is 0–21. HRV is milliseconds.
 */
data class WeeklyRecap(
    val weekStart: LocalDate,
    val days: List<DaySummary>,
    val averageRecovery: Metric<Double>,
    val averageStrain: Metric<Double>,
    val averageSleepPerformance: Metric<Double>,
    val averageHrvMs: Metric<Double>,
    val sleepDebt: Metric<Duration>,
)

/** The Monday of the ISO week that contains this date. */
fun LocalDate.toIsoWeekStart(): LocalDate = minusDays((dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
