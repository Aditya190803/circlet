package app.circlet.analytics.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** True Phillips SRI. Vectors from OpenStrap sleep_test.dart. */
class SriTest {
    @Test
    fun perfectlyRegularTwoDayVectorIs100() {
        val epochsPerDay = 1440
        val day = List(epochsPerDay) { e -> e < 7 * 60 || e >= 23 * 60 }
        val m = phillipsSri(day + day, epochsPerDay)
        assertTrue(m.present)
        assertEquals(100.0, m.value!!.sri, 1e-6)
    }

    @Test
    fun shiftedSecondDayIsBelow100() {
        val epochsPerDay = 1440
        val vec = day(0, epochsPerDay) + day(3 * 60, epochsPerDay)
        val m = phillipsSri(vec, epochsPerDay)
        assertTrue(m.present)
        assertTrue(m.value!!.sri < 100.0)
        // 3 h shift on an 8 h sleep: 6 h of 24 disagree → SRI = 200·(18/24)−100 = 50.
        assertEquals(50.0, m.value!!.sri, 1.0)
    }

    @Test
    fun perPairNamesTheBreakAndDropsAHalfUnobservedPair() {
        val epochsPerDay = 1440
        val vec = day(0, epochsPerDay) + day(0, epochsPerDay) + day(90, epochsPerDay) + day(3 * 60, epochsPerDay)
        val valid = List(epochsPerDay) { true } +
            List(epochsPerDay) { true } +
            List(epochsPerDay) { true } +
            List(epochsPerDay) { e -> e < 300 }
        val m = phillipsSri(vec, epochsPerDay, valid = valid)
        assertTrue(m.present)
        val pairs = m.value!!.pairs
        assertEquals(listOf(1, 2), pairs.map { it.dayIndex })
        assertEquals(100.0, pairs[0].sri, 1e-6)
        assertTrue(pairs[1].sri < pairs[0].sri)
        var agree = 0
        var cases = 0
        for (p in pairs) {
            agree += p.agreement
            cases += p.cases
        }
        assertTrue(agree <= cases)
        assertTrue(cases < m.value!!.cases)
    }

    private fun day(shift: Int, epochsPerDay: Int): List<Boolean> =
        List(epochsPerDay) { e ->
            var ee = (e - shift) % epochsPerDay
            if (ee < 0) ee += epochsPerDay
            ee < 7 * 60 || ee >= 23 * 60
        }
}
