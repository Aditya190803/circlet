package app.circlet.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreContractTest {
    @Test
    fun metricValueAndReasonAreExclusive() {
        val present = Metric.of(61, Confidence.HIGH)
        assertEquals(61, present.value)
        assertNull(present.absentReason)

        val missing = Metric.absent<Int>(AbsentReason.NOT_WORN)
        assertNull(missing.value)
        assertEquals(AbsentReason.NOT_WORN, missing.absentReason)
        assertEquals(Confidence.LOW, missing.confidence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun metricRejectsAValueThatAlsoHasAReason() {
        Metric(1, Confidence.HIGH, AbsentReason.NOT_WORN)
    }

    @Test
    fun signalBoundsMatchTheGen4Gate() {
        assertTrue(SignalBounds.offWrist(0))
        assertFalse(SignalBounds.plausibleBpm(0))
        assertTrue(SignalBounds.plausibleBpm(25))
        assertTrue(SignalBounds.plausibleBpm(230))
        assertFalse(SignalBounds.plausibleBpm(24))
        assertFalse(SignalBounds.plausibleBpm(231))
        assertTrue(SignalBounds.plausibleRr(200))
        assertTrue(SignalBounds.plausibleRr(2500))
        assertFalse(SignalBounds.plausibleRr(199))
        assertFalse(SignalBounds.plausibleRr(2501))
        assertTrue(SignalBounds.plausibleGravity(GravityVector(0f, 0f, 1f)))
        assertTrue(SignalBounds.plausibleGravity(GravityVector(0.5f, 0f, 0f)))
        assertFalse(SignalBounds.plausibleGravity(GravityVector(0f, 0f, 0f)))
        assertFalse(SignalBounds.plausibleGravity(GravityVector(0f, 0f, 2f)))

        val sample = Sample(
            epochSecond = 1_700_000_000,
            heartRateBpm = 0,
            rrIntervalsMs = listOf(800, 100),
            gravity = GravityVector(0f, 0f, 1f),
            optical = null,
            rawRecord = null,
        )
        assertNull(sample.measurementBpm())
        assertFalse(sample.rrIntervalsInRange())
        assertEquals(72, sample.copy(heartRateBpm = 72, rrIntervalsMs = listOf(800)).measurementBpm())
    }

    @Test
    fun ageUsesBirthDateWhenBothAreSet() {
        val profile = Profile(
            birthDate = LocalDate.of(2000, 1, 1),
            ageYears = 99,
            sex = Sex.UNSPECIFIED,
            heightCm = null,
            weightKg = null,
            maxHeartRateBpm = null,
            customZones = emptyList(),
        )
        assertEquals(26, profile.ageOn(LocalDate.of(2026, 10, 10)))
        assertEquals(25, profile.ageOn(LocalDate.of(2025, 12, 31)))
        assertNull(profile.copy(birthDate = LocalDate.of(2027, 1, 1)).ageOn(LocalDate.of(2026, 10, 10)))
        assertEquals(41, profile.copy(birthDate = null, ageYears = 41).ageOn(LocalDate.of(2026, 10, 10)))
    }

    @Test
    fun profileValidationAcceptsContiguousZonesAndRejectsTheRest() {
        val valid = baseProfile().copy(
            customZones = listOf(
                HeartRateZone(1, 90, 110),
                HeartRateZone(2, 110, 130),
                HeartRateZone(3, 130, 150),
                HeartRateZone(4, 150, 170),
                HeartRateZone(5, 170, 190),
            ),
        )
        assertTrue(valid.problems().isEmpty())
        assertTrue(valid.validate() is Outcome.Ok)

        val gap = valid.copy(customZones = valid.customZones.map { if (it.index == 3) it.copy(fromBpm = 132) else it })
        assertTrue(gap.problems().any { it.contains("contiguous") })

        val light = baseProfile().copy(weightKg = 10.0)
        val rejected = light.validate()
        assertTrue(rejected is Outcome.Err)
    }

    @Test
    fun zoneMinutesCoverSixZones() {
        val zones = ZoneMinutes.of(1, 2, 3, 4, 5, 6)
        assertEquals(21, zones.total)
        assertEquals(listOf(2, 4, 6, 8, 10, 12), zones.plus(zones).minutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zoneMinutesRejectAShortList() {
        ZoneMinutes(listOf(1, 2, 3))
    }

    @Test
    fun isoWeekStartsOnMonday() {
        val date = LocalDate.of(2026, 10, 10)
        val start = date.toIsoWeekStart()
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertFalse(start.isAfter(date))
        assertTrue(ChronoUnit.DAYS.between(start, date) in 0..6)
    }

    @Test
    fun emptyDayReadsAbsentForEveryKind() {
        val day = emptyDay(LocalDate.of(2026, 10, 10), AbsentReason.NOT_WORN)
        for (kind in MetricKind.values()) {
            val reading = day.reading(kind)
            assertNull("kind $kind", reading.value)
            val expected = if (kind == MetricKind.SKIN_TEMPERATURE) {
                AbsentReason.NOT_ENOUGH_DATA
            } else {
                AbsentReason.NOT_WORN
            }
            assertEquals("kind $kind", expected, reading.absentReason)
        }
    }

    @Test
    fun stressBandsSplitAtThirtyFourAndSixtySeven() {
        assertEquals(StressBand.LOW, stressBandOf(0.0))
        assertEquals(StressBand.LOW, stressBandOf(33.9))
        assertEquals(StressBand.MEDIUM, stressBandOf(34.0))
        assertEquals(StressBand.MEDIUM, stressBandOf(66.9))
        assertEquals(StressBand.HIGH, stressBandOf(67.0))
        assertEquals(StressBand.HIGH, stressBandOf(100.0))
        assertEquals(0, StressBandMinutes.ZERO.totalMinutes)
    }

    @Test
    fun nextAlarmSkipsToTheFollowingListedDay() {
        val zone = ZoneId.of("UTC")
        val alarm = StrapAlarm(
            id = "weekday",
            time = LocalTime.of(6, 30),
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            smartWake = Duration.ofMinutes(30),
            enabled = true,
        )
        val saturdayNoon = Instant.parse("2026-10-10T12:00:00Z")
        val next = alarm.nextFire(saturdayNoon, zone)
        assertEquals(Instant.parse("2026-10-12T06:30:00Z"), next)

        val mondayMorning = Instant.parse("2026-10-12T06:00:00Z")
        assertEquals(Instant.parse("2026-10-12T06:30:00Z"), alarm.nextFire(mondayMorning, zone))

        val mondayAfter = Instant.parse("2026-10-12T07:00:00Z")
        assertEquals(Instant.parse("2026-10-13T06:30:00Z"), alarm.nextFire(mondayAfter, zone))
        assertNull(alarm.copy(enabled = false).nextFire(saturdayNoon, zone))
    }

    @Test
    fun emptyDayCarriesTheReasonOnEveryMetric() {
        val day = emptyDay(LocalDate.of(2026, 10, 10), AbsentReason.NOT_ENOUGH_DATA, CalibrationStatus.READY)
        assertNull(day.sleep.value)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, day.sleep.absentReason)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, day.recovery.score.absentReason)
        assertEquals(CalibrationStatus.READY, day.recovery.calibration)
        assertTrue(day.naps.isEmpty())
        assertTrue(day.activities.isEmpty())
        assertTrue(day.heartRateByMinute.isEmpty())
        assertEquals(0, day.strain.zoneMinutes.total)
    }

    @Test
    fun manualClockAdvancesOnlyWhenAsked() {
        val clock = ManualTimeSource(Instant.parse("2026-10-10T15:00:00Z"), ZoneId.of("UTC"))
        assertEquals(Instant.parse("2026-10-10T15:00:00Z"), clock.now())
        clock.advance(Duration.ofMinutes(10))
        assertEquals(Instant.parse("2026-10-10T15:10:00Z"), clock.now())
    }

    private fun baseProfile() = Profile(
        birthDate = LocalDate.of(1994, 6, 15),
        ageYears = null,
        sex = Sex.FEMALE,
        heightCm = 168.0,
        weightKg = 64.0,
        maxHeartRateBpm = null,
        customZones = emptyList(),
    )
}
