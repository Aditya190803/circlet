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
 * [bandMinutes] counts whole minutes in each band from the timeline, so the stress
 * screen does not re-derive them. It is zero when the timeline is empty.
 */
data class DayStress(
    val score: Metric<Double>,
    val timeline: List<StressPoint>,
    val bandMinutes: StressBandMinutes = StressBandMinutes.ZERO,
)

/** Cut-off for the low stress band: scores under this are low. Matches the app palette. */
const val STRESS_LOW_CUTOFF = 34.0

/** Cut-off for the high stress band: scores at or above this are high. Matches the app palette. */
const val STRESS_HIGH_CUTOFF = 67.0

/** Which of the three stress bands a 0–100 score falls in. */
fun stressBandOf(score: Double): StressBand = when {
    score >= STRESS_HIGH_CUTOFF -> StressBand.HIGH
    score >= STRESS_LOW_CUTOFF -> StressBand.MEDIUM
    else -> StressBand.LOW
}

/** The three stress bands. */
enum class StressBand {
    LOW,
    MEDIUM,
    HIGH,
}

/** Whole minutes spent in each stress band. */
data class StressBandMinutes(
    val lowMinutes: Int,
    val mediumMinutes: Int,
    val highMinutes: Int,
) {
    val totalMinutes: Int get() = lowMinutes + mediumMinutes + highMinutes

    companion object {
        val ZERO = StressBandMinutes(0, 0, 0)
    }
}
