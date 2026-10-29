package app.circlet.core

import org.junit.Assert.*
import org.junit.Test

class GenerationTest {
    @Test fun heartRateAndAccelerationBoundsUseTheMeasuringStrap() {
        val gen4 = Sample(1, 220, emptyList(), null, null, null)
        assertEquals(220, gen4.measurementBpm())
        assertNull(gen4.copy(generation = StrapGeneration.GEN5).measurementBpm())
        assertEquals(210, gen4.copy(generation = StrapGeneration.GEN5, heartRateBpm = 210).measurementBpm())
        val motion = GravityVector(3f, 0f, 1f)
        assertFalse(SignalBounds.plausibleGravity(motion))
        assertTrue(SignalBounds.plausibleGravity(motion, StrapGeneration.GEN5))
        assertFalse(SignalBounds.plausibleGravity(GravityVector(Float.NaN, 0f, 1f), StrapGeneration.GEN5))
    }
}
