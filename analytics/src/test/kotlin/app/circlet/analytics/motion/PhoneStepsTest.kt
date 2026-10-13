package app.circlet.analytics.motion

import app.circlet.core.StepReading
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneStepsTest {
    private val date = LocalDate.of(2026, 10, 13)
    private val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun at(minute: Long, count: Long, secondMs: Long = 0) = StepReading(midnight + minute * 60_000 + secondMs, count)

    @Test
    fun theFirstReadingOnlySetsTheBaseline() {
        assertEquals(emptyMap<Long, Double>(), phoneStepsPerMinute(listOf(at(10, 5_000))))
    }

    @Test
    fun aRiseIsSpreadEvenlyOverTheMinutesBetweenReadings() {
        val minutes = phoneStepsPerMinute(listOf(at(10, 1_000), at(13, 1_400)))
        assertEquals(listOf(10L, 11L, 12L, 13L), minutes.keys.map { it - midnight / 60_000 })
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0), minutes.values.toList())
    }

    @Test
    fun aRiseInsideOneMinuteStaysInThatMinute() {
        val minutes = phoneStepsPerMinute(listOf(at(5, 100), at(5, 130, 40_000)))
        assertEquals(mapOf(midnight / 60_000 + 5 to 30.0), minutes)
    }

    @Test
    fun aGapOfAnHourIsSpreadButALongerOneLandsOnTheLaterMinute() {
        val hour = phoneStepsPerMinute(listOf(at(0, 0), at(60, 610)))
        assertEquals(61, hour.size)
        assertEquals(10.0, hour.values.first(), 1e-9)

        val longer = phoneStepsPerMinute(listOf(at(0, 0), at(61, 610)))
        assertEquals(mapOf(midnight / 60_000 + 61 to 610.0), longer)
    }

    @Test
    fun aCounterThatDropsRestartedFromZero() {
        val minutes = phoneStepsPerMinute(listOf(at(1, 9_000), at(2, 40)))
        assertEquals(40.0, minutes.values.sum(), 1e-9)
    }

    @Test
    fun readingsOutOfOrderAndRepeatedAreTidied() {
        val minutes = phoneStepsPerMinute(listOf(at(3, 130), at(1, 100), at(1, 100), at(3, 130)))
        assertEquals(30.0, minutes.values.sum(), 1e-9)
    }

    @Test
    fun theDayTotalCountsOnlyTheMinutesOfThatDate() {
        val before = StepReading(midnight - 60_000, 1_000)
        val total = phoneStepsOnDay(listOf(before, at(1, 1_300), at(24 * 60 - 1, 2_000)), ZoneOffset.UTC, date)
        // The first rise straddles midnight over the last minute of the day before and minutes 0 and 1.
        assertEquals(200 + 700, total)
    }

    @Test
    fun theDayUsesTheLocalZone() {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val readings = listOf(StepReading(start + 60_000, 10), StepReading(start + 5 * 60_000, 510))
        assertEquals(500, phoneStepsOnDay(readings, zone, date))
        assertNull(phoneStepsOnDay(readings, zone, date.plusDays(1)))
    }

    @Test
    fun noUsableReadingsGiveNoCount() {
        assertNull(phoneStepsOnDay(emptyList(), ZoneOffset.UTC, date))
        assertNull(phoneStepsOnDay(listOf(at(5, 100)), ZoneOffset.UTC, date))
        assertNull(phoneStepsOnDay(listOf(at(5, 100), at(6, 150)), ZoneOffset.UTC, date.plusDays(1)))
    }
}
