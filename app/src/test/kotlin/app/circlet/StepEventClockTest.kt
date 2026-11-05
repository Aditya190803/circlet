package app.circlet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepEventClockTest {
    @Test
    fun mapsBatchedEventsUsingOneClockAnchor() {
        val clock = StepEventClock(1_000_000, 10_000_000_000)
        assertEquals(995_000L, clock.epochMs(5_000_000_000))
        assertEquals(1_001_000L, clock.epochMs(11_000_000_000))
    }

    @Test
    fun subMillisecondEventsCannotReverseTheirWallTimes() {
        val clock = StepEventClock(1_000_000, 10_000_500_000)
        val timestamps = listOf(9_999_499_999L, 9_999_500_000L, 10_000_499_999L, 10_000_500_000L)
        val mapped = timestamps.map(clock::epochMs)
        assertTrue(mapped.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(listOf(999_998L, 999_999L, 999_999L, 1_000_000L), mapped)
    }
}
