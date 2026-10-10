package app.circlet.core

/**
 * Minutes in heart-rate zones 0 through 5.
 * Index 0 is below zone 1. Index 5 is the top zone. Each value is a whole minute.
 */
data class ZoneMinutes(
    val minutes: List<Int>,
) {
    init {
        require(minutes.size == ZONE_COUNT) { "zone minutes cover zone 0 through zone 5" }
        require(minutes.all { it >= 0 }) { "zone minutes cannot be negative" }
    }

    val total: Int get() = minutes.sum()

    fun plus(other: ZoneMinutes): ZoneMinutes = ZoneMinutes(List(ZONE_COUNT) { minutes[it] + other.minutes[it] })

    companion object {
        const val ZONE_COUNT = 6

        fun of(zone0: Int, zone1: Int, zone2: Int, zone3: Int, zone4: Int, zone5: Int): ZoneMinutes =
            ZoneMinutes(listOf(zone0, zone1, zone2, zone3, zone4, zone5))

        val ZERO: ZoneMinutes = ZoneMinutes(List(ZONE_COUNT) { 0 })
    }
}

/**
 * Cardiovascular strain on a 0–21 scale, with the zone minutes that produced it.
 * Calories for the same window live on [DaySummary] and on each [Activity].
 */
data class Strain(
    val score: Metric<Double>,
    val zoneMinutes: ZoneMinutes,
)

/**
 * The strain to aim for today given recovery, on the same 0–21 scale.
 */
data class TargetStrain(
    val score: Metric<Double>,
)
