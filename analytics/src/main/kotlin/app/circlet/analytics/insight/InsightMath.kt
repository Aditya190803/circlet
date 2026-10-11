package app.circlet.analytics.insight

// Trends, records, and the weekly recap over stored days.
// OpenStrap has no single function for these screens. The numbers are the
// day metrics themselves: sleep performance and debt from human/coaching.dart
// and human/sleep_regularity.dart, strain from clinical/load_trimp.dart.
// Averages skip absent days. Nothing here fills a gap with a typical value.

import app.circlet.core.AbsentReason
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.PersonalRecord
import app.circlet.core.RecordKind
import app.circlet.core.TrendPoint
import app.circlet.core.TrendRange
import app.circlet.core.WeeklyRecap
import java.time.Duration
import java.time.LocalDate

fun trend(
    days: List<DaySummary>,
    metric: MetricKind,
    range: TrendRange,
    today: LocalDate,
): List<TrendPoint> {
    val from = when (range) {
        TrendRange.WEEK -> today.minusDays(6)
        TrendRange.MONTH -> today.minusDays(29)
    }
    return days
        .filter { it.date in from..today }
        .sortedBy { it.date }
        .map { TrendPoint(it.date, metricValue(it, metric)) }
}

fun records(days: List<DaySummary>): List<PersonalRecord> {
    val out = ArrayList<PersonalRecord>()
    best(days, { it.strain.score }) { day, value ->
        PersonalRecord(RecordKind.HIGHEST_STRAIN, value, day.date, null)
    }?.let { out.add(it) }
    best(days, { day ->
        val session = day.sleep.value ?: return@best null
        Metric.of(session.timeAsleep.toMinutes().toDouble(), session.efficiency.confidence)
    }) { day, value ->
        PersonalRecord(RecordKind.LONGEST_SLEEP, value, day.date, null)
    }?.let { out.add(it) }
    best(days, { it.recovery.score.map { v -> v.toDouble() } }) { day, value ->
        PersonalRecord(RecordKind.HIGHEST_RECOVERY, value, day.date, null)
    }?.let { out.add(it) }
    best(days, { it.heart.hrv.rmssdMs }) { day, value ->
        PersonalRecord(RecordKind.HIGHEST_HRV, value, day.date, null)
    }?.let { out.add(it) }
    lowest(days, { it.heart.resting.bpm.map { v -> v.toDouble() } }) { day, value ->
        PersonalRecord(RecordKind.LOWEST_RESTING_HEART_RATE, value, day.date, null)
    }?.let { out.add(it) }
    best(days, { it.steps.count.map { v -> v.toDouble() } }) { day, value ->
        PersonalRecord(RecordKind.MOST_STEPS, value, day.date, null)
    }?.let { out.add(it) }
    var bestHr: PersonalRecord? = null
    var longest: PersonalRecord? = null
    for (day in days) {
        for (activity in day.activities) {
            val hr = activity.maxHeartRateBpm.value
            if (hr != null && (bestHr == null || hr > bestHr.value)) {
                bestHr = PersonalRecord(RecordKind.HIGHEST_HEART_RATE, hr.toDouble(), day.date, activity.id)
            }
            val minutes = Duration.between(activity.start, activity.end).toMinutes().toDouble()
            if (minutes > 0 && (longest == null || minutes > longest.value)) {
                longest = PersonalRecord(RecordKind.LONGEST_ACTIVITY, minutes, day.date, activity.id)
            }
        }
        val dayMax = day.heart.maxBpm.value
        if (dayMax != null && (bestHr == null || dayMax > bestHr.value)) {
            bestHr = PersonalRecord(RecordKind.HIGHEST_HEART_RATE, dayMax.toDouble(), day.date, null)
        }
    }
    bestHr?.let { out.add(it) }
    longest?.let { out.add(it) }
    return out
}

fun weeklyRecap(days: List<DaySummary>, weekStart: LocalDate): WeeklyRecap {
    val week = (0L..6L).map { offset ->
        days.find { it.date == weekStart.plusDays(offset) }
    }
    val present = week.filterNotNull()
    return WeeklyRecap(
        weekStart = weekStart,
        days = present,
        averageRecovery = average(present.map { it.recovery.score.map { v -> v.toDouble() } }),
        averageStrain = average(present.map { it.strain.score }),
        averageSleepPerformance = average(present.map { it.sleepPerformance.percent.map { v -> v.toDouble() } }),
        averageHrvMs = average(present.map { it.heart.hrv.rmssdMs }),
        sleepDebt = sumDebt(present),
    )
}

private fun sumDebt(days: List<DaySummary>): Metric<Duration> {
    val present = days.mapNotNull { it.sleepDebt.shortfall.value }
    if (present.isEmpty()) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return Metric.of(present.reduce(Duration::plus), app.circlet.analytics.support.confidenceTier(0.6))
}

private fun average(values: List<Metric<Double>>): Metric<Double> {
    val present = values.mapNotNull { it.value }
    if (present.isEmpty()) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return Metric.of(present.average(), app.circlet.analytics.support.confidenceTier(0.6))
}

private fun best(
    days: List<DaySummary>,
    value: (DaySummary) -> Metric<Double>?,
    build: (DaySummary, Double) -> PersonalRecord,
): PersonalRecord? {
    var record: PersonalRecord? = null
    for (day in days) {
        val v = value(day)?.value ?: continue
        if (record == null || v > record.value) record = build(day, v)
    }
    return record
}

private fun lowest(
    days: List<DaySummary>,
    value: (DaySummary) -> Metric<Double>?,
    build: (DaySummary, Double) -> PersonalRecord,
): PersonalRecord? {
    var record: PersonalRecord? = null
    for (day in days) {
        val v = value(day)?.value ?: continue
        if (record == null || v < record.value) record = build(day, v)
    }
    return record
}

internal fun metricValue(day: DaySummary, kind: MetricKind): Metric<Double> = when (kind) {
    MetricKind.SLEEP_PERFORMANCE -> day.sleepPerformance.percent.map { it.toDouble() }
    MetricKind.SLEEP_DURATION -> {
        val session = day.sleep.value
        if (session == null) Metric.absent(day.sleep.absentReason ?: AbsentReason.NO_SLEEP)
        else Metric.of(session.timeAsleep.toMinutes().toDouble(), session.efficiency.confidence)
    }
    MetricKind.SLEEP_EFFICIENCY -> {
        val session = day.sleep.value
        if (session == null) Metric.absent(day.sleep.absentReason ?: AbsentReason.NO_SLEEP)
        else session.efficiency.map { it.toDouble() }
    }
    MetricKind.SLEEP_NEED -> day.sleepNeed.duration.map { it.toMinutes().toDouble() }
    MetricKind.SLEEP_DEBT -> day.sleepDebt.shortfall.map { it.toMinutes().toDouble() }
    MetricKind.SLEEP_CONSISTENCY -> day.sleepConsistency.index
    MetricKind.RECOVERY -> day.recovery.score.map { it.toDouble() }
    MetricKind.STRAIN -> day.strain.score
    MetricKind.STRESS -> day.stress.score
    MetricKind.RESTING_HEART_RATE -> day.heart.resting.bpm.map { it.toDouble() }
    MetricKind.HRV -> day.heart.hrv.rmssdMs
    MetricKind.RESPIRATORY_RATE -> day.heart.respiratoryRate.breathsPerMinute
    MetricKind.STEPS -> day.steps.count.map { it.toDouble() }
    MetricKind.CALORIES -> day.calories.map { it.toDouble() }
    MetricKind.VO2_MAX -> day.vo2Max.mlPerKgMin
    MetricKind.SKIN_TEMPERATURE -> day.skinTemperature?.deviation
        ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
}

private fun <T, R> Metric<T>.map(transform: (T) -> R): Metric<R> {
    val v = value ?: return Metric.absent(absentReason ?: AbsentReason.NOT_ENOUGH_DATA, confidence)
    return Metric.of(transform(v), confidence)
}
