package app.circlet.analytics.sleep

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CV-06 nightly HRV shape. Vectors from OpenStrap sleep_test.dart. */
class NightHrvShapeTest {
    @Test
    fun recoversASuppressedFirstThirdAndARecoveredLastThird() {
        val n = syntheticNight(hours = 8.0) { h ->
            when {
                h < 2.7 -> 5.0
                h < 5.3 -> 15.0
                else -> 30.0
            }
        }
        val m = nightHrvShape(n.first, n.second)
        assertTrue(m.present)
        val v = m.value!!
        assertEquals(16, v.bins.size)
        assertTrue(v.bins.all { it.present })
        assertEquals(10.0, v.firstThirdMs!!, 1.0)
        assertEquals(60.0, v.lastThirdMs!!, 2.0)
        assertTrue(v.lastOverFirst!! > 3.0)
        for (b in v.bins) {
            assertTrue(b.loMs!! < b.rmssdMs!!)
            assertTrue(b.hiMs!! > b.rmssdMs!!)
        }
    }

    @Test
    fun aGappedBinAbstainsAndStaysInTheSeries() {
        val n = syntheticNight(hours = 8.0, gapFromH = 3.0, gapToH = 3.9) { 20.0 }
        val m = nightHrvShape(n.first, n.second)
        val v = m.value!!
        assertEquals(16, v.bins.size)
        val holes = v.bins.filter { !it.present }
        assertTrue(holes.isNotEmpty())
        assertTrue(holes.first().nBeats < kMinBeatsPerHrvBin)
        assertTrue(m.confidence < 0.8)
    }

    @Test
    fun aShortNightHasNoShape() {
        val n = syntheticNight(hours = 1.0) { 20.0 }
        val m = nightHrvShape(n.first, n.second)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("no shape"))
    }

    @Test
    fun doesNotPublishATimeToMax() {
        val n = syntheticNight(hours = 8.0) { 20.0 }
        val j = nightHrvShape(n.first, n.second).value!!.toJson()
        assertFalse(j.keys.contains("time_to_max_sec"))
        assertFalse(j.toString().contains("time_to"))
    }

    /**
     * 4-beat cycle [0, +2a, 0, −2a] so every successive difference is 2·amp
     * and the difference-series lag-1 ACF is 0 (hrvTime's jitter floor refuses −1).
     */
    private fun syntheticNight(
        hours: Double,
        gapFromH: Double? = null,
        gapToH: Double? = null,
        ampMs: (Double) -> Double,
    ): Pair<List<Double>, List<Double>> {
        val nn = ArrayList<Double>()
        val t = ArrayList<Double>()
        var now = 0.0
        var i = 0
        while (now < hours * 3_600_000.0) {
            val h = now / 3_600_000.0
            val v = 1000.0 + 2.0 * ampMs(h) * sin(2.0 * PI * i / 4.0)
            now += v
            i++
            if (gapFromH != null && gapToH != null && h >= gapFromH && h < gapToH) continue
            nn.add(v)
            t.add(now)
        }
        return nn to t
    }
}
