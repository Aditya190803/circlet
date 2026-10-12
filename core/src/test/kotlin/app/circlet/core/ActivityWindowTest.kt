package app.circlet.core

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityWindowTest {
    private val now = Instant.parse("2026-06-15T12:00:00Z")

    @Test
    fun aWindowHasToEndAfterItStartsAndNotInTheFuture() {
        assertEquals("The activity has to end after it starts.", rejectedActivityWindow(now, now, now))
        assertEquals(
            "The activity has to end after it starts.",
            rejectedActivityWindow(now, now.minusSeconds(1), now),
        )
        assertEquals(
            "The activity cannot end in the future.",
            rejectedActivityWindow(now.minusSeconds(60), now.plusSeconds(1), now),
        )
        assertEquals(
            "An activity cannot be longer than 24 hours.",
            rejectedActivityWindow(now.minus(Duration.ofHours(24)).minusSeconds(1), now, now),
        )
        assertNull(rejectedActivityWindow(now.minus(Duration.ofHours(24)), now, now))
        assertNull(rejectedActivityWindow(now.minusSeconds(60), now, now))
        assertNull(rejectedActivityWindow(now.plusSeconds(60), now.plusSeconds(120), now.plusSeconds(120)))
    }
}
