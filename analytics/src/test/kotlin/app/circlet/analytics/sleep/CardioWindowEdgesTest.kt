package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-contained window-gather cases from real_night_cardio_stager_test.dart. */
class CardioWindowEdgesTest {
    @Test
    fun windowGatherKeepsTiedBeatsAtBothEdges() {
        val accel = List(30) { i -> AccelSample(i * 1000.0, 0.0, 0.0, 1.0) }
        val ts = listOf(
            9000.0,
            10000.0, 10000.0, 10000.0,
            15000.0,
            20000.0, 20000.0, 20000.0,
            21000.0,
        )
        val rr = List(ts.size) { 900.0 }
        val beats = cleanBeatsInWindowForTest(rr, ts, accel, 0, 30, halfWinMs = 5000)
        assertEquals(7, beats.size)
        val marked = listOf(900.0, 901.0, 902.0, 903.0, 904.0, 905.0, 906.0, 907.0, 908.0)
        val got = cleanBeatsInWindowForTest(marked, ts, accel, 0, 30, halfWinMs = 5000)
        assertEquals(listOf(901.0, 902.0, 903.0, 904.0, 905.0, 906.0, 907.0), got)
    }

    @Test
    fun stepRejectedBeatDoesNotPairItsNeighbours() {
        val accel = stillAccel()
        val rr = ArrayList<Double>()
        repeat(40) { rr.add(800.0) }
        rr.add(1001.0)
        repeat(40) { rr.add(1200.0) }
        val d = cleanBeatDiffsInWindowForTest(rr, clock(rr), accel, 140, 170)
        assertTrue(d.isNotEmpty())
        assertTrue(d.all { kotlin.math.abs(it) <= 200.0 })
    }

    @Test
    fun beatsEitherSideOfAnRrHoleAreNotDifferenced() {
        val accel = stillAccel()
        val rr = ArrayList<Double>()
        repeat(40) { rr.add(800.0) }
        repeat(40) { rr.add(950.0) }
        val d = cleanBeatDiffsInWindowForTest(rr, clock(rr, holeAt = 40), accel, 140, 170)
        assertTrue(d.isNotEmpty())
        assertTrue(d.all { it == 0.0 })
    }

    @Test
    fun aWindowFragmentedIntoOneContiguousPairAbstains() {
        val accel = stillAccel()
        val rr = List(20) { i -> if (i == 1) 820.0 else 800.0 }
        val ts = ArrayList<Double>()
        var t = 140000.0
        for (i in rr.indices) {
            t += if (i == 1) 1000.0 else 5000.0
            ts.add(t)
        }
        assertEquals(listOf(20.0), cleanBeatDiffsInWindowForTest(rr, ts, accel, 140, 170))
        val w = windowRmssdRkForTest(rr, ts, accel, 140, 170)
        assertTrue(w.rmssd.isNaN())
        assertNull(w.rk)
    }

    private fun stillAccel(): List<AccelSample> =
        List(300) { i -> AccelSample(i * 1000.0, 0.0, 0.0, 1.0) }

    private fun clock(rr: List<Double>, holeAt: Int = -1): List<Double> {
        val ts = ArrayList<Double>()
        var t = 0.0
        for (i in rr.indices) {
            t += rr[i] + if (i == holeAt) 20000.0 else 0.0
            ts.add(floor(t / 1000.0) * 1000.0)
        }
        return ts
    }
}
