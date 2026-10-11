package app.circlet.ui.stress

import app.circlet.core.StressPoint
import java.time.Duration

/** The three stress bands. The cut-offs match `Palette.stress`, so a band always has its colour. */
enum class StressBand { LOW, MEDIUM, HIGH }

/** Band for a 0-100 score: under 34 is low, 34 to 66 is medium, 67 and above is high. */
fun bandOf(score: Double): StressBand = when {
    score >= 67.0 -> StressBand.HIGH
    score >= 34.0 -> StressBand.MEDIUM
    else -> StressBand.LOW
}

/** Whole minutes spent in each band. */
data class BandMinutes(val low: Int, val medium: Int, val high: Int) {
    val total: Int get() = low + medium + high

    /** The band with the most minutes, or null when no time was counted. */
    fun dominant(): StressBand? = when {
        total == 0 -> null
        high >= low && high >= medium -> StressBand.HIGH
        medium >= low -> StressBand.MEDIUM
        else -> StressBand.LOW
    }

    fun of(band: StressBand): Int = when (band) {
        StressBand.LOW -> low
        StressBand.MEDIUM -> medium
        StressBand.HIGH -> high
    }
}

/**
 * Minutes per band from a timeline of samples. Each sample counts until the next
 * one, which is capped at [maxGap] so a long pause (the strap off the wrist)
 * does not count as time in a band. Samples out of order are ignored.
 */
fun bandMinutes(points: List<StressPoint>, maxGap: Duration = Duration.ofMinutes(10)): BandMinutes {
    val seconds = LongArray(StressBand.values().size)
    points.zipWithNext { current, next ->
        val gap = Duration.between(current.at, next.at)
        if (!gap.isNegative && !gap.isZero) {
            val counted = if (gap > maxGap) maxGap else gap
            seconds[bandOf(current.score).ordinal] += counted.seconds
        }
    }
    fun minutes(band: StressBand): Int = (seconds[band.ordinal] / 60).toInt()
    return BandMinutes(
        low = minutes(StressBand.LOW),
        medium = minutes(StressBand.MEDIUM),
        high = minutes(StressBand.HIGH),
    )
}
