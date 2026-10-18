package app.circlet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeGateTest {
    @Test
    fun passesOnceThenOnlyAfterTheInterval() {
        val gate = NudgeGate(300_000)
        assertTrue(gate.tryPass(0))
        assertFalse(gate.tryPass(299_999))
        assertTrue(gate.tryPass(300_000))
        assertFalse(gate.tryPass(400_000))
    }

    @Test
    fun throttledReadingsHaveABoundedTrailingRefresh() {
        val gate = NudgeGate(300_000)
        assertEquals(0L, gate.remainingMs(0))
        assertTrue(gate.tryPass(0))
        assertEquals(240_000L, gate.remainingMs(60_000))
        assertEquals(1L, gate.remainingMs(299_999))
        assertEquals(0L, gate.remainingMs(300_000))
        assertTrue(gate.tryPass(300_000))
        assertEquals(300_000L, gate.remainingMs(300_000))
    }
}
