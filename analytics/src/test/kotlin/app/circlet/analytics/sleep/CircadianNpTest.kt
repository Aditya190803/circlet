package app.circlet.analytics.sleep

import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nonparametric circadian IS/IV/RA/L5/M10. Vectors from OpenStrap sleep_test.dart. */
class CircadianNpTest {
    @Test
    fun cleanCosineOverSevenDaysIsStableWithHighAmplitude() {
        val eph = 24
        val x = ArrayList<Double>()
        for (d in 0 until 7) {
            for (h in 0 until eph) {
                x.add(1.0 + cos(2.0 * PI * (h - 14) / 24.0))
            }
        }
        val m = circadianNonparametric(x, eph)
        assertTrue(m.present)
        val c = m.value!!
        assertEquals(1.0, c.interdailyStability, 1e-6)
        assertTrue(c.intradailyVariability < 0.1)
        assertTrue(c.m10 > c.l5)
        assertTrue(c.relativeAmplitude > 0.3)
    }

    @Test
    fun flatActivityIsAbsent() {
        val m = circadianNonparametric(List(24 * 3) { 5.0 }, 24)
        assertFalse(m.present)
    }
}
