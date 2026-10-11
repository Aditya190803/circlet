package app.circlet.analytics.stress

import app.circlet.core.StressPoint
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DayStressBandsTest {
    private val start = Instant.parse("2026-03-11T08:00:00Z")

    private fun point(minute: Long, score: Double) =
        StressPoint(start.plus(Duration.ofMinutes(minute)), score)

    @Test
    fun emptyTimelineCountsNothing() {
        assertEquals(0, stressBandMinutes(emptyList()).totalMinutes)
    }

    @Test
    fun eachPointCountsUntilTheNext() {
        val timeline = listOf(
            point(0, 10.0),
            point(5, 50.0),
            point(10, 80.0),
            point(15, 80.0),
        )
        val minutes = stressBandMinutes(timeline)
        assertEquals(5, minutes.lowMinutes)
        assertEquals(5, minutes.mediumMinutes)
        assertEquals(5, minutes.highMinutes)
        assertEquals(15, minutes.totalMinutes)
    }

    @Test
    fun aLongPauseCountsAtMostTenMinutes() {
        val timeline = listOf(point(0, 80.0), point(60, 10.0), point(61, 10.0))
        val minutes = stressBandMinutes(timeline)
        assertEquals(10, minutes.highMinutes)
        assertEquals(1, minutes.lowMinutes)
        assertEquals(0, minutes.mediumMinutes)
    }

    @Test
    fun cutOffsMatchTheBands() {
        val timeline = listOf(point(0, 33.9), point(1, 34.0), point(2, 66.9), point(3, 67.0), point(4, 67.0))
        val minutes = stressBandMinutes(timeline)
        assertEquals(1, minutes.lowMinutes)
        assertEquals(2, minutes.mediumMinutes)
        assertEquals(1, minutes.highMinutes)
    }
}
