package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.Tier
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** sleepCyclesMetric direct coverage. Vectors from OpenStrap sleep_test.dart. */
class CyclesTest {
    @Test
    fun emptyRrIsAbsent() {
        val m = sleepCyclesMetric(emptyList(), emptyList(), 0, 8 * 3600)
        assertFalse(m.present)
        assertNull(m.value)
        assertEquals(Tier.ESTIMATE, m.tier)
    }

    @Test
    fun ultradianRmssdWaveDetectsPeakToPeakCycles() {
        val offset = 8 * 3600
        val rrMs = ArrayList<Double>(offset)
        val rrTsMs = ArrayList<Double>(offset)
        for (t in 0 until offset) {
            val amp = 1.0 + sin(2.0 * PI * t / (90.0 * 60.0))
            val wobble = (if (t % 2 == 0) 1.0 else -1.0) * (10.0 + 30.0 * amp)
            rrMs.add(900.0 + wobble)
            rrTsMs.add((t * 1000).toDouble())
        }
        val m = sleepCyclesMetric(rrMs, rrTsMs, 0, offset)
        assertTrue(m.present)
        assertEquals(Tier.ESTIMATE, m.tier)
        val r = m.value!!
        assertTrue(r.n >= 1)
        assertEquals(r.n, r.cycles.size)
        for (c in r.cycles) {
            assertTrue(c.lenMin > 0)
            assertTrue(c.endMin > c.startMin)
        }
        assertTrue(r.series.isNotEmpty())
        assertEquals(r.n, r.toJson()["cycle_count"])
    }
}
