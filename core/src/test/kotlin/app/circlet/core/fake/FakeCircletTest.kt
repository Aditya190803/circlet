package app.circlet.core.fake

import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.CoreError
import app.circlet.core.LinkState
import app.circlet.core.ManualTimeSource
import app.circlet.core.Outcome
import app.circlet.core.SleepStage
import app.circlet.core.MetricKind
import app.circlet.core.Sport
import app.circlet.core.TrendRange
import app.circlet.core.WellnessKind
import app.circlet.core.toIsoWeekStart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCircletTest {
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-10T15:00:00Z")

    @Test
    fun pairedDaysAreInternallyConsistent() = runBlocking {
        val clock = ManualTimeSource(start, zone)
        val fake = FakeCirclet(clock, FakeScenario.PAIRED)
        val today = LocalDate.of(2026, 10, 10)
        val days = fake.observeRange(today.minusDays(13), today).first()
        assertEquals(14, days.size)
        assertEquals(today.minusDays(13), days.first().date)
        assertEquals(today, days.last().date)
        val records = fake.records()
        assertTrue(records is Outcome.Ok)
        assertTrue((records as Outcome.Ok).value.isNotEmpty())
        val trend = fake.trend(MetricKind.RECOVERY, TrendRange.WEEK)
        assertTrue(trend is Outcome.Ok)
        assertEquals(7, (trend as Outcome.Ok).value.size)
        val recap = fake.weeklyRecap(today.toIsoWeekStart())
        assertTrue(recap is Outcome.Ok)
        assertEquals(7, (recap as Outcome.Ok).value.days.size)

        for (day in days) {
            assertEquals(1440, day.heartRateByMinute.size)
            val present = day.heartRateByMinute.filterNotNull()
            assertEquals(present.size, day.strain.zoneMinutes.total)
            assertEquals(present.average().roundToInt(), day.heart.averageBpm.value)
            val strain = day.strain.score.value
            assertNotNull(strain)
            assertTrue(strain!! in 0.0..21.0)
            val session = day.sleep.value
            assertNotNull(session)
            session!!
            var cursor = session.start
            for (epoch in session.epochs) {
                assertEquals(cursor, epoch.start)
                cursor = cursor.plus(epoch.duration)
            }
            assertEquals(session.end, cursor)
            val asleep = session.epochs.filter { it.stage != SleepStage.AWAKE }.sumOf { it.duration.toMinutes() }
            val inBed = session.epochs.sumOf { it.duration.toMinutes() }
            assertEquals(asleep, session.timeAsleep.toMinutes())
            assertEquals(inBed, session.timeInBed.toMinutes())
            assertEquals((asleep * 100.0 / inBed).roundToInt(), session.efficiency.value)
            val need = day.sleepNeed.duration.value
            assertNotNull(need)
            assertEquals((asleep * 100.0 / need!!.toMinutes()).roundToInt(), day.sleepPerformance.percent.value)
            assertEquals(CalibrationStatus.READY, day.recovery.calibration)
            assertNotNull(day.recovery.score.value)
            assertTrue(day.recovery.score.value!! in 1..99)
            assertEquals(4, day.recovery.drivers.size)
            assertTrue(day.recovery.drivers.all { it.contribution != null })
            for (activity in day.activities) {
                assertTrue((activity.strain.value ?: 0.0) <= strain + 0.001)
                assertTrue(activity.zoneMinutes.total <= day.strain.zoneMinutes.total)
                activity.zoneMinutes.minutes.forEachIndexed { index, minutes ->
                    assertTrue(minutes <= day.strain.zoneMinutes.minutes[index])
                }
            }
            val flagged = day.wellness.any { it.kind == WellnessKind.OVERREACHING }
            val recovery = checkNotNull(day.recovery.score.value)
            if (flagged) assertTrue(recovery < 40 && strain > 14.0)
            if (recovery < 40 && strain > 14.0) assertTrue(flagged)
        }
        assertTrue(days.any { it.wellness.isNotEmpty() })
        assertEquals(LinkState.CONNECTED, fake.state.value.link)
        assertTrue(fake.checks.value.strapPaired)
        assertTrue(fake.completed.value)
    }

    @Test
    fun unpairedCalibratingAndEmptyAreDistinct() = runBlocking {
        val today = LocalDate.of(2026, 10, 10)
        val unpaired = FakeCirclet(ManualTimeSource(start, zone), FakeScenario.UNPAIRED)
        assertEquals(LinkState.UNPAIRED, unpaired.state.value.link)
        val bare = unpaired.observeDay(today).first()
        assertEquals(AbsentReason.STRAP_NOT_SYNCED, bare.sleep.absentReason)
        assertTrue(unpaired.syncNow() is Outcome.Err)

        val calibrating = FakeCirclet(ManualTimeSource(start, zone), FakeScenario.CALIBRATING)
        val night = calibrating.observeDay(today.minusDays(1)).first()
        assertNotNull(night.sleep.value)
        assertEquals(AbsentReason.CALIBRATING, night.recovery.score.absentReason)
        assertEquals(CalibrationStatus.CALIBRATING, night.recovery.calibration)
        assertEquals(AbsentReason.CALIBRATING, night.targetStrain.score.absentReason)

        val empty = FakeCirclet(ManualTimeSource(start, zone), FakeScenario.NO_DATA_TODAY)
        val missing = empty.observeDay(today).first()
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, missing.strain.score.absentReason)
        assertTrue(missing.heartRateByMinute.isEmpty())
        assertNotNull(empty.observeDay(today.minusDays(1)).first().sleep.value)
        assertEquals(LinkState.CONNECTED, empty.state.value.link)
    }

    @Test
    fun workoutJournalAndBackupMutateTheDemo() = runBlocking {
        val clock = ManualTimeSource(start, zone)
        val fake = FakeCirclet(clock, FakeScenario.PAIRED)
        assertTrue(fake.start(Sport.RUNNING) is Outcome.Ok)
        assertTrue(fake.start(Sport.CYCLING) is Outcome.Err)
        assertTrue(fake.stop() is Outcome.Err)
        clock.advance(Duration.ofMinutes(12))
        val stopped = fake.stop()
        assertTrue(stopped is Outcome.Ok)
        val activity = (stopped as Outcome.Ok).value
        val today = fake.observeDay(LocalDate.of(2026, 10, 10)).first()
        assertTrue(today.activities.any { it.id == activity.id })

        val firstTwo = today.activities.take(2).map { it.id }
        if (firstTwo.size == 2) {
            val merged = fake.merge(firstTwo)
            assertTrue(merged is Outcome.Ok)
        }

        assertTrue(fake.deleteTag("caffeine") is Outcome.Err)
        val saved = fake.saveTag(app.circlet.core.JournalTag("sauna", "Sauna", builtIn = false))
        assertTrue(saved is Outcome.Ok)
        assertTrue(fake.deleteTag("sauna") is Outcome.Ok)

        val backup = fake.updateBackup(
            app.circlet.core.BackupSettings(
                enabled = true,
                interval = Duration.ofHours(24),
                folderUri = "content://app.circlet/backup",
                lastBackup = null,
            ),
        )
        assertTrue(backup is Outcome.Ok)
        assertTrue(
            fake.updateBackup(
                app.circlet.core.BackupSettings(true, Duration.ofHours(24), null, null),
            ) is Outcome.Err,
        )

        val correlations = fake.correlations()
        assertTrue(correlations is Outcome.Ok)
        assertTrue((correlations as Outcome.Ok).value.any { it.effect.value != null })
        assertNull(fake.live.value)
    }
}
