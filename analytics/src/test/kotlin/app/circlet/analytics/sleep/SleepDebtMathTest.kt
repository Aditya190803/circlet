package app.circlet.analytics.sleep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** sleepDebt only. Vectors from OpenStrap human_test.dart. */
class SleepDebtMathTest {
    @Test
    fun noFreeNightLeavesOsdNull() {
        val m = sleepDebt(listOf(6.0, 6.2, 5.9, 6.1), emptyList())
        assertTrue(m.present)
        assertNull(m.value!!.osdHours)
        assertNull(m.value!!.debtHours)
        assertFalse(m.value!!.hasFreeNight)
    }

    @Test
    fun freeNightReboundIsPositiveDebt() {
        val m = sleepDebt(listOf(6.0, 6.1, 5.9, 6.0, 6.2), listOf(8.4, 8.6, 8.5))
        assertTrue(m.value!!.hasFreeNight)
        assertTrue(m.value!!.debtHours!! > 1.5)
    }
}
