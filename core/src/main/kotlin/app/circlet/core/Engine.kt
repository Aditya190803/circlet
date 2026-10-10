package app.circlet.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything required to compute one local day.
 *
 * [samples] run from 18:00 local on the day before [date] through the last
 * instant of [date], in [zone]. [history] holds at most 60 days before [date],
 * oldest first, and does not include [date]. [manualActivities] are workouts
 * the user logged or edited; the result must keep their identity.
 * [now] is the instant the caller considers current. The engine does not read a clock.
 */
data class DayInput(
    val date: LocalDate,
    val zone: ZoneId,
    val samples: List<Sample>,
    val profile: Profile,
    val history: List<DaySummary>,
    val manualActivities: List<Activity>,
    val now: Instant,
)

/**
 * Computes one [DaySummary] from a [DayInput].
 *
 * [algorithmVersion] increments when the maths changes. Storage recomputes a
 * stored day whose version does not match, lazily, the next time that day is
 * read or new samples land on it.
 */
interface DayEngine {
    val algorithmVersion: Int

    fun computeDay(input: DayInput): DaySummary
}

/**
 * Trends, records, the weekly recap, journal correlations, and the sleep-coach bedtime.
 *
 * Every function is pure: it reads only its arguments and returns the core types.
 * [days] are in ascending date order.
 */
interface InsightEngine {
    fun trend(days: List<DaySummary>, metric: MetricKind): List<TrendPoint>

    fun records(days: List<DaySummary>): List<PersonalRecord>

    /** [days] are the seven local days that start on [weekStart], oldest first. */
    fun weeklyRecap(weekStart: LocalDate, days: List<DaySummary>): WeeklyRecap

    /**
     * One row per tag and [CorrelationMetric].
     * [tags] is the catalogue. [entries] are the days the user filled in.
     */
    fun correlations(
        days: List<DaySummary>,
        tags: List<JournalTag>,
        entries: List<JournalEntry>,
    ): List<TagCorrelation>

    /**
     * When to be in bed to meet tonight's sleep need, or null when the need
     * cannot be known from [history]. [history] is oldest first and may include today.
     */
    fun recommendedBedtime(history: List<DaySummary>, zone: ZoneId, now: Instant): Instant?
}
