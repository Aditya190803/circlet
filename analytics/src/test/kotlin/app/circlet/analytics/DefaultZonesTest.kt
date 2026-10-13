package app.circlet.analytics

import app.circlet.analytics.strain.defaultZoneBounds
import app.circlet.core.HeartRateZone
import app.circlet.core.Profile
import app.circlet.core.Sex
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultZonesTest {
    private val date = LocalDate.of(2026, 10, 1)

    private fun profile(maxHr: Int?, age: Int? = 30, zones: List<HeartRateZone> = emptyList()) = Profile(
        birthDate = null,
        ageYears = age,
        sex = Sex.MALE,
        heightCm = 175.0,
        weightKg = 70.0,
        maxHeartRateBpm = maxHr,
        customZones = zones,
    )

    @Test
    fun withoutRestingHistoryTheBoundsAreBandsOfMaxHeartRate() {
        assertEquals(listOf(100, 120, 140, 160, 180, 200), defaultZoneBounds(profile(200), date, emptyList()))
    }

    @Test
    fun withTwoWeeksOfRestingRateTheBoundsAreReserveBands() {
        assertEquals(listOf(130, 144, 158, 172, 186, 200), defaultZoneBounds(profile(200), date, List(14) { 60.0 }))
    }

    @Test
    fun customZonesDoNotChangeTheDefaults() {
        val custom = (1..5).map { HeartRateZone(it, 80 + 20 * it, 100 + 20 * it) }
        assertEquals(
            defaultZoneBounds(profile(200), date, emptyList()),
            defaultZoneBounds(profile(200, zones = custom), date, emptyList()),
        )
    }

    @Test
    fun noAgeAndNoMaxHeartRateGivesNothing() {
        assertNull(defaultZoneBounds(profile(null, age = null), date, emptyList()))
    }
}
