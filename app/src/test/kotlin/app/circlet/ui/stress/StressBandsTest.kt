package app.circlet.ui.stress

import app.circlet.core.StressPoint
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StressBandsTest {
    private val start = Instant.parse("2026-10-11T08:00:00Z")

    private fun at(minutes: Long, score: Double) = StressPoint(start.plus(Duration.ofMinutes(minutes)), score)

    @Test
    fun bandsFollowThePaletteCutOffs() {
        assertEquals(StressBand.LOW, bandOf(0.0))
        assertEquals(StressBand.LOW, bandOf(33.9))
        assertEquals(StressBand.MEDIUM, bandOf(34.0))
        assertEquals(StressBand.MEDIUM, bandOf(66.9))
        assertEquals(StressBand.HIGH, bandOf(67.0))
        assertEquals(StressBand.HIGH, bandOf(100.0))
    }

    @Test
    fun minutesCountEachSampleUntilTheNext() {
        val points = listOf(at(0, 10.0), at(5, 50.0), at(10, 80.0), at(20, 80.0))

        val minutes = bandMinutes(points)

        // 0-5 low, 5-10 medium, 10-20 high. The last sample has no end, so it counts nothing.
        assertEquals(BandMinutes(low = 5, medium = 5, high = 10), minutes)
    }

    @Test
    fun longGapsAreCappedSoAPauseIsNotCountedAsTime() {
        val points = listOf(at(0, 10.0), at(60, 10.0))

        assertEquals(10, bandMinutes(points).low)
    }

    @Test
    fun outOfOrderSamplesAreIgnored() {
        val points = listOf(at(10, 80.0), at(5, 10.0), at(15, 10.0))

        // The 10 to 5 step is skipped. The 5 to 15 step counts as low, capped at 10 minutes.
        assertEquals(BandMinutes(low = 10, medium = 0, high = 0), bandMinutes(points))
    }

    @Test
    fun emptyTimelineGivesNoMinutes() {
        assertEquals(BandMinutes(0, 0, 0), bandMinutes(emptyList()))
        assertEquals(0, bandMinutes(emptyList()).total)
    }

    @Test
    fun dominantBandIsTheOneWithMostMinutes() {
        assertEquals(StressBand.LOW, BandMinutes(low = 4, medium = 2, high = 0).dominant())
        assertEquals(StressBand.HIGH, BandMinutes(low = 1, medium = 2, high = 5).dominant())
        assertNull(BandMinutes(0, 0, 0).dominant())
    }

    @Test
    fun ofReturnsTheMinutesForABand() {
        val minutes = BandMinutes(low = 3, medium = 7, high = 11)

        assertEquals(3, minutes.of(StressBand.LOW))
        assertEquals(7, minutes.of(StressBand.MEDIUM))
        assertEquals(11, minutes.of(StressBand.HIGH))
        assertEquals(21, minutes.total)
    }
}
