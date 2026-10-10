package app.circlet.analytics

import app.circlet.analytics.strain.dayLoad
import app.circlet.analytics.stress.dayStress
import app.circlet.analytics.workout.detectActivities
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.Sex
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A resting day with one 45-minute run at 150 bpm.
 * The profile is 30 years old, so Tanaka's HRmax is 187. Three prior quiet
 * days are enough for a strain number; seven would be a settled level.
 */
class SyntheticDayTest {
    @Test
    fun oneRunProducesActivityZonesStrainAndAStressTimeline() {
        val zone = ZoneId.of("UTC")
        val date = LocalDate.of(2026, 6, 1)
        val start = date.atTime(6, 0).atZone(zone).toEpochSecond()
        val runStart = date.atTime(8, 0).atZone(zone).toEpochSecond()
        val runEnd = runStart + 45 * 60
        val samples = ArrayList<Sample>(8 * 3600)
        for (i in 0 until 8 * 3600) {
            val t = start + i
            val running = t >= runStart && t < runEnd
            val restRr = if (i % 2 == 0) 680 else 732
            val runRr = if (i % 2 == 0) listOf(380, 420) else listOf(420, 380)
            samples.add(
                Sample(
                    epochSecond = t,
                    heartRateBpm = if (running) 150 else 85,
                    rrIntervalsMs = if (running) runRr else listOf(restRr),
                    gravity = null,
                    optical = null,
                    rawRecord = null,
                ),
            )
        }
        val now = Instant.ofEpochSecond(start + 8 * 3600 - 1)
        val profile = Profile(
            birthDate = LocalDate.of(1996, 6, 1),
            ageYears = null,
            sex = Sex.MALE,
            heightCm = 170.0,
            weightKg = 70.0,
            maxHeartRateBpm = 187,
            customZones = emptyList(),
        )
        val quiet = (85.0 - 60.0) / (187.0 - 60.0)
        val activities = detectActivities(
            samples, profile, zone, date, now, 60.0, quiet, false,
        )
        assertEquals(1, activities.size)
        val activity = activities.single()
        assertEquals(45, activity.zoneMinutes.total)
        assertEquals(activity.zoneMinutes.total, activity.zoneMinutes.minutes.sum())
        assertEquals(45, activity.zoneMinutes.minutes[4])

        val load = dayLoad(samples, profile, zone, date, now, 60.0, List(3) { quiet })
        val strain = load.strain.score.value
        assertTrue("strain was $strain", strain != null && strain in 6.0..14.0)

        val stress = dayStress(samples, zone, date, now)
        assertTrue(stress.timeline.size > 10)
        val span = Duration.between(stress.timeline.first().at, stress.timeline.last().at)
        assertTrue(span.toHours() >= 6)
    }
}
