package app.circlet.ui.design

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepWindowsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val wake = LocalDate.of(2026, 10, 24)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(date.atTime(hour, minute), zone).toInstant()

    @Test
    fun anEveningBedtimeAndTheNextMorningWakeShareOneRisingScale() {
        assertEquals(11 * 60L + 12, minutesFromNoonBefore(wake, at(wake.minusDays(1), 23, 12), zone))
        assertEquals(18 * 60L + 55, minutesFromNoonBefore(wake, at(wake, 6, 55), zone))
    }

    @Test
    fun aBedtimeAfterMidnightStillFallsAfterTheEveningOnes() {
        assertEquals(13 * 60L + 30, minutesFromNoonBefore(wake, at(wake, 1, 30), zone))
    }
}
