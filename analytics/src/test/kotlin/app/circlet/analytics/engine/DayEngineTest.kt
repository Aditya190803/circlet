package app.circlet.analytics.engine

import app.circlet.core.AbsentReason
import app.circlet.core.ActivitySource
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.GravityVector
import app.circlet.core.Profile
import app.circlet.core.Recovery
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.Sample
import app.circlet.core.Sex
import app.circlet.core.StepReading
import app.circlet.core.SkinTempFlag
import app.circlet.core.SleepEdit
import app.circlet.core.SleepPerformance
import app.circlet.core.Sport
import app.circlet.core.Activity
import app.circlet.core.Metric
import app.circlet.core.WorkoutPause
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic 1 Hz day: still night, moving wake, one 45 minute run.
 * RR is written on the second a beat ends so coverage stays near one.
 */
class DayEngineTest {
    private val engine = CircletDayEngine()
    private val zone: ZoneId = ZoneId.of("UTC")
    private val date: LocalDate = LocalDate.of(2026, 3, 11)

    @Test
    fun oneDayFillsWhatTheGatesAllow() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val summary = engine.computeDay(input(date, daySamples(date, 0), now, emptyList()))

        val sleep = summary.sleep.value
        assertNotNull("sleep ${summary.sleep.absentReason}", sleep)
        sleep!!
        assertTrue(sleep.start < date.atStartOfDay(zone).toInstant())
        assertTrue(sleep.end > date.atStartOfDay(zone).toInstant())
        assertTrue(sleep.timeAsleep.seconds in 5 * 3600..9 * 3600)
        assertTrue(summary.naps.isEmpty())
        // No history yet: the consensus adult need stands in, at low confidence.
        assertEquals(java.time.Duration.ofHours(8), summary.sleepNeed.duration.value)
        assertEquals(app.circlet.core.Confidence.LOW, summary.sleepNeed.duration.confidence)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, summary.sleepDebt.shortfall.absentReason)
        assertTrue(summary.sleepPerformance.percent.value!! in 60..100)
        assertEquals(app.circlet.core.Confidence.LOW, summary.sleepPerformance.percent.confidence)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, summary.sleepConsistency.index.absentReason)

        val resting = summary.heart.resting.bpm.value
        assertNotNull("rhr ${summary.heart.resting.bpm.absentReason}", resting)
        assertTrue("rhr $resting", resting!! in 40..65)
        assertNotNull("hrv ${summary.heart.hrv.rmssdMs.absentReason}", summary.heart.hrv.rmssdMs.value)
        assertNotNull(summary.heart.hrv.sdnnMs.value)
        assertNotNull("resp ${summary.heart.respiratoryRate.breathsPerMinute.absentReason}", summary.heart.respiratoryRate.breathsPerMinute.value)
        assertNotNull(summary.heart.averageBpm.value)
        assertTrue(summary.heart.maxBpm.value!! >= 140)

        // A provisional score from the night's own readings, still marked as calibrating.
        assertTrue(summary.recovery.score.value!! in 1..99)
        assertEquals(app.circlet.core.Confidence.LOW, summary.recovery.score.confidence)
        assertEquals(CalibrationStatus.CALIBRATING, summary.recovery.calibration)
        assertEquals(4, summary.recovery.drivers.size)
        assertTrue(summary.strain.score.value!! in 0.0..21.0)
        assertEquals(app.circlet.core.Confidence.LOW, summary.strain.score.confidence)
        assertTrue(summary.strain.zoneMinutes.total > 0)
        assertNotNull("calories ${summary.calories.absentReason}", summary.calories.value)
        assertNotNull("stress ${summary.stress.score.absentReason}", summary.stress.score.value)
        assertTrue(summary.stress.timeline.isNotEmpty())

        // Strap motion never makes a step count; only the phone's counter does.
        assertNull(summary.steps.count.value)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, summary.steps.count.absentReason)
        // No paced run to measure from, so the heart-rate-ratio estimate stands in at low confidence.
        assertTrue(summary.vo2Max.mlPerKgMin.value!! in 30.0..80.0)
        assertEquals(app.circlet.core.Confidence.LOW, summary.vo2Max.mlPerKgMin.confidence)
        assertTrue(summary.wellness.isEmpty())

        assertEquals(1440, summary.heartRateByMinute.size)
        assertNotNull(summary.heartRateByMinute[0])
        val runMinute = 16 * 60
        assertTrue(summary.heartRateByMinute[runMinute]!! >= 140)
        assertTrue(summary.heartRateByMinute[3 * 60]!! < 80)

        val run = summary.activities.single { it.source == ActivitySource.AUTO }
        assertEquals(Sport.OTHER, run.sport)
        val runSeconds = java.time.Duration.between(run.start, run.end).seconds
        assertTrue("run length $runSeconds", runSeconds in 44 * 60..46 * 60)
        // No prior days yet, so the run is scored against today's own quiet waking level.
        assertTrue("run strain ${run.strain.value}", run.strain.value!! in 4.0..21.0)
        assertTrue(run.averageHeartRateBpm.value!! >= 140)
    }

    @Test
    fun anEditedWindowReplacesTheDetectedOneAndRescoresFromIt() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val samples = daySamples(date, 0)
        val detected = engine.computeDay(input(date, samples, now, emptyList()))
        val start = date.minusDays(1).atTime(23, 30).atZone(zone).toInstant()
        val end = date.atTime(5, 30).atZone(zone).toInstant()
        val edited = engine.computeDay(
            input(date, samples, now, emptyList()).copy(sleepEdit = SleepEdit(start = start, end = end)),
        )

        val session = edited.sleep.value
        assertNotNull("sleep ${edited.sleep.absentReason}", session)
        assertEquals(start, session!!.start)
        assertEquals(end, session.end)
        assertTrue(session.timeAsleep < detected.sleep.value!!.timeAsleep)
        assertNotNull(edited.heart.resting.bpm.value)
        assertNotNull(edited.heart.hrv.rmssdMs.value)
        assertNotNull(edited.sleepPerformance.percent.value)
    }

    @Test
    fun aDeletedNightHasNoSleepAndNoSleepDerivedMetrics() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val samples = daySamples(date, 0)
        val deleted = engine.computeDay(
            input(date, samples, now, emptyList()).copy(sleepEdit = SleepEdit(deleted = true)),
        )
        val noSleep = engine.computeDay(input(date, samples.filter { it.epochSecond >= date.atTime(7, 0).atZone(zone).toEpochSecond() }, now, emptyList()))

        assertNull(deleted.sleep.value)
        assertEquals(AbsentReason.NO_SLEEP, deleted.sleep.absentReason)
        assertEquals(noSleep.sleep.absentReason, deleted.sleep.absentReason)
        assertNull(deleted.sleepPerformance.percent.value)
        assertNull(deleted.skinTemperature?.settledMeanCounts)
    }

    @Test
    fun anEmptyEditAndAnUnmatchedRejectionScoreTheDayLikeNoEdit() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val samples = daySamples(date, 0)
        val plain = engine.computeDay(input(date, samples, now, emptyList()))
        val empty = engine.computeDay(input(date, samples, now, emptyList()).copy(sleepEdit = SleepEdit()))
        val unmatched = engine.computeDay(
            input(date, samples, now, emptyList()).copy(sleepEdit = SleepEdit(rejectedNaps = setOf(Instant.ofEpochSecond(5)))),
        )

        assertEquals(plain, empty)
        assertEquals(plain, unmatched)
    }

    @Test
    fun manualActivityKeepsItsIdentity() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val start = date.atTime(12, 0).atZone(zone).toInstant()
        val manual = Activity(
            id = "user-1",
            source = ActivitySource.MANUAL,
            sport = Sport.CYCLING,
            sportConfidence = null,
            start = start,
            end = start.plusSeconds(30 * 60),
            strain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            averageHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            maxHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            zoneMinutes = ZoneMinutes.ZERO,
            heartRateRecovery = null,
        )
        val summary = engine.computeDay(
            input(date, daySamples(date, 0, includeRun = false), now, emptyList(), manual = listOf(manual)),
        )
        val kept = summary.activities.single { it.id == "user-1" }
        assertEquals(ActivitySource.MANUAL, kept.source)
        assertEquals(Sport.CYCLING, kept.sport)
        assertNull(kept.sportConfidence)
        assertEquals(start, kept.start)
        assertNotNull(kept.averageHeartRateBpm.value)
    }

    @Test
    fun pausedSamplesAreLeftOutOfTheActivity() {
        val start = date.atTime(12, 0).atZone(zone).toInstant()
        val pauseStart = start.plusSeconds(30)
        val pauseEnd = start.plusSeconds(90)
        val end = start.plusSeconds(120)
        val samples = (0 until 120).map { second ->
            val bpm = if (second in 30 until 90) 180 else 100
            sample(start.epochSecond + second, bpm, emptyList(), moving = true)
        }
        val manual = logged("paused", start, end, listOf(WorkoutPause(pauseStart, pauseEnd)))
        val now = end.plusSeconds(30)
        val summary = engine.computeDay(input(date, samples, now, emptyList(), manual = listOf(manual)))
        val kept = summary.activities.single { it.id == "paused" }
        assertEquals(100, kept.averageHeartRateBpm.value)
        assertEquals(100, kept.maxHeartRateBpm.value)
        assertEquals(60L, kept.pausedSeconds)
        assertEquals(start, kept.start)
        assertEquals(end, kept.end)
    }

    @Test
    fun anEditedWindowIsScoredFromTheSamplesInsideIt() {
        val start = date.atTime(12, 0).atZone(zone).toInstant()
        val earlyEnd = start.plusSeconds(59)
        val lateStart = start.plusSeconds(60)
        val end = start.plusSeconds(119)
        val samples = (0 until 120).map { second ->
            sample(start.epochSecond + second, if (second < 60) 90 else 150, emptyList(), moving = true)
        }
        val now = end.plusSeconds(30)
        val summary = engine.computeDay(
            input(
                date,
                samples,
                now,
                emptyList(),
                manual = listOf(logged("early", start, earlyEnd), logged("late", lateStart, end)),
            ),
        )
        assertEquals(90, summary.activities.single { it.id == "early" }.averageHeartRateBpm.value)
        assertEquals(150, summary.activities.single { it.id == "late" }.averageHeartRateBpm.value)
        assertEquals(150, summary.activities.single { it.id == "late" }.maxHeartRateBpm.value)
    }

    @Test
    fun fourteenImportedRecoveryNightsLeaveTheNextDayReady() {
        val history = (14 downTo 1).map { ago ->
            importedNight(date.minusDays(ago.toLong()), 50 + ago, 40.0 + ago, 48 + (ago % 5), 14.0 + ago * 0.1, 70 + ago)
        }
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(10, engine.algorithmVersion)
        val summary = engine.computeDay(input(date, daySamples(date, 0, includeRun = false), now, history))
        assertEquals(CalibrationStatus.READY, summary.recovery.calibration)
        assertNotNull(summary.recovery.score.value)
        val short = engine.computeDay(input(date, emptyList(), now, history.take(13)))
        assertEquals(CalibrationStatus.CALIBRATING, short.recovery.calibration)
    }

    @Test
    fun phoneReadingsAreTheOnlySourceOfSteps() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val readings = listOf(StepReading(noon, 4_000), StepReading(noon + 10 * 60_000, 4_900))
        val summary = engine.computeDay(input(date, emptyList(), now, emptyList(), phoneSteps = readings))
        assertEquals(900, summary.steps.count.value)
        assertEquals(app.circlet.core.Confidence.MEDIUM, summary.steps.count.confidence)
        val other = engine.computeDay(input(date.plusDays(1), emptyList(), now, emptyList(), phoneSteps = readings))
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, other.steps.count.absentReason)
    }

    @Test
    fun emptyInputStaysAbsent() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val summary = engine.computeDay(input(date, emptyList(), now, emptyList()))
        assertEquals(AbsentReason.NOT_WORN, summary.sleep.absentReason)
        assertEquals(AbsentReason.NOT_WORN, summary.heart.resting.bpm.absentReason)
        assertEquals(AbsentReason.NOT_WORN, summary.strain.score.absentReason)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, summary.steps.count.absentReason)
        assertTrue(summary.activities.isEmpty())
        assertEquals(1440, summary.heartRateByMinute.size)
        assertTrue(summary.heartRateByMinute.all { it == null })
        assertEquals(CalibrationStatus.CALIBRATING, summary.recovery.calibration)
    }

    @Test
    fun minuteIndexFollowsTheZone() {
        val instant = date.atTime(15, 0).atZone(ZoneOffset.UTC).toInstant()
        val samples = (0 until 60).map { second ->
            sample(instant.epochSecond + second, 100, emptyList(), moving = true)
        }
        val now = instant.plusSeconds(120)
        val utc = engine.computeDay(input(date, samples, now, emptyList(), ZoneOffset.UTC))
        val west = engine.computeDay(input(date, samples, now, emptyList(), ZoneOffset.ofHours(-4)))
        assertEquals(100, utc.heartRateByMinute[15 * 60])
        assertEquals(100, west.heartRateByMinute[11 * 60])
        assertNull(west.heartRateByMinute[15 * 60])
    }

    @Test
    fun engineFillsReferencesSkinTemperatureAndStressBands() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val summary = engine.computeDay(input(date, daySamples(date, 0), now, emptyList()))
        assertEquals(10, summary.references.size)
        // The synthetic samples carry no skin-temp counts, so tonight has no
        // mean and the flag honestly reports the missing baseline.
        assertEquals(SkinTempFlag.NOT_ENOUGH_BASELINE, summary.skinTemperature!!.flag)
        assertTrue(summary.stress.bandMinutes.totalMinutes > 0)
    }

    @Test(timeout = 180_000)
    fun recoveryAppearsOnceTheBaselineExists() {
        val history = ArrayList<DaySummary>()
        var firstScore: Int? = null
        var readyAt: Int? = null
        var strainAt: Int? = null
        for (index in 0 until 32) {
            val day = date.plusDays(index.toLong())
            val now = day.plusDays(1).atStartOfDay(zone).toInstant()
            val summary = engine.computeDay(input(day, daySamples(day, index), now, history.toList()))
            if (index < 14) {
                // Before the personal baseline exists the score is provisional: present, low confidence, still calibrating.
                assertNotNull("recovery on day $index", summary.recovery.score.value)
                assertEquals(app.circlet.core.Confidence.LOW, summary.recovery.score.confidence)
                assertEquals(CalibrationStatus.CALIBRATING, summary.recovery.calibration)
            }
            if (summary.strain.score.value != null && strainAt == null) strainAt = index
            if (summary.recovery.score.value != null && firstScore == null) firstScore = index
            if (summary.recovery.calibration == CalibrationStatus.READY && readyAt == null) readyAt = index
            history += summary
        }
        assertEquals("strain shows from the first day", 0, strainAt)
        assertEquals("recovery shows from the first day", 0, firstScore)
        assertNotNull("calibration never became ready", readyAt)
        assertTrue(readyAt!! >= 14)
    }

    @Test(timeout = 15_000)
    fun oneDayStaysUnderAFewSeconds() {
        val now = date.plusDays(1).atStartOfDay(zone).toInstant()
        val samples = daySamples(date, 0)
        val started = System.nanoTime()
        engine.computeDay(input(date, samples, now, emptyList()))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("compute took ${millis}ms", millis < 2_500)
    }

    @Test
    fun insightEngineIsTheExistingOne() {
        val insight = circletInsightEngine()
        assertTrue(insight.trend(emptyList(), app.circlet.core.MetricKind.RECOVERY).isEmpty())
    }

    private fun input(
        day: LocalDate,
        samples: List<Sample>,
        now: Instant,
        history: List<DaySummary>,
        zoneId: ZoneId = zone,
        manual: List<Activity> = emptyList(),
        phoneSteps: List<StepReading> = emptyList(),
    ): DayInput = DayInput(
        date = day,
        zone = zoneId,
        samples = samples,
        profile = profile(),
        history = history,
        manualActivities = manual,
        now = now,
        phoneSteps = phoneSteps,
    )

    private fun profile(): Profile = Profile(
        birthDate = LocalDate.of(1990, 1, 1),
        ageYears = null,
        sex = Sex.MALE,
        heightCm = 180.0,
        weightKg = 78.0,
        maxHeartRateBpm = null,
        customZones = emptyList(),
    )

    private fun daySamples(day: LocalDate, dayIndex: Int, includeRun: Boolean = true): List<Sample> {
        val origin = day.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val end = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val sleepStart = day.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond()
        val sleepEnd = day.atTime(6, 30).atZone(zone).toEpochSecond()
        val runStart = day.atTime(16, 0).atZone(zone).toEpochSecond()
        val runEnd = runStart + 45 * 60
        val beats = HashMap<Long, MutableList<Int>>()
        val amplitude = 20.0 + (dayIndex % 5) * 8.0
        emit(beats, sleepStart, sleepEnd) { i ->
            (1_200.0 + amplitude * sin(2.0 * PI * i / 4.0)).toInt().coerceIn(400, 1_600)
        }
        emit(beats, origin, sleepStart) { i ->
            (900.0 + 40.0 * sin(2.0 * PI * i / 5.0)).toInt()
        }
        val afterSleep = if (includeRun) runStart else end
        emit(beats, sleepEnd, afterSleep) { i ->
            (880.0 + 50.0 * sin(2.0 * PI * i / 6.0)).toInt()
        }
        if (includeRun) {
            emit(beats, runStart, runEnd) { 390 }
            emit(beats, runEnd, end) { i ->
                (880.0 + 50.0 * sin(2.0 * PI * i / 6.0)).toInt()
            }
        }
        val sleepHr = 46 + (dayIndex % 7)
        val samples = ArrayList<Sample>((end - origin).toInt())
        var cursor = origin
        while (cursor < end) {
            val asleep = cursor in sleepStart until sleepEnd
            val running = includeRun && cursor in runStart until runEnd
            val hr = when {
                asleep -> sleepHr + (2.0 * sin(2.0 * PI * (cursor - sleepStart) / 1_800.0)).toInt()
                running -> 155
                else -> 64 + (dayIndex % 3)
            }
            samples += sample(cursor, hr, beats[cursor].orEmpty(), moving = !asleep)
            cursor++
        }
        return samples
    }

    private fun emit(beats: HashMap<Long, MutableList<Int>>, from: Long, until: Long, rrAt: (Int) -> Int) {
        var millis = from * 1000.0
        val end = until * 1000.0
        var i = 0
        while (millis < end) {
            val rr = rrAt(i)
            millis += rr
            val second = (millis / 1000.0).toLong()
            if (second in from until until) beats.getOrPut(second) { ArrayList() }.add(rr)
            i++
        }
    }

    private fun logged(
        id: String,
        start: Instant,
        end: Instant,
        pauses: List<WorkoutPause> = emptyList(),
    ) = Activity(
        id = id,
        source = ActivitySource.MANUAL,
        sport = Sport.YOGA,
        sportConfidence = null,
        start = start,
        end = end,
        strain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        maxHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        zoneMinutes = ZoneMinutes.ZERO,
        heartRateRecovery = null,
        pausedSeconds = pauses.sumOf { java.time.Duration.between(it.start, it.end).seconds.coerceAtLeast(0) },
        pauses = pauses,
    )

    private fun importedNight(
        day: LocalDate,
        recovery: Int,
        hrv: Double,
        resting: Int,
        respiratory: Double,
        sleep: Int,
    ): DaySummary {
        val base = emptyDay(day, AbsentReason.NOT_ENOUGH_DATA, CalibrationStatus.READY)
        return base.copy(
            recovery = Recovery(Metric.of(recovery, Confidence.MEDIUM), CalibrationStatus.READY, base.recovery.drivers),
            heart = base.heart.copy(
                resting = RestingHeartRate(Metric.of(resting, Confidence.MEDIUM)),
                hrv = base.heart.hrv.copy(rmssdMs = Metric.of(hrv, Confidence.MEDIUM)),
                respiratoryRate = RespiratoryRate(Metric.of(respiratory, Confidence.MEDIUM)),
            ),
            sleepPerformance = SleepPerformance(Metric.of(sleep, Confidence.MEDIUM)),
        )
    }

    private fun sample(epoch: Long, hr: Int, rr: List<Int>, moving: Boolean): Sample {
        val gravity = if (!moving) {
            GravityVector(0.02f, -0.04f, 0.98f)
        } else if (epoch % 2L == 0L) {
            GravityVector(0.35f, 0.2f, 0.9f)
        } else {
            GravityVector(0.35f, -0.2f, -0.9f)
        }
        return Sample(epoch, hr, rr, gravity, optical = null, rawRecord = null)
    }
}
