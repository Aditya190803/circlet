package app.circlet.data.repo

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ageOn
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.LiveWorkout
import app.circlet.core.Metric
import app.circlet.core.Outcome
import app.circlet.core.Sport
import app.circlet.core.WorkoutController
import app.circlet.core.ZoneMinutes
import app.circlet.core.rejectedActivityWindow
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.codec.LiveCodec
import app.circlet.data.db.ActivityEntity
import app.circlet.data.db.LIVE_ROW
import app.circlet.data.db.LiveWorkoutEntity
import app.circlet.data.pipeline.RecomputePipeline
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

internal class WorkoutControllerImpl(
    private val store: Store,
    private val memory: Memory,
    private val pipeline: RecomputePipeline,
) : WorkoutController {
    override val live: StateFlow<LiveWorkout?> = memory.live

    override suspend fun start(sport: Sport): Outcome<Unit> {
        if (memory.live.value != null) return Outcome.Err(CoreError.Busy)
        val now = store.time.now()
        val workout = LiveWorkout(
            sport = sport,
            startedAt = now,
            heartRateBpm = null,
            strain = 0.0,
            caloriesKcal = 0,
            zoneMinutes = ZoneMinutes.ZERO,
            averageHeartRateBpm = null,
            maxHeartRateBpm = null,
        )
        memory.liveSamples = 0
        memory.liveTickMillis = now.toEpochMilli()
        memory.zoneSeconds.fill(0)
        persist(workout)
        return Outcome.Ok(Unit)
    }

    override suspend fun pause(): Outcome<Unit> {
        val current = memory.live.value ?: return Outcome.Err(CoreError.Invalid("no workout is running"))
        if (current.paused) return Outcome.Err(CoreError.Invalid("workout is already paused"))
        persist(current.copy(pausedAt = store.time.now()))
        return Outcome.Ok(Unit)
    }

    override suspend fun resume(): Outcome<Unit> {
        val current = memory.live.value ?: return Outcome.Err(CoreError.Invalid("no workout is running"))
        if (!current.paused) return Outcome.Err(CoreError.Invalid("workout is not paused"))
        persist(current.closingPause(store.time.now()))
        return Outcome.Ok(Unit)
    }

    override suspend fun stop(): Outcome<Activity> {
        val current = memory.live.value ?: return Outcome.Err(CoreError.Invalid("no workout is being recorded"))
        val end = store.time.now()
        if (!end.isAfter(current.startedAt)) {
            return Outcome.Err(CoreError.Invalid("workout has not started"))
        }
        val closed = current.closingPause(end)
        val zone = store.time.zone()
        val date = current.startedAt.atZone(zone).toLocalDate()
        val weight = memory.profile.value.weightKg
        val activity = Activity(
            id = "m-${current.startedAt.toEpochMilli()}",
            source = ActivitySource.MANUAL,
            sport = current.sport,
            sportConfidence = null,
            start = current.startedAt,
            end = end,
            strain = Metric.of(closed.strain, Confidence.LOW),
            averageHeartRateBpm = closed.averageHeartRateBpm?.let { Metric.of(it, Confidence.LOW) }
                ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            maxHeartRateBpm = closed.maxHeartRateBpm?.let { Metric.of(it, Confidence.LOW) }
                ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            calories = if (weight != null) Metric.of(closed.caloriesKcal, Confidence.LOW)
            else Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            zoneMinutes = closed.zoneMinutes,
            heartRateRecovery = null,
            pausedSeconds = closed.pausedSeconds,
            pauses = closed.pauses,
        )
        store.io {
            val db = store.database
            db.activities().insert(listOf(activity.toStored(date.toString(), userEdited = false)))
            db.liveWorkout().clear()
            db.dirty().mark(date.toString())
            val endDate = end.atZone(zone).toLocalDate()
            if (endDate != date) db.dirty().mark(endDate.toString())
        }
        memory.live.value = null
        memory.liveSamples = 0
        memory.zoneSeconds.fill(0)
        pipeline.nudge()
        return Outcome.Ok(activity)
    }

    override suspend fun edit(activity: Activity): Outcome<Unit> {
        val existing = store.io { store.database.activities().get(activity.id) }
            ?: return Outcome.Err(CoreError.Invalid("no activity with that id"))
        if (existing.suppressed) return Outcome.Err(CoreError.Invalid("no activity with that id"))
        val window = rejectedActivityWindow(activity.start, activity.end, store.time.now())
        if (window != null) return Outcome.Err(CoreError.Invalid(window))
        val zone = store.time.zone()
        val date = activity.start.atZone(zone).toLocalDate().toString()
        val endDate = activity.end.atZone(zone).toLocalDate().toString()
        store.io {
            store.database.activities().insert(
                listOf(existing.copy(
                    source = activity.source.name,
                    userEdited = true,
                    localDate = date,
                    startMillis = activity.start.toEpochMilli(),
                    endMillis = activity.end.toEpochMilli(),
                    payload = ActivityCodec.encode(activity),
                )),
            )
            store.database.dirty().mark(existing.localDate)
            store.database.dirty().mark(date)
            if (endDate != date) store.database.dirty().mark(endDate)
        }
        pipeline.nudge()
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: String): Outcome<Unit> {
        val existing = store.io { store.database.activities().get(id) }
            ?: return Outcome.Err(CoreError.Invalid("no activity with that id"))
        store.io {
            if (existing.source == ActivitySource.AUTO.name) {
                store.database.activities().insert(listOf(existing.copy(suppressed = true)))
            } else {
                store.database.activities().delete(id)
            }
            store.database.dirty().mark(existing.localDate)
        }
        pipeline.nudge()
        return Outcome.Ok(Unit)
    }

    override suspend fun merge(ids: List<String>): Outcome<Activity> {
        if (ids.size < 2) return Outcome.Err(CoreError.Invalid("merge needs two activities"))
        val rows = store.io { ids.map { id -> store.database.activities().get(id) } }
        if (rows.any { it == null || it.suppressed }) {
            return Outcome.Err(CoreError.Invalid("no activity with that id"))
        }
        val present = rows.filterNotNull()
        if (present.map { it.localDate }.distinct().size != 1) {
            return Outcome.Err(CoreError.Invalid("activities are on different days"))
        }
        val activities = present.mapNotNull { it.visibleActivity() }
        if (activities.size != ids.size) return Outcome.Err(CoreError.Invalid("no activity with that id"))
        val merged = mergeActivities(activities)
        val date = present.first().localDate
        store.io {
            for (row in present) {
                if (row.source == ActivitySource.AUTO.name) {
                    store.database.activities().insert(listOf(row.copy(suppressed = true)))
                } else {
                    store.database.activities().delete(row.id)
                }
            }
            store.database.activities().insert(listOf(merged.toStored(date, userEdited = true)))
            store.database.dirty().mark(date)
        }
        pipeline.nudge()
        return Outcome.Ok(merged)
    }

    private suspend fun persist(workout: LiveWorkout) {
        memory.live.value = workout
        val payload = LiveCodec.encode(workout, memory.liveSamples, memory.liveTickMillis)
        store.lock.withLock {
            store.io { store.database.liveWorkout().upsert(LiveWorkoutEntity(LIVE_ROW, payload)) }
        }
    }
}

/** Live-screen estimate from zone minutes. [app.circlet.core.DayEngine] replaces it when it echoes the activity id. */
internal fun liveStrain(zones: ZoneMinutes): Double {
    val weights = doubleArrayOf(0.0, 0.4, 0.9, 1.6, 2.6, 4.0)
    val weighted = zones.minutes.indices.sumOf { index -> zones.minutes[index] * weights[index] }
    return (weighted / 20.0).coerceIn(0.0, 21.0)
}

internal fun liveCalories(zones: ZoneMinutes, weightKg: Double?): Int {
    if (weightKg == null) return 0
    val perMinute = doubleArrayOf(0.0, 4.0, 6.0, 8.5, 11.0, 14.0)
    return zones.minutes.indices.sumOf { index ->
        zones.minutes[index] * perMinute[index] * (weightKg / 70.0)
    }.roundToInt()
}

internal fun zoneIndex(bpm: Int, profile: app.circlet.core.Profile, on: java.time.LocalDate): Int {
    if (profile.customZones.size == 5) {
        val ordered = profile.customZones.sortedBy { it.index }
        for (zone in ordered) {
            val inZone = bpm >= zone.fromBpm && (if (zone.index == 5) bpm <= zone.untilBpm else bpm < zone.untilBpm)
            if (inZone) return zone.index
        }
        return 0
    }
    val age = profile.ageOn(on)
    val maxHr = profile.maxHeartRateBpm ?: age?.let { (220 - it).coerceIn(120, 230) } ?: 190
    val ratio = bpm.toDouble() / maxHr.toDouble()
    return when {
        ratio < 0.50 -> 0
        ratio < 0.60 -> 1
        ratio < 0.70 -> 2
        ratio < 0.80 -> 3
        ratio < 0.90 -> 4
        else -> 5
    }
}

private fun Activity.toStored(date: String, userEdited: Boolean): ActivityEntity = ActivityEntity(
    id = id,
    localDate = date,
    source = source.name,
    userEdited = userEdited,
    suppressed = false,
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    payload = ActivityCodec.encode(this),
)

private fun mergeActivities(activities: List<Activity>): Activity {
    val longer = activities.maxBy { Duration.between(it.start, it.end) }
    val minutes = activities.map { Duration.between(it.start, it.end).toMinutes().coerceAtLeast(1) }
    val total = minutes.sum()
    val average = activities.zip(minutes).sumOf { (activity, span) ->
        (activity.averageHeartRateBpm.value ?: 0).toLong() * span
    } / total
    return longer.copy(
        id = "m-${activities.minOf { it.start.toEpochMilli() }}",
        source = ActivitySource.MANUAL,
        sportConfidence = null,
        start = activities.minOf { it.start },
        end = activities.maxOf { it.end },
        strain = activities.mapNotNull { it.strain.value }.maxOrNull()?.let { Metric.of(it, Confidence.LOW) }
            ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageHeartRateBpm = if (activities.any { it.averageHeartRateBpm.value != null }) {
            Metric.of(average.toInt(), Confidence.LOW)
        } else {
            Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
        },
        maxHeartRateBpm = activities.mapNotNull { it.maxHeartRateBpm.value }.maxOrNull()?.let { Metric.of(it, Confidence.LOW) }
            ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        calories = activities.mapNotNull { it.calories.value }.let { values ->
            if (values.isEmpty()) Metric.absent(AbsentReason.NOT_ENOUGH_DATA) else Metric.of(values.sum(), Confidence.LOW)
        },
        zoneMinutes = activities.map { it.zoneMinutes }.reduce { left, right -> left.plus(right) },
        heartRateRecovery = null,
    )
}
