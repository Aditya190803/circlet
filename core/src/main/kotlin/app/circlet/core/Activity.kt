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
)

/**
 * A workout that is being recorded.
 * [pausedAt] is set while the workout is paused and null while it runs.
 * [pausedSeconds] is the total of earlier, finished pauses; it excludes a pause still open.
 * Time recorded so far is [activeDuration]. Nothing is added to the totals while paused.
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
) {
    val paused: Boolean get() = pausedAt != null

    /** Recorded time up to [now], leaving out every pause. Never negative. */
    fun activeDuration(now: Instant): Duration {
        val until = pausedAt ?: now
        val active = Duration.between(startedAt, until).minusSeconds(pausedSeconds)
        return if (active.isNegative) Duration.ZERO else active
    }
}

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
