package app.circlet.ui.trends

import app.circlet.core.MetricKind
import app.circlet.core.PersonalRecord
import app.circlet.core.RecordKind
import app.circlet.core.TrendRange
import app.circlet.core.toIsoWeekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The cells of a month calendar, Monday first. Null entries are blanks: leading blanks
 * line day 1 up under its weekday, trailing blanks complete the last week.
 */
fun monthGrid(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val days: List<LocalDate?> = (1..month.lengthOfMonth()).map { month.atDay(it) }
    val used = leading + days.size
    val trailing = (WEEK_LENGTH - used % WEEK_LENGTH) % WEEK_LENGTH
    return List(leading) { null } + days + List(trailing) { null }
}

/** The mean of the days that have a value. Missing days are skipped, not counted as zero. Null when none do. */
fun List<Double?>.averageOrNull(): Double? {
    val present = filterNotNull()
    return if (present.isEmpty()) null else present.average()
}

data class SeriesStats(val min: Double?, val average: Double?, val max: Double?)

fun seriesStats(values: List<Double?>): SeriesStats {
    val present = values.filterNotNull()
    return SeriesStats(present.minOrNull(), values.averageOrNull(), present.maxOrNull())
}

/** Current period against the previous equal period. Both averages are real, or there is no change. */
data class PeriodChange(val current: Double, val previous: Double) {
    val delta: Double get() = current - previous

    /** Null when the previous average is zero, because a relative change is undefined there. */
    val percent: Double? get() = if (previous == 0.0) null else delta / previous * 100.0
}

fun periodChange(current: List<Double?>, previous: List<Double?>): PeriodChange? {
    val now = current.averageOrNull() ?: return null
    val before = previous.averageOrNull() ?: return null
    return PeriodChange(now, before)
}

/** The inclusive local dates a trend covers. [from] and [to] are both days. */
data class DayWindow(val from: LocalDate, val to: LocalDate)

/** The days a range covers, ending today: 7 for a week, 30 for a month. */
fun TrendRange.length(): Int = when (this) {
    TrendRange.WEEK -> 7
    TrendRange.MONTH -> 30
}

fun currentWindow(range: TrendRange, today: LocalDate): DayWindow =
    DayWindow(today.minusDays(range.length() - 1L), today)

/** The equal period immediately before [currentWindow]. */
fun previousWindow(range: TrendRange, today: LocalDate): DayWindow =
    DayWindow(today.minusDays(2L * range.length() - 1), today.minusDays(range.length().toLong()))

/** The Monday of the week containing [today]. Nothing after it can be shown. */
fun currentWeekStart(today: LocalDate): LocalDate = today.toIsoWeekStart()

fun canShowWeekAfter(weekStart: LocalDate, today: LocalDate): Boolean =
    weekStart.toIsoWeekStart() < currentWeekStart(today)

/** Moves [weekStart] by whole weeks and stops at the current week. */
fun shiftWeek(weekStart: LocalDate, weeks: Long, today: LocalDate): LocalDate =
    minOf(weekStart.toIsoWeekStart().plusWeeks(weeks), currentWeekStart(today))

/** Moves [month] by whole months and stops at the month containing [today]. */
fun shiftMonth(month: YearMonth, months: Long, today: LocalDate): YearMonth =
    minOf(month.plusMonths(months), YearMonth.from(today))

fun canShowMonthAfter(month: YearMonth, today: LocalDate): Boolean = month < YearMonth.from(today)

/** Every date in [window], oldest first. */
fun DayWindow.dates(): List<LocalDate> = generateSequence(from) { it.plusDays(1) }
    .takeWhile { !it.isAfter(to) }
    .toList()

/** One day on a trend line. [value] is null when the day has no reading. */
data class DayPoint(val date: LocalDate, val value: Double?)

/** A metric over the current window, with its min, average and max and the change against the previous period. */
data class MetricTrend(
    val kind: MetricKind,
    val range: TrendRange,
    val window: DayWindow,
    val points: List<DayPoint>,
    val stats: SeriesStats,
    val change: PeriodChange?,
)

/**
 * Builds a [MetricTrend] from [valueOn], which returns the real reading for a date or null when
 * the day has none. The previous period is read the same way, so gaps stay gaps on both sides.
 */
fun metricTrend(
    kind: MetricKind,
    range: TrendRange,
    today: LocalDate,
    valueOn: (LocalDate) -> Double?,
): MetricTrend {
    val current = currentWindow(range, today)
    val previous = previousWindow(range, today)
    val points = current.dates().map { DayPoint(it, valueOn(it)) }
    val change = periodChange(
        current = points.map { it.value },
        previous = previous.dates().map(valueOn),
    )
    return MetricTrend(kind, range, current, points, seriesStats(points.map { it.value }), change)
}

/** Records under their kind, in [RecordKind] order. Kinds with no record are left out. */
data class RecordGroup(val kind: RecordKind, val records: List<PersonalRecord>)

fun groupRecords(records: List<PersonalRecord>): List<RecordGroup> {
    val byKind = records.groupBy { it.kind }
    return RecordKind.values().mapNotNull { kind -> byKind[kind]?.let { RecordGroup(kind, it) } }
}

private const val WEEK_LENGTH = 7
