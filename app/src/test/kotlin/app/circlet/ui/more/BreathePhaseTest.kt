package app.circlet.ui.more

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreathePhaseTest {
    @Test
    fun `six breaths a minute is a ten second breath`() {
        val start = phaseAt(0, 6.0)
        assertTrue(start.inhaling)
        assertEquals(0f, start.fill, 0.001f)
        val midInhale = phaseAt(2_500, 6.0)
        assertTrue(midInhale.inhaling)
        assertEquals(0.5f, midInhale.fill, 0.001f)
    }

    @Test
    fun `the switch to exhale happens at half a breath with lungs full`() {
        val top = phaseAt(5_000, 6.0)
        assertFalse(top.inhaling)
        assertEquals(1f, top.fill, 0.001f)
        assertEquals(0.5f, phaseAt(7_500, 6.0).fill, 0.001f)
    }

    @Test
    fun `the next breath starts empty and is counted`() {
        val next = phaseAt(10_000, 6.0)
        assertTrue(next.inhaling)
        assertEquals(0f, next.fill, 0.001f)
        assertEquals(1, next.index)
        assertEquals(0, phaseAt(9_999, 6.0).index)
    }

    @Test
    fun `slower pace stretches the breath`() {
        assertTrue(phaseAt(6_000, 4.5).inhaling)
        assertFalse(phaseAt(6_000, 7.0).inhaling)
    }

    @Test
    fun `negative time is the start`() {
        assertEquals(phaseAt(0, 5.5), phaseAt(-40, 5.5))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pace of zero is refused`() {
        phaseAt(0, 0.0)
    }
}
