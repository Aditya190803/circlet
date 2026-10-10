package app.circlet.core

import java.time.Duration
import java.time.Instant

/** One epoch of a sleep session. */
enum class SleepStage {
    AWAKE,
    LIGHT,
    DEEP,
    REM,
}

/** Main nocturnal sleep, or a nap kept separate from it. */
enum class SleepKind {
    MAIN,
    NAP,
}

/**
 * A contiguous stretch of one [SleepStage].
 * [duration] is the length of the stretch, typically several minutes.
 */
data class SleepEpoch(
    val start: Instant,
    val stage: SleepStage,
    val duration: Duration,
)

/**
 * One sleep cycle inside a session, numbered from 1 in time order.
 * [start] and [end] bound the cycle. The epochs in the parent session carry the stages.
 */
data class SleepCycle(
    val number: Int,
    val start: Instant,
    val end: Instant,
)

/**
 * A wake inside the sleep, after sleep onset.
 * The time it took to fall asleep is [SleepSession.latency], not a wake event.
 * [duration] is how long the wake lasted.
 */
data class WakeEvent(
    val at: Instant,
    val duration: Duration,
)

/**
 * One sleep, main or nap.
 *
 * [timeInBed] and [timeAsleep] are durations. Time asleep excludes awake epochs.
 * [latency] is the time from getting into bed to the first asleep epoch.
 * [efficiency] is asleep / in bed, percent, 0–100.
 */
data class SleepSession(
    val id: String,
    val kind: SleepKind,
    val start: Instant,
    val end: Instant,
    val epochs: List<SleepEpoch>,
    val cycles: List<SleepCycle>,
    val latency: Duration,
    val efficiency: Metric<Int>,
    val wakeEvents: List<WakeEvent>,
    val timeInBed: Duration,
    val timeAsleep: Duration,
)

/**
 * How much sleep tonight's strain and debt call for.
 * [duration] is the need. [recommendedBedtime] is when to be in bed to meet it
 * for a 07:00 wake, or null when the need itself is absent.
 */
data class SleepNeed(
    val duration: Metric<Duration>,
    val recommendedBedtime: Instant?,
)

/**
 * Accumulated shortfall against need. The duration is zero or positive, never negative.
 */
data class SleepDebt(
    val shortfall: Metric<Duration>,
)

/**
 * Asleep time as a percent of [SleepNeed]. The percent may exceed 100.
 */
data class SleepPerformance(
    val percent: Metric<Int>,
)

/**
 * Sleep regularity index, 0–100. Higher means recent bedtimes agree.
 */
data class SleepConsistency(
    val index: Metric<Double>,
)
