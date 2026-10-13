package app.circlet.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepEditTest {
    private val now = Instant.parse("2026-06-15T12:00:00Z")

    @Test
    fun aSleepWindowNeedsAWakeAfterBedNotInTheFutureAndUnderADay() {
        val bed = now.minus(Duration.ofHours(8))
        assertEquals("Wake time has to be after bed time.", rejectedSleepWindow(bed, bed, now))
        assertEquals("Wake time cannot be in the future.", rejectedSleepWindow(bed, now.plusSeconds(1), now))
        assertEquals(
            "Sleep has to be shorter than 24 hours.",
            rejectedSleepWindow(now.minus(Duration.ofHours(24)), now, now),
        )
        assertNull(rejectedSleepWindow(now.minus(Duration.ofHours(24)).plusSeconds(1), now, now))
        assertNull(rejectedSleepWindow(bed, now, now))
    }

    @Test
    fun anEditIsEmptyOnlyWhenItChangesNothing() {
        val bed = now.minusSeconds(3600)
        assertTrue(SleepEdit().isEmpty)
        assertFalse(SleepEdit(deleted = true).isEmpty)
        assertFalse(SleepEdit(rejectedNaps = setOf(bed)).isEmpty)
        assertEquals(bed to now, SleepEdit(start = bed, end = now).window)
        assertNull(SleepEdit(start = bed).window)
        assertNull(SleepEdit(start = bed, end = now, deleted = true).window)
    }
}
