package app.circlet.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubTest {
    private val morningOnly: List<Int?> = List(1440) { minute -> if (minute < 600) 60 + minute % 7 else null }

    @Test
    fun aTouchOnDataReadsItsOwnBucket() {
        assertEquals(300 until 305, scrubBucket(morningOnly, 300f / 1439))
    }

    @Test
    fun scrubbingPastTheLastSyncedMinuteHoldsTheLastReading() {
        assertEquals(595 until 600, scrubBucket(morningOnly, 0.9f))
        assertEquals(595 until 600, scrubBucket(morningOnly, 1f))
    }

    @Test
    fun aGapReadsTheNearestBucketWithData() {
        val gap: List<Int?> = List(60) { minute -> if (minute < 10 || minute >= 40) 70 else null }
        assertEquals(5 until 10, scrubBucket(gap, 15f / 59))
        assertEquals(40 until 45, scrubBucket(gap, 35f / 59))
    }

    @Test
    fun aDayWithNoReadingsReadsNothing() {
        assertNull(scrubBucket(List(1440) { null }, 0.5f))
        assertNull(scrubBucket(emptyList(), 0.5f))
    }

    @Test
    fun anEmptyStressHourHoldsTheNearestHourWithAScore() {
        assertEquals(9, scrubHour(setOf(2, 9), 0.9f))
        assertEquals(2, scrubHour(setOf(2, 9), 0f))
        assertNull(scrubHour(emptySet(), 0.5f))
    }
}
