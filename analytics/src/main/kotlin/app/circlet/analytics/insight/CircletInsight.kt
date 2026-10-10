package app.circlet.analytics.insight

import app.circlet.analytics.support.median
import app.circlet.core.DaySummary
import app.circlet.core.InsightEngine
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.MetricKind
import app.circlet.core.PersonalRecord
import app.circlet.core.TagCorrelation
import app.circlet.core.TrendPoint
import app.circlet.core.WeeklyRecap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// The insight surface the rest of the app calls.
// Bedtime is OpenStrap human/coaching.dart: time in bed is need divided by
// the user's own efficiency, backed off from the observed wake. Wake and
// efficiency come from main sleeps in [history]. A missing need, efficiency,
// or wake is absent — 07:00 and 90% are not filled in.
// Correlations are the coaching.dart mean difference (one row per catalogue
// tag and outcome). associations.dart's 84-day block scan is a different
// question than the two-day mean the core contract publishes.

class CircletInsight : InsightEngine {
    override fun trend(days: List<DaySummary>, metric: MetricKind): List<TrendPoint> =
        days.sortedBy { it.date }.map { TrendPoint(it.date, metricValue(it, metric)) }

    override fun records(days: List<DaySummary>): List<PersonalRecord> =
        app.circlet.analytics.insight.records(days)

    override fun weeklyRecap(weekStart: LocalDate, days: List<DaySummary>): WeeklyRecap =
        app.circlet.analytics.insight.weeklyRecap(days, weekStart)

    override fun correlations(
        days: List<DaySummary>,
        tags: List<JournalTag>,
        entries: List<JournalEntry>,
    ): List<TagCorrelation> = tagCorrelations(days, entries, tags)

    override fun recommendedBedtime(history: List<DaySummary>, zone: ZoneId, now: Instant): Instant? {
        val today = now.atZone(zone).toLocalDate()
        val known = history.filter { it.date <= today }
        val need = known.filter { it.sleepNeed.duration.present }.maxByOrNull { it.date }
            ?.sleepNeed?.duration?.value ?: return null
        val efficiency = median(known.mapNotNull { it.sleep.value?.efficiency?.value?.toDouble() }) ?: return null
        val wakes = known.mapNotNull { day ->
            val end = day.sleep.value?.end ?: return@mapNotNull null
            val local = end.atZone(zone)
            local.hour * 60 + local.minute
        }
        val wakeMin = median(wakes.map { it.toDouble() }) ?: return null
        val wakeMinute = wakeMin.roundToInt().coerceIn(0, 24 * 60 - 1)
        val localNow = now.atZone(zone)
        val nowMin = localNow.hour * 60 + localNow.minute + localNow.second / 60.0
        val wakeDate = if (nowMin >= wakeMinute) localNow.toLocalDate().plusDays(1) else localNow.toLocalDate()
        return app.circlet.analytics.insight.recommendedBedtime(need, efficiency, zone, wakeDate, wakeMinute)
    }
}
