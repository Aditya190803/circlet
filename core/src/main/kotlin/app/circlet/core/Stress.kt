package app.circlet.core

import java.time.Instant

/**
 * One point on the day's stress timeline.
 * [score] is 0–100. Higher means more stressed.
 */
data class StressPoint(
    val at: Instant,
    val score: Double,
)

/**
 * All-day stress.
 * [score] is 0–100. [timeline] is in time order and may be empty when the score is absent.
 */
data class DayStress(
    val score: Metric<Double>,
    val timeline: List<StressPoint>,
)
