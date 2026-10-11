package app.circlet.analytics.strain

import app.circlet.core.Profile
import app.circlet.core.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DayCaloriesTest {
    private val date = LocalDate.of(2026, 6, 15)
    private val profile = Profile(null, 35, Sex.MALE, 180.0, 80.0, null, emptyList())
    private val restingDay = List(1440) { 55.0 }

    @Test
    fun aDayInProgressCountsBasalEnergyOnlyForTheTimePassed() {
        val whole = dayCaloriesFromMinutes(restingDay, profile, date, 50.0, 185.0).value!!
        val half = dayCaloriesFromMinutes(restingDay, profile, date, 50.0, 185.0, elapsedMinutes = 720).value!!
        // Mifflin-St Jeor for this profile is 1,755 kcal a day.
        assertEquals(1755, whole)
        assertTrue("half $half", half in 877..878)
    }

    @Test
    fun elapsedMinutesStopsAtTheEndsOfTheDay() {
        val noon = date.atTime(12, 0).toInstant(ZoneOffset.UTC)
        assertEquals(720, elapsedMinutes(ZoneOffset.UTC, date, noon))
        assertEquals(1440, elapsedMinutes(ZoneOffset.UTC, date, noon.plusSeconds(86_400)))
        assertEquals(0, elapsedMinutes(ZoneOffset.UTC, date, noon.minusSeconds(86_400)))
    }
}
