package app.circlet.analytics.strain

import app.circlet.core.Profile
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The zone boundaries the app uses when [Profile.customZones] is empty, in whole bpm:
 * the lower bound of zones 1 to 5, then the top of zone 5 (max heart rate).
 * [restingHistory] is the daily resting rate, oldest first; with enough of it the zones are
 * heart-rate-reserve bands, otherwise bands of max heart rate. Null when no max heart rate can be derived.
 */
fun defaultZoneBounds(profile: Profile, on: LocalDate, restingHistory: List<Double>): List<Int>? {
    val set = zoneSet(profile.copy(customZones = emptyList()), on, restingHistory, null) ?: return null
    return set.zones.map { it.lower.roundToInt() } + set.zones.last().upper.roundToInt()
}
