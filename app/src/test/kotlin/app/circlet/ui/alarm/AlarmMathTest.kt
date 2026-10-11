package app.circlet.ui.alarm

import app.circlet.core.StrapAlarm
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmMathTest {
    private val utc = ZoneId.of("UTC")
    private val english = Locale.ENGLISH

    private fun alarm(
        id: String,
        time: LocalTime,
        days: Set<DayOfWeek> = emptySet(),
        enabled: Boolean = true,
    ) = StrapAlarm(id, time, days, Duration.ZERO, enabled)

    @Test
    fun weekOrderStartsOnMondayAndHasSevenDays() {
        assertEquals(DayOfWeek.MONDAY, WEEK_ORDER.first())
        assertEquals(7, WEEK_ORDER.size)
        assertEquals(DayOfWeek.SUNDAY, WEEK_ORDER.last())
    }

    @Test
    fun shortDayIsUppercaseThreeLetters() {
        assertEquals("MON", shortDay(DayOfWeek.MONDAY, english))
        assertEquals("SAT", shortDay(DayOfWeek.SATURDAY, english))
    }

    @Test
    fun describeDaysNamesTheCommonSets() {
        assertEquals("NO DAYS", describeDays(emptySet(), english))
        assertEquals("EVERY DAY", describeDays(WEEK_ORDER.toSet(), english))
        assertEquals("WEEKDAYS", describeDays(WEEK_ORDER.take(5).toSet(), english))
        assertEquals("WEEKENDS", describeDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), english))
    }

    @Test
    fun describeDaysListsOtherSetsInWeekOrder() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertEquals("MON, WED, FRI", describeDays(days, english))
    }

    @Test
    fun formatAlarmTimeIn24HourMode() {
        assertEquals("06:30", formatAlarmTime(LocalTime.of(6, 30), twentyFourHour = true, locale = english))
        assertEquals("23:05", formatAlarmTime(LocalTime.of(23, 5), twentyFourHour = true, locale = english))
    }

    @Test
    fun formatAlarmTimeIn12HourMode() {
        assertEquals("6:30 AM", formatAlarmTime(LocalTime.of(6, 30), twentyFourHour = false, locale = english))
        assertEquals("12:00 PM", formatAlarmTime(LocalTime.of(12, 0), twentyFourHour = false, locale = english))
        assertEquals("12:15 AM", formatAlarmTime(LocalTime.of(0, 15), twentyFourHour = false, locale = english))
    }

    @Test
    fun displayHourMapsTo12HourClock() {
        assertEquals(12, displayHour(0, twentyFourHour = false))
        assertEquals(1, displayHour(1, twentyFourHour = false))
        assertEquals(12, displayHour(12, twentyFourHour = false))
        assertEquals(1, displayHour(13, twentyFourHour = false))
        assertEquals(23, displayHour(23, twentyFourHour = true))
    }

    @Test
    fun withPeriodKeepsHourInItsHalfOfTheDay() {
        assertEquals(7, withPeriod(7, pm = false))
        assertEquals(19, withPeriod(7, pm = true))
        assertEquals(0, withPeriod(0, pm = false))
        assertEquals(12, withPeriod(0, pm = true))
    }

    @Test
    fun stepHourWrapsPastMidnight() {
        assertEquals(23, stepHour(0, -1))
        assertEquals(0, stepHour(23, 1))
        assertEquals(9, stepHour(8, 1))
    }

    @Test
    fun stepMinuteMovesByFiveAndWraps() {
        assertEquals(5, stepMinute(0, 1))
        assertEquals(55, stepMinute(0, -1))
        assertEquals(0, stepMinute(55, 1))
        assertEquals(30, stepMinute(25, 1))
    }

    @Test
    fun smartWakeOptionMapsWindowsToNearestMark() {
        assertEquals(0, smartWakeOption(Duration.ZERO))
        assertEquals(0, smartWakeOption(Duration.ofMinutes(-5)))
        assertEquals(1, smartWakeOption(Duration.ofMinutes(15)))
        assertEquals(1, smartWakeOption(Duration.ofMinutes(20)))
        assertEquals(2, smartWakeOption(Duration.ofMinutes(30)))
        assertEquals(2, smartWakeOption(Duration.ofMinutes(45)))
    }

    @Test
    fun smartWakeCaptionIsEmptyWhenOff() {
        assertEquals("", smartWakeCaption(Duration.ZERO))
        assertEquals("SMART WAKE 15 MIN", smartWakeCaption(Duration.ofMinutes(15)))
        assertEquals("SMART WAKE 30 MIN", smartWakeCaption(Duration.ofMinutes(30)))
    }

    @Test
    fun smartWakeOptionsHaveOffFifteenAndThirtyMinutes() {
        assertEquals(
            listOf(Duration.ZERO, Duration.ofMinutes(15), Duration.ofMinutes(30)),
            SMART_WAKE_OPTIONS,
        )
    }

    @Test
    fun earliestNextPicksSoonestEnabledAlarm() {
        // 2026-10-12 is a Monday. Both alarms repeat on weekdays.
        val after = Instant.parse("2026-10-11T12:00:00Z") // Sunday noon
        val early = alarm("early", LocalTime.of(6, 0), WEEK_ORDER.take(5).toSet())
        val late = alarm("late", LocalTime.of(9, 0), WEEK_ORDER.take(5).toSet())

        val next = earliestNext(listOf(late, early), after, utc)

        assertTrue(next != null)
        assertEquals("early", next!!.alarm.id)
        assertEquals(Instant.parse("2026-10-12T06:00:00Z"), next.at)
    }

    @Test
    fun earliestNextIsNullWithNoAlarms() {
        val after = Instant.parse("2026-10-11T12:00:00Z")
        assertNull(earliestNext(emptyList(), after, utc))
    }

    @Test
    fun newAlarmIsWeekdaysAtSevenSwitchedOn() {
        val alarm = newAlarm()
        assertEquals(LocalTime.of(7, 0), alarm.time)
        assertEquals(WEEK_ORDER.take(5).toSet(), alarm.weekdays)
        assertEquals(Duration.ZERO, alarm.smartWake)
        assertTrue(alarm.enabled)
        assertFalse(alarm.id.isEmpty())
    }
}
