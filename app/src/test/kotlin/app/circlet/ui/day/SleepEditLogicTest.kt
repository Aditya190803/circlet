package app.circlet.ui.day

import app.circlet.core.SleepEdit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepEditLogicTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 6, 15)

    @Test
    fun anAddedNightStartsAtElevenToSevenOnceMorningHasPassed() {
        val now = Instant.parse("2026-06-15T12:00:30Z")
        val (bed, wake) = suggestedSleepWindow(date, zone, now, null)
        assertEquals(Instant.parse("2026-06-14T23:00:00Z"), bed)
        assertEquals(Instant.parse("2026-06-15T07:00:00Z"), wake)
    }

    @Test
    fun anAddedNightIsPulledBackWhenSevenHasNotHappenedYet() {
        val now = Instant.parse("2026-06-15T05:10:45Z")
        val (bed, wake) = suggestedSleepWindow(date, zone, now, null)
        assertEquals(Instant.parse("2026-06-15T05:10:00Z"), wake)
        assertEquals(Instant.parse("2026-06-14T21:10:00Z"), bed)
    }

    @Test
    fun rejectingAndKeepingANapTogglesItsStart() {
        val nap = Instant.parse("2026-06-15T13:00:00Z")
        val rejected = null.withNap(nap, counted = false)
        assertEquals(setOf(nap), rejected.rejectedNaps)
        assertTrue(rejected.withNap(nap, counted = true).isEmpty)
    }

    @Test
    fun mainSleepEditsKeepTheNapChoices() {
        val nap = Instant.parse("2026-06-15T13:00:00Z")
        val base = SleepEdit(rejectedNaps = setOf(nap))
        val start = Instant.parse("2026-06-15T01:00:00Z")
        val end = Instant.parse("2026-06-15T08:00:00Z")
        val window = base.withWindow(start, end)
        assertEquals(start to end, window.window)
        assertEquals(setOf(nap), window.rejectedNaps)
        assertTrue(window.changesMain)
        val removed = window.withMainRemoved()
        assertTrue(removed.deleted)
        assertEquals(null, removed.window)
        val detected = removed.withDetectedMain()
        assertFalse(detected.changesMain)
        assertEquals(setOf(nap), detected.rejectedNaps)
        assertFalse(null.changesMain)
    }
}
