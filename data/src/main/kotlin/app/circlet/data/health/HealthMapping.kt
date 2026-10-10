package app.circlet.data.health

import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.DaySummary
import app.circlet.core.HealthKind
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.Sport
import java.time.Instant
import java.time.ZoneId

/**
 * One Health Connect row derived from a stored day.
 * Absent metrics are not represented. [clientRecordId] is stable for the same source id,
 * so writing the list again updates the same rows instead of duplicating them.
 */
data class HealthWrite(
    val kind: HealthKind,
    val clientRecordId: String,
    val start: Instant,
    val end: Instant,
    val offsetTotalSeconds: Int,
    val stages: List<HealthStage> = emptyList(),
    val bpm: Long? = null,
    val hrvRmssdMs: Double? = null,
    val breathsPerMinute: Double? = null,
    val activeKcal: Double? = null,
    val steps: Long? = null,
    val sport: Sport? = null,
    val manual: Boolean = false,
)

data class HealthStage(
    val start: Instant,
    val end: Instant,
    val stage: SleepStage,
)

/**
 * Measured values only: a null metric is omitted, and nothing is invented in its place.
 * Active energy is the sum of activity calories, not the day's total.
 * Records that would end in the future are clipped to [now]; a row that is entirely in the future is omitted.
 */
fun healthWrites(day: DaySummary, zone: ZoneId, now: Instant): List<HealthWrite> {
    val writes = ArrayList<HealthWrite>()
    day.sleep.value?.let { session -> sleepWrite(session, zone, now)?.let(writes::add) }
    day.naps.forEach { session -> sleepWrite(session, zone, now)?.let(writes::add) }
    val at = pointTime(day, zone, now)
    if (at != null) {
        day.heart.resting.bpm.value?.let { bpm ->
            writes += point(HealthKind.RESTING_HEART_RATE, "circlet:rhr:${day.date}", at, zone, bpm = bpm.toLong())
        }
        day.heart.hrv.rmssdMs.value?.let { rmssd ->
            writes += point(HealthKind.HRV, "circlet:hrv:${day.date}", at, zone, hrvRmssdMs = rmssd)
        }
        day.heart.respiratoryRate.breathsPerMinute.value?.let { rate ->
            writes += point(HealthKind.RESPIRATORY_RATE, "circlet:rr:${day.date}", at, zone, breathsPerMinute = rate)
        }
    }
    energyWrite(day, zone, now)?.let { writes += it }
    day.activities.forEach { activity -> workoutWrite(activity, zone, now)?.let { writes += it } }
    stepsWrite(day, zone, now)?.let { writes += it }
    return writes
}

private fun sleepWrite(session: SleepSession, zone: ZoneId, now: Instant): HealthWrite? {
    val range = clip(session.start, session.end, now) ?: return null
    val stages = session.epochs.mapNotNull { epoch ->
        val end = epoch.start.plus(epoch.duration)
        val clipped = clip(maxOf(epoch.start, range.first), minOf(end, range.second), range.second.plusMillis(1))
            ?: return@mapNotNull null
        if (clipped.first.isBefore(range.first) || clipped.second.isAfter(range.second)) return@mapNotNull null
        HealthStage(clipped.first, clipped.second, epoch.stage)
    }
    return HealthWrite(
        kind = HealthKind.SLEEP,
        clientRecordId = "circlet:sleep:${session.id}",
        start = range.first,
        end = range.second,
        offsetTotalSeconds = offset(zone, range.first),
        stages = stages,
    )
}

private fun energyWrite(day: DaySummary, zone: ZoneId, now: Instant): HealthWrite? {
    val measured = day.activities.mapNotNull { activity ->
        activity.calories.value?.let { activity to it }
    }
    if (measured.isEmpty()) return null
    val start = measured.minOf { it.first.start }
    val end = measured.maxOf { it.first.end }
    val range = clip(start, end, now) ?: return null
    return HealthWrite(
        kind = HealthKind.ACTIVE_ENERGY,
        clientRecordId = "circlet:energy:${day.date}",
        start = range.first,
        end = range.second,
        offsetTotalSeconds = offset(zone, range.first),
        activeKcal = measured.sumOf { it.second }.toDouble(),
    )
}

private fun workoutWrite(activity: Activity, zone: ZoneId, now: Instant): HealthWrite? {
    val range = clip(activity.start, activity.end, now) ?: return null
    return HealthWrite(
        kind = HealthKind.WORKOUTS,
        clientRecordId = "circlet:workout:${activity.id}",
        start = range.first,
        end = range.second,
        offsetTotalSeconds = offset(zone, range.first),
        sport = activity.sport,
        manual = activity.source == ActivitySource.MANUAL,
    )
}

private fun stepsWrite(day: DaySummary, zone: ZoneId, now: Instant): HealthWrite? {
    val count = day.steps.count.value ?: return null
    val start = day.date.atStartOfDay(zone).toInstant()
    val end = day.date.plusDays(1).atStartOfDay(zone).toInstant()
    val range = clip(start, end, now) ?: return null
    return HealthWrite(
        kind = HealthKind.STEPS,
        clientRecordId = "circlet:steps:${day.date}",
        start = range.first,
        end = range.second,
        offsetTotalSeconds = offset(zone, range.first),
        steps = count.toLong(),
    )
}

private fun point(
    kind: HealthKind,
    id: String,
    at: Instant,
    zone: ZoneId,
    bpm: Long? = null,
    hrvRmssdMs: Double? = null,
    breathsPerMinute: Double? = null,
): HealthWrite = HealthWrite(
    kind = kind,
    clientRecordId = id,
    start = at,
    end = at.plusMillis(1),
    offsetTotalSeconds = offset(zone, at),
    bpm = bpm,
    hrvRmssdMs = hrvRmssdMs,
    breathsPerMinute = breathsPerMinute,
)

private fun pointTime(day: DaySummary, zone: ZoneId, now: Instant): Instant? {
    val dayStart = day.date.atStartOfDay(zone).toInstant()
    val candidate = day.sleep.value?.end ?: day.date.atTime(12, 0).atZone(zone).toInstant()
    val stamped = if (candidate.isBefore(now)) candidate else now.minusMillis(1)
    if (stamped.isBefore(dayStart) || !stamped.isBefore(now)) return null
    return stamped
}

private fun clip(start: Instant, end: Instant, now: Instant): Pair<Instant, Instant>? {
    val capped = if (end.isAfter(now)) now else end
    if (!start.isBefore(capped)) return null
    return start to capped
}

private fun offset(zone: ZoneId, instant: Instant): Int = zone.rules.getOffset(instant).totalSeconds

private fun maxOf(left: Instant, right: Instant): Instant = if (left.isAfter(right)) left else right

private fun minOf(left: Instant, right: Instant): Instant = if (left.isBefore(right)) left else right

/** Deletes then inserts, so a second call with the same client ids replaces the previous rows. */
internal suspend fun writeMeasured(client: HealthRecordClient, writes: List<HealthWrite>) {
    writes.groupBy { it.kind }.forEach { (kind, rows) ->
        client.delete(kind, rows.map { it.clientRecordId })
    }
    if (writes.isNotEmpty()) client.insert(writes)
}

internal interface HealthRecordClient {
    suspend fun delete(kind: HealthKind, clientRecordIds: List<String>)
    suspend fun insert(writes: List<HealthWrite>)
}
