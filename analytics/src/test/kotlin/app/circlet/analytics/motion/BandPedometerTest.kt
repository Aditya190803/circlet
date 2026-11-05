package app.circlet.analytics.motion

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandPedometerTest {
    /** Gravity plus a clean gait wave: one peak per step at [stepsPerMinute]. */
    private fun walk(seconds: Int, stepsPerMinute: Double, amplitudeG: Double = 0.3): DoubleArray {
        val n = (seconds * SAMPLE_RATE_HZ).toInt()
        val hz = stepsPerMinute / 60.0
        return DoubleArray(n) { i -> 1.0 + amplitudeG * sin(2 * PI * hz * i / SAMPLE_RATE_HZ) }
    }

    @Test
    fun cleanWalkingCountsEveryStepOnceTheGateOpens() {
        val steps = bandPedometer(walk(60, 110.0))
        // 110 steps a minute; the regularity gate and the window edges cost at most a step or two.
        assertTrue("counted $steps", steps in 107..110)
    }

    @Test
    fun aStillWristCountsNothing() {
        assertEquals(0, bandPedometer(DoubleArray(6000) { 1.0 }))
    }

    @Test
    fun movementTooSmallForTheDeadZoneCountsNothing() {
        assertEquals(0, bandPedometer(walk(60, 110.0, amplitudeG = 0.02)))
    }

    @Test
    fun aSignalShorterThanThePeakWindowCountsNothing() {
        assertEquals(0, bandPedometer(DoubleArray(10) { 1.0 }))
    }
}
