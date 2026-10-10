package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static gravity-tilt → sleep position. Vectors from OpenStrap motion_test.dart. */
class OrientationTest {
    @Test
    fun zUpIsSupine() {
        val m = staticTilt(still(30, 0.0, 0.0, 1.0))
        assertTrue(m.present)
        assertEquals("supine", m.value!!.position)
        assertEquals(0.0, m.value!!.pitchDeg, 1e-6)
        assertEquals(0.0, m.value!!.rollDeg, 1e-6)
        assertEquals(1.0, m.value!!.stillness, 1e-9)
    }

    @Test
    fun gravityAlongPositiveYIsLateralRight() {
        val m = staticTilt(still(30, 0.0, 1.0, 0.0))
        assertTrue(m.present)
        assertEquals(90.0, m.value!!.rollDeg, 1e-6)
        assertEquals("lateral_right", m.value!!.position)
    }

    @Test
    fun gravityAlongNegativeYIsLateralLeft() {
        val m = staticTilt(still(30, 0.0, -1.0, 0.0))
        assertEquals("lateral_left", m.value!!.position)
    }

    @Test
    fun gravityAlongNegativeZIsProne() {
        val m = staticTilt(still(30, 0.0, 0.0, -1.0))
        assertEquals("prone", m.value!!.position)
    }

    @Test
    fun gravityAlongNegativeXIsUpright() {
        val m = staticTilt(still(30, -1.0, 0.0, 0.0))
        assertEquals(90.0, m.value!!.pitchDeg, 1e-6)
        assertEquals("upright", m.value!!.position)
    }

    @Test
    fun highMotionEpochIsAbsent() {
        val s = ArrayList<AccelSample>()
        for (i in 0 until 30) {
            s.add(AccelSample(i * 1000.0, 0.0, 0.0, if (i % 2 == 0) 0.2 else 2.0))
        }
        val m = staticTilt(s)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun aRotatingWristYieldsNoPosture() {
        val s = List(30) { i ->
            AccelSample(
                i * 1000.0,
                0.0,
                1.03 * sin(PI * i / 29.0),
                1.03 * cos(PI * i / 29.0),
            )
        }
        val m = staticTilt(s)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("rotating"))
    }

    @Test
    fun tenMinutesOfContinuousRotationPublishesZeroPostures() {
        val s = List(600) { i ->
            AccelSample(
                i * 1000.0,
                0.0,
                1.03 * sin(2.0 * PI * i / 60.0),
                1.03 * cos(2.0 * PI * i / 60.0),
            )
        }
        assertTrue(positionSeries(s).isEmpty())
    }

    @Test
    fun positionSeriesEmitsStableSupine() {
        val series = positionSeries(still(120, 0.0, 0.0, 1.0), epochSec = 30)
        assertEquals(4, series.size)
        assertTrue(series.all { it.position == "supine" })
    }

    private fun still(n: Int, x: Double, y: Double, z: Double): List<AccelSample> =
        List(n) { i -> AccelSample(i * 1000.0, x, y, z) }
}
