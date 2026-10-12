package app.circlet.strap.workout

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.LiveStrainEstimator
import app.circlet.core.LiveWorkout
import app.circlet.core.Metric
import app.circlet.core.Outcome
import app.circlet.core.Sport
import app.circlet.core.TimeSource
import app.circlet.core.WorkoutController
import app.circlet.core.WorkoutStore
import app.circlet.core.ZoneMinutes
import java.time.Duration
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

internal interface HeartDemand {
    suspend fun setWorkout(on: Boolean): Outcome<Unit>
    fun listen(listener: (Int?) -> Unit)
}

/** Live session: start, stop, heart rate, and elapsed time. Stored activities go to [WorkoutStore]. */
internal class StrapWorkouts(
    private val store: WorkoutStore,
    private val time: TimeSource,
    private val estimator: LiveStrainEstimator,
    private val heart: HeartDemand,
) : WorkoutController {
    private val liveState = MutableStateFlow<LiveWorkout?>(null)
    private val rates = ArrayList<Int>()
    private val gate = Mutex()

    override val live: StateFlow<LiveWorkout?> = liveState

    init {
        heart.listen { bpm -> onHeart(bpm) }
    }

    override suspend fun start(sport: Sport): Outcome<Unit> {
        if (!gate.tryLock()) return busy()
        try {
            if (liveState.value != null) return busy()
            val streaming = heart.setWorkout(true)
            if (streaming is Outcome.Err) return streaming
            rates.clear()
            liveState.value = LiveWorkout(
                sport = sport,
                startedAt = time.now(),
                heartRateBpm = null,
                strain = 0.0,
                caloriesKcal = 0,
                zoneMinutes = ZoneMinutes.ZERO,
                averageHeartRateBpm = null,
                maxHeartRateBpm = null,
            )
            return Outcome.Ok(Unit)
        } finally {
            gate.unlock()
        }
    }

    // TODO(pause): placeholder so the module compiles; the real pause and resume replace these.
    override suspend fun pause(): Outcome<Unit> = Outcome.Err(CoreError.Invalid("pause is not available yet"))

    override suspend fun resume(): Outcome<Unit> = Outcome.Err(CoreError.Invalid("pause is not available yet"))

    override suspend fun stop(): Outcome<Activity> {
        if (!gate.tryLock()) return busy()
        try {
            val current = liveState.value ?: return invalid("No workout is being recorded")
            heart.setWorkout(false)
            val end = time.now()
            val elapsed = Duration.between(current.startedAt, end).coerceAtLeast(Duration.ZERO)
            val snapshot = rates.toList()
            val numbers = estimator.load(elapsed, snapshot)
            val activity = Activity(
                id = UUID.randomUUID().toString(),
                source = ActivitySource.MANUAL,
                sport = current.sport,
                sportConfidence = null,
                start = current.startedAt,
                end = end,
                strain = numbers.strain,
                averageHeartRateBpm = heartMetric(snapshot.averageOrNull()),
                maxHeartRateBpm = heartMetric(snapshot.maxOrNull()),
                calories = numbers.calories,
                zoneMinutes = numbers.zoneMinutes,
                heartRateRecovery = null,
            )
            store.insert(activity)
            rates.clear()
            liveState.value = null
            return Outcome.Ok(activity)
        } finally {
            gate.unlock()
        }
    }

    override suspend fun edit(activity: Activity): Outcome<Unit> {
        if (activity.id.isBlank()) return invalid("Activity id is empty")
        if (store.get(activity.id) == null) return invalid("No activity with that id")
        if (activity.end.isBefore(activity.start)) return invalid("Activity ends before it starts")
        store.update(activity)
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: String): Outcome<Unit> {
        if (id.isBlank()) return invalid("Activity id is empty")
        store.delete(id)
        return Outcome.Ok(Unit)
    }

    override suspend fun merge(ids: List<String>): Outcome<Activity> {
        if (ids.size < 2) return invalid("Merge needs two activities")
        val found = ArrayList<Activity>(ids.size)
        for (id in ids) {
            found += store.get(id) ?: return invalid("No activity with that id")
        }
        val strain = Metric.absent<Double>(AbsentReason.NOT_ENOUGH_DATA)
        val calories = found.map { it.calories }.reduce { left, right ->
            val a = left.value
            val b = right.value
            if (a == null || b == null) Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
            else Metric.of(a + b, Confidence.LOW)
        }
        val maxes = found.map { it.maxHeartRateBpm.value }
        val maxHr = if (maxes.all { it != null }) {
            Metric.of(maxes.filterNotNull().max(), Confidence.LOW)
        } else {
            Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
        }
        val zones = found.map { it.zoneMinutes }.reduce { left, right -> left.plus(right) }
        val sport = found.map { it.sport }.distinct().singleOrNull() ?: Sport.OTHER
        val merged = Activity(
            id = UUID.randomUUID().toString(),
            source = ActivitySource.MANUAL,
            sport = sport,
            sportConfidence = null,
            start = found.minOf { it.start },
            end = found.maxOf { it.end },
            strain = strain,
            averageHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            maxHeartRateBpm = maxHr,
            calories = calories,
            zoneMinutes = zones,
            heartRateRecovery = null,
        )
        store.insert(merged)
        found.forEach { store.delete(it.id) }
        return Outcome.Ok(merged)
    }

    private fun onHeart(bpm: Int?) {
        val current = liveState.value ?: return
        val measured = bpm?.takeIf { it in 25..230 }
        if (measured != null) rates += measured
        val elapsed = Duration.between(current.startedAt, time.now()).coerceAtLeast(Duration.ZERO)
        val numbers = estimator.load(elapsed, rates.toList())
        liveState.value = current.copy(
            heartRateBpm = measured,
            strain = numbers.strain.value ?: 0.0,
            caloriesKcal = numbers.calories.value ?: 0,
            zoneMinutes = numbers.zoneMinutes,
            averageHeartRateBpm = rates.averageOrNull(),
            maxHeartRateBpm = rates.maxOrNull(),
        )
    }

    private fun heartMetric(value: Int?): Metric<Int> =
        if (value == null) Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
        else Metric.of(value, Confidence.MEDIUM)

    private fun List<Int>.averageOrNull(): Int? =
        if (isEmpty()) null else (sum().toDouble() / size).roundToInt()

    private fun <T> busy(): Outcome<T> = Outcome.Err(CoreError.Busy)
    private fun <T> invalid(detail: String): Outcome<T> = Outcome.Err(CoreError.Invalid(detail))
}
