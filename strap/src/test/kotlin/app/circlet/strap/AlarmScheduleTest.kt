package app.circlet.strap

import app.circlet.core.SleepStage
import app.circlet.core.StrapAlarm
import app.circlet.strap.alarm.AlarmScheduler
import app.circlet.strap.alarm.AlarmTick
import app.circlet.strap.alarm.SmartWake
import app.circlet.strap.alarm.decideTick
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmScheduleTest {
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val wake = LocalTime.of(7, 30)

    @Test
    fun nextFireSkipsDaysThatAreNotSelected() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val wednesday = onOrAfter(monday.plusDays(1), DayOfWeek.WEDNESDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val before = monday.atTime(7, 0).atZone(auckland).toInstant()
        val first = AlarmScheduler.next(listOf(alarm), before, auckland)
        assertEquals(monday.atTime(wake).atZone(auckland).toInstant(), first?.at)
        assertEquals(DayOfWeek.MONDAY, first!!.at.atZone(auckland).dayOfWeek)

        val afterMonday = monday.atTime(8, 0).atZone(auckland).toInstant()
        val second = AlarmScheduler.next(listOf(alarm), afterMonday, auckland)
        assertEquals(wednesday.atTime(wake).atZone(auckland).toInstant(), second?.at)
        assertEquals(DayOfWeek.WEDNESDAY, second!!.at.atZone(auckland).dayOfWeek)
    }

    @Test
    fun theSameInstantIsADifferentLocalMorningAcrossZones() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY))
        val after = monday.atTime(7, 0).atZone(auckland).toInstant()
        val inAuckland = alarm.nextFire(after, auckland)
        val inLosAngeles = alarm.nextFire(after, losAngeles)
        assertEquals(monday.atTime(wake).atZone(auckland).toInstant(), inAuckland)
        assertEquals(monday.atTime(wake).atZone(losAngeles).toInstant(), inLosAngeles)
        assertNotEquals(inAuckland, inLosAngeles)
        assertEquals(DayOfWeek.MONDAY, inAuckland!!.atZone(auckland).dayOfWeek)
        assertEquals(DayOfWeek.MONDAY, inLosAngeles!!.atZone(losAngeles).dayOfWeek)
    }

    @Test
    fun disabledAndEmptySchedulesDoNotFire() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val after = monday.atStartOfDay(auckland).toInstant()
        assertNull(alarm(setOf(DayOfWeek.MONDAY)).copy(enabled = false).nextFire(after, auckland))
        assertNull(alarm(emptySet()).nextFire(after, auckland))
        assertNull(AlarmScheduler.next(emptyList(), after, auckland))
    }

    @Test
    fun theSoonerOfTwoAlarmsWins() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val early = alarm(setOf(DayOfWeek.MONDAY)).copy(id = "early", time = LocalTime.of(6, 0))
        val late = alarm(setOf(DayOfWeek.MONDAY)).copy(id = "late", time = LocalTime.of(8, 0))
        val after = monday.atStartOfDay(auckland).toInstant()
        assertEquals("early", AlarmScheduler.next(listOf(late, early), after, auckland)?.alarm?.id)
    }

    @Test
    fun smartWakeFiresOnlyInsideTheWindowOnLightSleep() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY)).copy(smartWake = Duration.ofMinutes(30))
        val scheduled = monday.atTime(wake).atZone(auckland).toInstant()
        val open = scheduled.minus(Duration.ofMinutes(30))
        assertTrue(SmartWake.shouldFire(alarm, open, auckland, SleepStage.LIGHT, connected = true))
        assertTrue(
            SmartWake.shouldFire(
                alarm,
                scheduled.minusMillis(1),
                auckland,
                SleepStage.LIGHT,
                connected = true,
            ),
        )
        assertFalse(SmartWake.shouldFire(alarm, scheduled, auckland, SleepStage.LIGHT, connected = true))
        assertFalse(SmartWake.shouldFire(alarm, open.minusMillis(1), auckland, SleepStage.LIGHT, connected = true))
        assertFalse(SmartWake.shouldFire(alarm, open, auckland, SleepStage.DEEP, connected = true))
        assertFalse(SmartWake.shouldFire(alarm, open, auckland, SleepStage.LIGHT, connected = false))
        assertFalse(SmartWake.shouldFire(alarm, open, auckland, null, connected = true))
        assertFalse(
            SmartWake.shouldFire(
                alarm.copy(smartWake = Duration.ZERO),
                open,
                auckland,
                SleepStage.LIGHT,
                connected = true,
            ),
        )
    }

    @Test
    fun anEarlyBuzzArmsTheFollowingOccurrence() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY)).copy(smartWake = Duration.ofMinutes(30))
        val scheduled = monday.atTime(wake).atZone(auckland).toInstant()
        val tick = decideTick(
            alarms = listOf(alarm),
            armedFor = null,
            now = scheduled.minus(Duration.ofMinutes(10)),
            zone = auckland,
            stage = SleepStage.LIGHT,
            connected = true,
            alreadyBuzzed = { _, _ -> false },
        )
        assertTrue(tick is AlarmTick.FireEarly)
        val early = tick as AlarmTick.FireEarly
        assertEquals(scheduled, early.occurrence)
        assertEquals(scheduled.atZone(auckland).plusWeeks(1).toInstant(), early.then)
    }

    @Test
    fun aBuzzAlreadySentDoesNotFireAgain() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY)).copy(smartWake = Duration.ofMinutes(30))
        val scheduled = monday.atTime(wake).atZone(auckland).toInstant()
        val tick = decideTick(
            alarms = listOf(alarm),
            armedFor = null,
            now = scheduled.minus(Duration.ofMinutes(10)),
            zone = auckland,
            stage = SleepStage.LIGHT,
            connected = true,
            alreadyBuzzed = { _, _ -> true },
        )
        assertEquals(AlarmTick.None, tick)
    }

    @Test
    fun aReachedOccurrenceIsRearmedAndADisabledListIsDisarmed() {
        val monday = onOrAfter(LocalDate.of(2026, 10, 1), DayOfWeek.MONDAY)
        val alarm = alarm(setOf(DayOfWeek.MONDAY))
        val scheduled = monday.atTime(wake).atZone(auckland).toInstant()
        val following = scheduled.atZone(auckland).plusWeeks(1).toInstant()
        val rearm = decideTick(
            alarms = listOf(alarm),
            armedFor = scheduled,
            now = scheduled,
            zone = auckland,
            stage = null,
            connected = true,
            alreadyBuzzed = { _, _ -> false },
        )
        assertEquals(AlarmTick.Rearm(following), rearm)
        val disarm = decideTick(
            alarms = listOf(alarm.copy(enabled = false)),
            armedFor = scheduled,
            now = scheduled,
            zone = auckland,
            stage = null,
            connected = false,
            alreadyBuzzed = { _, _ -> false },
        )
        assertEquals(AlarmTick.Disarm, disarm)
    }

    private fun alarm(days: Set<DayOfWeek>) = StrapAlarm(
        id = "wake",
        time = wake,
        weekdays = days,
        smartWake = Duration.ZERO,
        enabled = true,
    )

    private fun onOrAfter(start: LocalDate, day: DayOfWeek): LocalDate {
        var date = start
        while (date.dayOfWeek != day) date = date.plusDays(1)
        return date
    }
}
