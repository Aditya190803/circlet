package app.circlet.core

import java.time.LocalDate

/**
 * The zone boundaries used when a profile has no custom zones, in whole bpm:
 * the lower bound of zones 1 to 5, then the top of zone 5. Null when they cannot be derived.
 * [restingHistory] is the daily resting heart rate, oldest first.
 */
fun interface ZoneDefaults {
    fun bounds(profile: Profile, on: LocalDate, restingHistory: List<Double>): List<Int>?
}

/** Zones as 50 to 100 percent of max heart rate, for builds without the analytics module. */
val PercentOfMaxZones: ZoneDefaults = ZoneDefaults { profile, on, _ ->
    val maxHr = profile.maxHeartRateBpm ?: profile.ageOn(on)?.let { (208 - 0.7 * it).toInt() }
    maxHr?.let { max -> (5..10).map { max * it / 10 } }
}
