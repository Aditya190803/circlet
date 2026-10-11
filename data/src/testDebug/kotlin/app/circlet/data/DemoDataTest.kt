package app.circlet.data

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDataTest {
    private val zone = ZoneId.of("UTC")
    private val end = LocalDate.of(2026, 6, 21)

    @Test
    fun oneDayIsEmptyAndTheRestHaveNoon() {
        for (index in 0 until DemoData.DAY_COUNT) {
            val date = end.minusDays((DemoData.DAY_COUNT - 1 - index).toLong())
            val epoch = date.atTime(12, 0).atZone(zone).toEpochSecond()
            val sample = DemoData.sampleOrNull(1L, zone, end, epoch)
            if (index == DemoData.EMPTY_DAY_INDEX) assertNull(sample) else assertNotNull(sample)
        }
        val outside = end.plusDays(1).atTime(12, 0).atZone(zone).toEpochSecond()
        assertNull(DemoData.sampleOrNull(1L, zone, end, outside))
    }

    @Test
    fun patternsAreRealisticAndDeterministic() {
        val sleep = epoch(0, 3, 0)
        val first = DemoData.sampleOrNull(4L, zone, end, sleep)!!
        assertEquals(first, DemoData.sampleOrNull(4L, zone, end, sleep))
        assertTrue(first.heartRateBpm!! in 42..60)
        // Over a minute the beat intervals add up to about a minute, as a real heart's do.
        val minute = (0 until 60).mapNotNull { DemoData.sampleOrNull(4L, zone, end, sleep + it) }
        val beats = minute.sumOf { it.rrIntervalsMs.size }
        assertTrue("beats $beats", beats in 42..60)
        assertTrue(minute.sumOf { it.rrIntervalsMs.sum() } in 55_000..65_000)
        assertNotNull(first.gravity)
        assertNotNull(first.optical)

        val mismatches = (0 until 40).count { second ->
            val at = epoch(0, 3, second)
            DemoData.sampleOrNull(4L, zone, end, at) != DemoData.sampleOrNull(9L, zone, end, at)
        }
        assertTrue(mismatches > 0)

        assertEquals(0, DemoData.sampleOrNull(4L, zone, end, epoch(0, 7, 10))!!.heartRateBpm)
        assertNull(DemoData.sampleOrNull(4L, zone, end, epoch(0, 7, 10))!!.gravity)
        assertNull(DemoData.sampleOrNull(4L, zone, end, epoch(0, 15, 10)))
        assertTrue(DemoData.sampleOrNull(4L, zone, end, epoch(0, 8, 10))!!.heartRateBpm!! >= 145)
        assertTrue(DemoData.sampleOrNull(4L, zone, end, epoch(1, 17, 20))!!.heartRateBpm!! in 95..112)
        assertTrue(DemoData.sampleOrNull(4L, zone, end, epoch(1, 13, 40))!!.heartRateBpm!! in 50..62)
        val intervals = DemoData.sampleOrNull(4L, zone, end, epoch(2, 18, 45))!!.heartRateBpm
        assertTrue(intervals == 112 || intervals == 158)
        assertEquals(0, DemoData.sampleOrNull(4L, zone, end, epoch(4, 12, 0))!!.heartRateBpm)
    }

    private fun epoch(index: Int, hour: Int, minuteOrSecond: Int): Long {
        val date = end.minusDays((DemoData.DAY_COUNT - 1 - index).toLong())
        return if (minuteOrSecond < 60 && hour != 3) {
            date.atTime(hour, minuteOrSecond).atZone(zone).toEpochSecond()
        } else {
            date.atTime(hour, 0).plusSeconds(minuteOrSecond.toLong()).atZone(zone).toEpochSecond()
        }
    }
}
