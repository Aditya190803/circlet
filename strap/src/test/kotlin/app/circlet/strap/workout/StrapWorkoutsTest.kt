package app.circlet.strap.workout

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.LiveSession
import app.circlet.core.LiveStrainEstimator
import app.circlet.core.LiveLoad
import app.circlet.core.ManualTimeSource
import app.circlet.core.Metric
import app.circlet.core.Outcome
import app.circlet.core.Sport
import app.circlet.core.WorkoutStore
import app.circlet.core.ZoneMinutes
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrapWorkoutsTest {
    @Test
    fun aPausedWorkoutKeepsTheHeartRateOnScreenAndOutOfTheTotals() = runBlocking {
        val time = clock()
        val heart = Heart()
        val store = MemoryWorkouts()
        val workouts = StrapWorkouts(store, time, CountLoad(), heart)
        assertTrue(workouts.start(Sport.RUNNING) is Outcome.Ok)
        heart.beat(100)
        assertEquals(100, workouts.live.value!!.averageHeartRateBpm)
        assertEquals(1.0, workouts.live.value!!.strain, 0.0)
        assertTrue(workouts.pause() is Outcome.Ok)
        heart.beat(180)
        assertEquals(180, workouts.live.value!!.heartRateBpm)
        assertEquals(100, workouts.live.value!!.averageHeartRateBpm)
        assertEquals(100, workouts.live.value!!.maxHeartRateBpm)
        assertEquals(1.0, workouts.live.value!!.strain, 0.0)
        time.advance(Duration.ofSeconds(10))
        assertTrue(workouts.resume() is Outcome.Ok)
        heart.beat(120)
        assertEquals(110, workouts.live.value!!.averageHeartRateBpm)
        assertEquals(120, workouts.live.value!!.maxHeartRateBpm)
        assertEquals(2.0, workouts.live.value!!.strain, 0.0)
        assertEquals(10L, workouts.live.value!!.pausedSeconds)
    }

    @Test
    fun stopWhilePausedStoresTheSpanAndDropsThePausedBeat() = runBlocking {
        val time = clock()
        val heart = Heart()
        val store = MemoryWorkouts()
        val workouts = StrapWorkouts(store, time, CountLoad(), heart)
        val start = time.now()
        assertTrue(workouts.start(Sport.RUNNING) is Outcome.Ok)
        heart.beat(100)
        assertTrue(workouts.pause() is Outcome.Ok)
        time.advance(Duration.ofSeconds(30))
        heart.beat(180)
        val activity = workouts.stop().valueOrFail()
        assertEquals(start, activity.start)
        assertEquals(time.now(), activity.end)
        assertEquals(30L, Duration.between(activity.start, activity.end).seconds)
        assertEquals(30L, activity.pausedSeconds)
        assertEquals(start, activity.pauses.single().start)
        assertEquals(time.now(), activity.pauses.single().end)
        assertEquals(100, activity.averageHeartRateBpm.value)
        assertEquals(100, activity.maxHeartRateBpm.value)
        assertNull(workouts.live.value)
        assertNull(store.currentLive())
    }

    @Test
    fun aRestoredPauseStillIgnoresHeartRate() = runBlocking {
        val time = clock()
        val store = MemoryWorkouts()
        val firstHeart = Heart()
        val first = StrapWorkouts(store, time, CountLoad(), firstHeart)
        assertTrue(first.start(Sport.YOGA) is Outcome.Ok)
        firstHeart.beat(90)
        assertTrue(first.pause() is Outcome.Ok)
        val saved = store.currentLive()
        assertEquals(listOf(90), saved!!.beats)
        assertTrue(saved.workout.paused)

        val secondHeart = Heart()
        val second = StrapWorkouts(store, time, CountLoad(), secondHeart)
        second.restore()
        assertTrue(second.live.value!!.paused)
        assertEquals(90, second.live.value!!.averageHeartRateBpm)
        secondHeart.beat(150)
        assertEquals(150, second.live.value!!.heartRateBpm)
        assertEquals(90, second.live.value!!.averageHeartRateBpm)
        assertEquals(1.0, second.live.value!!.strain, 0.0)
        assertEquals("workout is already paused", ((second.pause() as Outcome.Err).error as CoreError.Invalid).detail)
    }

    private fun clock() = ManualTimeSource(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("UTC"))

    private fun <T> Outcome<T>.valueOrFail(): T {
        if (this is Outcome.Err) error(error.toString())
        return (this as Outcome.Ok).value
    }

    private class Heart : HeartDemand {
        private var listener: ((Int?) -> Unit)? = null
        override suspend fun setWorkout(on: Boolean): Outcome<Unit> = Outcome.Ok(Unit)
        override fun listen(listener: (Int?) -> Unit) {
            this.listener = listener
        }
        fun beat(bpm: Int?) = listener?.invoke(bpm)
    }

    private class CountLoad : LiveStrainEstimator {
        override fun load(elapsed: Duration, heartRatesBpm: List<Int>): LiveLoad = LiveLoad(
            strain = Metric.of(heartRatesBpm.size.toDouble(), Confidence.LOW),
            calories = Metric.of(heartRatesBpm.size, Confidence.LOW),
            zoneMinutes = ZoneMinutes.ZERO,
        )
    }

    private class MemoryWorkouts : WorkoutStore {
        private val activities = ArrayList<Activity>()
        private var live: LiveSession? = null
        override suspend fun insert(activity: Activity) {
            activities += activity
        }
        override suspend fun update(activity: Activity) = Unit
        override suspend fun delete(id: String) {
            activities.removeAll { it.id == id }
        }
        override suspend fun get(id: String): Activity? = activities.find { it.id == id }
        override suspend fun saveLive(session: LiveSession?) {
            live = session
        }
        override suspend fun currentLive(): LiveSession? = live
    }
}
