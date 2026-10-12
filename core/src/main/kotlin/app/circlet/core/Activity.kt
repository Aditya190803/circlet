package app.circlet.core

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** A sport the user can pick, or that auto-detection can guess. */
enum class Sport {
    RUNNING,
    CYCLING,
    WALKING,
    HIKING,
    SWIMMING,
    ROWING,
    ELLIPTICAL,
    STAIR_CLIMBER,
    JUMP_ROPE,
    STRENGTH,
    HIIT,
    YOGA,
    PILATES,
    DANCE,
    BOXING,
    MARTIAL_ARTS,
    CLIMBING,
    BASKETBALL,
    SOCCER,
    TENNIS,
    GOLF,
    SKIING,
    SNOWBOARDING,
    SKATING,
    SURFING,
    RUCKING,
    OTHER,
}

/** Whether the user started the activity or the phone detected it. */
enum class ActivitySource {
    AUTO,
    MANUAL,
}

/**
 * How far heart rate fell in the minute after an activity, in beats per minute.
 */
data class HeartRateRecovery(
    val dropBpm: Metric<Int>,
)

/**
 * One workout, detected or logged.
 *
 * [strain] is 0–21 for this activity alone.
 * Heart rates are beats per minute. [calories] are kilocalories.
 * [sportConfidence] is set for [ActivitySource.AUTO] and null for a sport the user chose.
 * [heartRateRecovery] is null when the minute after the activity was not sampled.
 *
 * [start] and [end] are wall-clock times and include any pause.
 * [pausedSeconds] is how long those pauses lasted. [pauses] are the wall-clock spans
 * the day engine leaves out of strain, zones, calories, and the heart-rate average and max.
 * Heart-rate recovery still uses the window after [end].
 */
data class Activity(
    val id: String,
    val source: ActivitySource,
    val sport: Sport,
    val sportConfidence: Confidence?,
    val start: Instant,
    val end: Instant,
    val strain: Metric<Double>,
    val averageHeartRateBpm: Metric<Int>,
    val maxHeartRateBpm: Metric<Int>,
    val calories: Metric<Int>,
    val zoneMinutes: ZoneMinutes,
    val heartRateRecovery: HeartRateRecovery?,
    val pausedSeconds: Long = 0,
    val pauses: List<WorkoutPause> = emptyList(),
)

/** One finished pause. Samples in `[start, end)` are not part of the workout. */
data class WorkoutPause(
    val start: Instant,
    val end: Instant,
)

/**
 * Why [start] and [end] cannot be stored, as a sentence safe to show.
 * Null when the window is usable: the end is after the start, not after [now], and at most 24 hours later.
 */
fun rejectedActivityWindow(start: Instant, end: Instant, now: Instant): String? {
    if (!end.isAfter(start)) return "The activity has to end after it starts."
    if (end.isAfter(now)) return "The activity cannot end in the future."
    if (Duration.between(start, end) > Duration.ofHours(24)) return "An activity cannot be longer than 24 hours."
    return null
}

/**
 * A workout that is being recorded.
 * [pausedAt] is set while the workout is paused and null while it runs.
 * [pausedSeconds] is the total of earlier, finished pauses; it excludes a pause still open.
 * [pauses] are those finished pauses. Time recorded so far is [activeDuration].
 * Nothing is added to the totals while paused.
 * [strain] is 0–21 so far. [caloriesKcal] are kilocalories so far.
 * Heart rates are beats per minute.
 */
data class LiveWorkout(
    val sport: Sport,
    val startedAt: Instant,
    val heartRateBpm: Int?,
    val strain: Double,
    val caloriesKcal: Int,
    val zoneMinutes: ZoneMinutes,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val pausedAt: Instant? = null,
    val pausedSeconds: Long = 0,
    val pauses: List<WorkoutPause> = emptyList(),
) {
    val paused: Boolean get() = pausedAt != null

    /** Recorded time up to [now], leaving out every pause. Never negative. */
    fun activeDuration(now: Instant): Duration {
        val until = pausedAt ?: now
        val active = Duration.between(startedAt, until).minusSeconds(pausedSeconds)
        return if (active.isNegative) Duration.ZERO else active
    }

    /**
     * Closes a pause that is still open at [at].
     * A workout that is running is returned unchanged. A zero-length pause adds no span.
     */
    fun closingPause(at: Instant): LiveWorkout {
        val open = pausedAt ?: return this
        val added = Duration.between(open, at).seconds.coerceAtLeast(0)
        val spans = if (at.isAfter(open)) pauses + WorkoutPause(open, at) else pauses
        return copy(pausedAt = null, pausedSeconds = pausedSeconds + added, pauses = spans)
    }
}

/**
 * A live workout and the beats per minute counted while it was running.
 * Paused beats are not in [beats]. Restoring both keeps averages across process death.
 */
data class LiveSession(
    val workout: LiveWorkout,
    val beats: List<Int> = emptyList(),
)

/**
 * Manual workouts: start, pause, resume, stop, edit, delete, and merge.
 * [live] is null when nothing is being recorded.
 * [pause] and [resume] fail with [CoreError.Invalid] when there is nothing to pause or resume.
 * [stop] works while paused and closes the open pause first.
 * [stop] returns the stored activity. [merge] returns the combined activity.
 */
interface WorkoutController {
    val live: StateFlow<LiveWorkout?>

    suspend fun start(sport: Sport): Outcome<Unit>

    suspend fun pause(): Outcome<Unit>

    suspend fun resume(): Outcome<Unit>

    suspend fun stop(): Outcome<Activity>

    suspend fun edit(activity: Activity): Outcome<Unit>

    suspend fun delete(id: String): Outcome<Unit>

    suspend fun merge(ids: List<String>): Outcome<Activity>
}
