package app.circlet.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.CoreError
import app.circlet.core.DayEngine
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.InsightEngine
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.MetricKind
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.Sport
import app.circlet.core.TagCorrelation
import app.circlet.core.TrendPoint
import app.circlet.core.WeeklyRecap
import app.circlet.core.emptyDay
import app.circlet.data.db.openMemoryDatabase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkoutPauseTest {
    private val zone = ZoneId.of("UTC")
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pauseAndResumeKeepTheOpenPauseOutUntilItCloses() = runBlocking {
        val time = clock()
        val opened = open(time)
        try {
            val workouts = opened.data.workouts
            assertEquals("no workout is running", invalid(workouts.pause()))
            assertTrue(workouts.start(Sport.YOGA) is Outcome.Ok)
            assertEquals("workout is not paused", invalid(workouts.resume()))
            val pausedAt = time.now()
            assertTrue(workouts.pause() is Outcome.Ok)
            assertEquals("workout is already paused", invalid(workouts.pause()))
            time.advance(Duration.ofSeconds(15))
            val saved = opened.data.ports.workouts.currentLive()
            assertEquals(pausedAt, saved!!.workout.pausedAt)
            assertEquals(0L, saved.workout.pausedSeconds)
            assertTrue(saved.workout.pauses.isEmpty())
            assertTrue(workouts.resume() is Outcome.Ok)
            val running = opened.data.ports.workouts.currentLive()!!.workout
            assertNull(running.pausedAt)
            assertEquals(15L, running.pausedSeconds)
            assertEquals(pausedAt, running.pauses.single().start)
            assertEquals(time.now(), running.pauses.single().end)
        } finally {
            opened.close()
        }
    }

    @Test
    fun stopWhilePausedClosesThePauseAndKeepsWallClockTimes() = runBlocking {
        val time = clock()
        val opened = open(time)
        try {
            val workouts = opened.data.workouts
            val start = time.now()
            assertTrue(workouts.start(Sport.YOGA) is Outcome.Ok)
            assertTrue(workouts.pause() is Outcome.Ok)
            time.advance(Duration.ofSeconds(30))
            val activity = workouts.stop().valueOrFail()
            assertEquals(start, activity.start)
            assertEquals(time.now(), activity.end)
            assertEquals(30L, Duration.between(activity.start, activity.end).seconds)
            assertEquals(30L, activity.pausedSeconds)
            assertEquals(start, activity.pauses.single().start)
            assertEquals(time.now(), activity.pauses.single().end)
            assertNull(workouts.live.value)
            assertNull(opened.data.ports.workouts.currentLive())
            assertEquals("no workout is being recorded", invalid(workouts.stop()))
            val stored = opened.data.ports.workouts.get(activity.id)
            assertEquals(activity.pausedSeconds, stored!!.pausedSeconds)
            assertEquals(activity.pauses, stored.pauses)
        } finally {
            opened.close()
        }
    }

    @Test
    fun anEditedWindowIsRejectedOrHandedToTheEngine() = runBlocking {
        val time = clock()
        val opened = open(time)
        try {
            val workouts = opened.data.workouts
            assertTrue(workouts.start(Sport.YOGA) is Outcome.Ok)
            time.advance(Duration.ofMinutes(1))
            val activity = workouts.stop().valueOrFail()
            assertEquals("The activity has to end after it starts.", invalid(workouts.edit(activity.copy(end = activity.start))))
            assertEquals(
                "The activity cannot end in the future.",
                invalid(workouts.edit(activity.copy(end = time.now().plusSeconds(1)))),
            )
            val tooLongEnd = time.now()
            assertEquals(
                "An activity cannot be longer than 24 hours.",
                invalid(workouts.edit(activity.copy(start = tooLongEnd.minus(Duration.ofHours(24)).minusSeconds(1), end = tooLongEnd))),
            )
            assertEquals(activity.end, opened.data.ports.workouts.get(activity.id)!!.end)

            time.advance(Duration.ofHours(2))
            val start = activity.start.plus(Duration.ofMinutes(20))
            val end = start.plus(Duration.ofMinutes(25))
            assertTrue(workouts.edit(activity.copy(start = start, end = end)) is Outcome.Ok)
            opened.data.recomputeNow()
            val seen = opened.engine.manuals.single()
            assertEquals(activity.id, seen.id)
            assertEquals(start, seen.start)
            assertEquals(end, seen.end)
            val stored = opened.data.ports.workouts.get(activity.id)!!
            assertEquals(start, stored.start)
            assertEquals(end, stored.end)
        } finally {
            opened.close()
        }
    }

    private fun clock() = app.circlet.core.ManualTimeSource(Instant.parse("2026-06-15T12:00:00Z"), zone)

    private fun invalid(outcome: Outcome<*>): String = ((outcome as Outcome.Err).error as CoreError.Invalid).detail

    private fun <T> Outcome<T>.valueOrFail(): T {
        if (this is Outcome.Err) error(error.toString())
        return (this as Outcome.Ok).value
    }

    private suspend fun open(time: app.circlet.core.ManualTimeSource): Opened {
        val engine = RecordingEngine()
        val data = DataModule.open(
            context = context,
            dayEngine = engine,
            insightEngine = QuietInsights(),
            time = time,
            database = openMemoryDatabase(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.IO,
            debounceMs = 50,
            startPipeline = false,
        )
        data.awaitReady()
        return Opened(data, engine)
    }

    private class Opened(val data: CircletData, val engine: RecordingEngine) {
        fun close() = data.close()
    }

    private class RecordingEngine : DayEngine {
        override val algorithmVersion: Int = 1
        var manuals: List<Activity> = emptyList()

        override fun computeDay(input: DayInput): DaySummary {
            manuals = input.manualActivities
            return emptyDay(input.date, AbsentReason.NOT_ENOUGH_DATA).copy(activities = input.manualActivities)
        }
    }
}

internal class QuietInsights : InsightEngine {
    override fun trend(days: List<DaySummary>, metric: MetricKind): List<TrendPoint> = emptyList()

    override fun records(days: List<DaySummary>): List<PersonalRecord> = emptyList()

    override fun weeklyRecap(weekStart: LocalDate, days: List<DaySummary>): WeeklyRecap = WeeklyRecap(
        weekStart = weekStart,
        days = days,
        averageRecovery = app.circlet.core.Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageStrain = app.circlet.core.Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageSleepPerformance = app.circlet.core.Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageHrvMs = app.circlet.core.Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        sleepDebt = app.circlet.core.Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
    )

    override fun correlations(
        days: List<DaySummary>,
        tags: List<JournalTag>,
        entries: List<JournalEntry>,
    ): List<TagCorrelation> = emptyList()

    override fun recommendedBedtime(history: List<DaySummary>, zone: ZoneId, now: Instant): Instant? = null
}
