package app.circlet.core

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
 * Elapsed time is [java.time.Duration.between] [startedAt] and [TimeSource.now].
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
)

/**
 * Manual workouts: start, stop, edit, delete, and merge.
 * [live] is null when nothing is being recorded.
 * [stop] returns the stored activity. [merge] returns the combined activity.
 */
interface WorkoutController {
    val live: StateFlow<LiveWorkout?>

    suspend fun start(sport: Sport): Outcome<Unit>

    suspend fun stop(): Outcome<Activity>

    suspend fun edit(activity: Activity): Outcome<Unit>

    suspend fun delete(id: String): Outcome<Unit>

    suspend fun merge(ids: List<String>): Outcome<Activity>
}
