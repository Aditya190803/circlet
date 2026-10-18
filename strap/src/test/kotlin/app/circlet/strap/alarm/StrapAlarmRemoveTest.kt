package app.circlet.strap.alarm

import app.circlet.core.DoubleTapAction
import app.circlet.core.DoubleTapPolicy
import app.circlet.core.ManualTimeSource
import app.circlet.core.Outcome
import app.circlet.core.SleepStageEstimate
import app.circlet.core.StrapAlarm
import app.circlet.strap.support.MemoryPrefs
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrapAlarmRemoveTest {
    private val zone = ZoneOffset.UTC
    private val monday = Instant.parse("2026-10-12T07:00:00Z")

    private fun alarms(prefs: MemoryPrefs = MemoryPrefs()) = StrapAlarms(
        prefs = prefs,
        time = ManualTimeSource(monday, zone),
        sleepStage = SleepStageEstimate.UNKNOWN,
        doubleTap = DoubleTapPolicy { DoubleTapAction.MARK_MOMENT },
        radio = object : AlarmRadio {
            val armed = mutableListOf<Instant?>()
            override suspend fun arm(at: Instant): Outcome<Unit> {
                armed += at
                return Outcome.Ok(Unit)
            }
            override suspend fun disarm(): Outcome<Unit> {
                armed += null
                return Outcome.Ok(Unit)
            }
            override suspend fun buzz(): Outcome<Unit> = Outcome.Ok(Unit)
            override suspend fun stop(): Outcome<Unit> = Outcome.Ok(Unit)
            override fun connected(): Boolean = true
        },
    )

    private fun alarm(id: String, time: LocalTime) = StrapAlarm(
        id = id,
        time = time,
        weekdays = DayOfWeek.values().toSet(),
        smartWake = Duration.ZERO,
        enabled = true,
    )

    @Test
    fun removeDeletesAndRearmsTheSoonest() = runBlocking {
        val prefs = MemoryPrefs()
        val controller = alarms(prefs)
        assertTrue(controller.set(alarm("early", LocalTime.of(6, 30))) is Outcome.Ok)
        assertTrue(controller.set(alarm("late", LocalTime.of(9, 0))) is Outcome.Ok)
        assertEquals(2, controller.alarms.value.size)

        assertTrue(controller.remove("missing") is Outcome.Err)
        assertTrue(controller.remove("early") is Outcome.Ok)

        assertEquals(listOf("late"), controller.alarms.value.map { it.id })
        assertEquals(listOf("late"), prefs.stored.map { it.id })
        assertEquals(Instant.parse("2026-10-12T09:00:00Z"), controller.next.value!!.at)
    }

    @Test
    fun failedDeliveryIsReportedAndRetriedOnTheNextTick() = runBlocking {
        var failed = true
        var writes = 0
        val radio = object : AlarmRadio {
            override suspend fun arm(at: Instant): Outcome<Unit> {
                writes++
                return if (failed) Outcome.Err(app.circlet.core.CoreError.Strap(app.circlet.core.StrapError.TimedOut)) else Outcome.Ok(Unit)
            }
            override suspend fun disarm(): Outcome<Unit> = arm(monday)
            override suspend fun buzz(): Outcome<Unit> = Outcome.Ok(Unit)
            override suspend fun stop(): Outcome<Unit> = Outcome.Ok(Unit)
            override fun connected(): Boolean = true
        }
        val prefs = MemoryPrefs()
        val controller = StrapAlarms(prefs, ManualTimeSource(monday, zone), SleepStageEstimate.UNKNOWN,
            DoubleTapPolicy { DoubleTapAction.MARK_MOMENT }, radio)
        assertTrue(controller.set(alarm("wake", LocalTime.of(9, 0))) is Outcome.Err)
        assertEquals(1, prefs.stored.size)
        failed = false
        controller.tick()
        assertEquals(2, writes)
        controller.tick()
        assertEquals(2, writes)
        failed = true
        assertTrue(controller.disable("wake") is Outcome.Err)
        failed = false
        controller.tick()
        assertEquals(4, writes)
    }
}
