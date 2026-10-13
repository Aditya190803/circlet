package app.circlet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
