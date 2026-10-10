package app.circlet.analytics.foundations

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun meanMedianPercentileAndStddev() {
        assertEquals(2.5, mean(listOf(1.0, 2.0, 3.0, 4.0))!!, 0.0)
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, percentile(listOf(1.0, 2.0, 3.0, 4.0), 50.0)!!, 0.0)
        assertClose(2.13809, stddev(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))!!, 1e-4)
        assertNull(mean(emptyList()))
        assertNull(stddev(listOf(5.0)))
    }

    @Test
    fun percentileSortedMatchesAndStaysNullOnEmpty() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(percentile(sorted, 50.0), percentileSorted(sorted, 50.0))
        assertEquals(percentile(sorted, 25.0), percentileSorted(sorted, 25.0))
        assertEquals(7.0, percentileSorted(listOf(7.0), 25.0)!!, 0.0)
        assertNull(percentileSorted(emptyList(), 25.0))
    }

    @Test
    fun madAndRobustZGuardAZeroMad() {
        assertEquals(0.0, mad(listOf(1.0, 1.0, 1.0, 1.0, 1.0), scaled = false)!!, 0.0)
        assertNull(robustZ(5.0, listOf(1.0, 1.0, 1.0, 1.0, 1.0)))
        assertEquals(1.0, mad(listOf(1.0, 2.0, 3.0, 4.0, 5.0), scaled = false)!!, 0.0)
        assertClose((7.0 - 3.0) / 1.4826, robustZ(7.0, listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!, 1e-6)
    }

    @Test
    fun olsSlopeAndInterceptOnAnExactLine() {
        val y = listOf(1.0, 3.0, 5.0, 7.0, 9.0)
        assertClose(2.0, olsSlope(y)!!, 1e-9)
        val fit = olsFit(y)!!
        assertClose(2.0, fit.slope, 1e-9)
        assertClose(1.0, fit.intercept, 1e-9)
    }

    @Test
    fun theilSenIgnoresAGrossOutlier() {
        val x = (0..9).map { it.toDouble() }
        val y = x.map { 2.0 * it }.toMutableList()
        y[9] = 999.0
        assertClose(2.0, theilSen(y, x)!!, 1e-9)
        assertTrue(olsSlope(y, x)!! > 5.0)
    }

    @Test
    fun lombScargleRecoversAKnownFrequency() {
        val f0 = 0.1
        val t = ArrayList<Double>()
        val y = ArrayList<Double>()
        var cur = 0.0
        var k = 0
        while (cur < 120.0) {
            t.add(cur)
            y.add(sin(2.0 * PI * f0 * cur))
            cur += 0.8 + 0.4 * ((k % 5) / 5.0)
            k++
        }
        val ls = lombScargle(t, y, freqGrid(0.01, 0.4, 400))!!
        assertClose(f0, ls.peakFreq(0.01, 0.4)!!, 0.01)
    }

    @Test
    fun lombScargleIsNullOnDegenerateInput() {
        assertNull(lombScargle(listOf(0.0, 1.0), listOf(1.0, 1.0), listOf(0.1)))
        assertNull(lombScargle(listOf(0.0, 1.0, 2.0, 3.0), listOf(5.0, 5.0, 5.0, 5.0), listOf(0.1)))
    }

    @Test
    fun presentMetricEncodesValueTierAndConfidence() {
        val metric = OsMetric(
            value = 42.0,
            confidence = 0.8,
            tier = Tier.HIGH,
            inputsUsed = listOf("rr"),
        )
        val json = metric.toJson()
        assertEquals(42.0, json["value"])
        assertEquals(0.8, json["confidence"])
        assertEquals("HIGH", json["tier"])
        assertEquals(listOf("rr"), json["inputs_used"])
        assertTrue(metric.present)
    }

    @Test
    fun absentMetricEmitsADashAndForcesConfidenceZero() {
        val metric = OsMetric.absent<Double>(
            tier = Tier.HIGH,
            inputsUsed = listOf("rr"),
            note = "no valid beats",
        )
        val json = metric.toJson()
        assertEquals("—", json["value"])
        assertEquals(0.0, json["confidence"])
        assertTrue(!metric.present)
    }

    @Test
    fun benjaminiHochbergStepUpKeepsQMonotoneInP() {
        val ps = listOf(0.01, 0.008, 0.024, 0.04, 0.6)
        val q = benjaminiHochberg(ps)
        assertClose(0.025, q[1]!!, 1e-12)
        assertClose(0.025, q[0]!!, 1e-12)
        assertClose(0.04, q[2]!!, 1e-12)
        assertClose(0.05, q[3]!!, 1e-12)
        assertClose(0.6, q[4]!!, 1e-12)
        val byP = ps.indices.sortedBy { ps[it] }
        for (k in 1 until byP.size) {
            assertTrue(q[byP[k]]!! >= q[byP[k - 1]]!!)
        }
    }

    @Test
    fun aFamilyOfOneIsTheIdentity() {
        assertClose(0.03, benjaminiHochberg(listOf(0.03)).single()!!, 1e-12)
    }

    @Test
    fun aNullPStaysNullAndDoesNotCountTowardFamilySize() {
        val q = benjaminiHochberg(listOf(0.01, null, 0.02))
        assertNull(q[1])
        assertClose(0.02, q[0]!!, 1e-12)
    }

    @Test
    fun qNeverGoesAboveOne() {
        val q = benjaminiHochberg(listOf(0.9, 0.95, 0.99))
        for (v in q) assertTrue(v!! <= 1.0)
    }

    @Test
    fun benjaminiHochbergEmptyInEmptyOut() {
        assertTrue(benjaminiHochberg(emptyList()).isEmpty())
    }

    @Test
    fun normalTwoSidedPMatchesTheLandmarks() {
        assertClose(1.0, normalTwoSidedP(0.0), 1e-6)
        assertClose(0.05, normalTwoSidedP(1.959964), 1e-5)
        assertClose(0.01, normalTwoSidedP(2.575829), 1e-5)
        assertClose(0.05, normalTwoSidedP(-1.959964), 1e-5)
    }

    @Test
    fun aNonFiniteZIsNoEvidence() {
        assertEquals(1.0, normalTwoSidedP(Double.NaN), 0.0)
    }

    @Test
    fun rrSeriesReconstructsBeatTimes() {
        val rr = RrSeries(listOf(1000.0, 1800.0, 2600.0), listOf(1000.0, 800.0, 800.0))
        assertEquals(listOf(1000.0, 1800.0, 2600.0), rr.beatTimesMs(0.0))
        assertEquals(3, rr.length)
    }

    @Test
    fun sampleCadenceMeasuresARegularStream() {
        assertClose(1.0, sampleCadenceSeconds(at(1.0, 100))!!, 1e-12)
        assertClose(60.0, sampleCadenceSeconds(at(60.0, 100))!!, 1e-12)
        assertClose(300.0, sampleCadenceSeconds(at(300.0, 100))!!, 1e-12)
    }

    @Test
    fun sampleCadenceAbstainsAboveTheMeasuredCeiling() {
        assertNull(sampleCadenceSeconds(at(301.0, 100)))
        assertNull(sampleCadenceSeconds(at(600.0, 100)))
    }

    @Test
    fun sampleCadenceIsNullWhenThereIsNothingToMeasure() {
        assertNull(sampleCadenceSeconds(emptyList()))
        assertNull(sampleCadenceSeconds(listOf(5.0)))
        assertNull(sampleCadenceSeconds(listOf(5.0, 5.0, 5.0)))
    }

    @Test
    fun dropoutsDoNotAbstain() {
        val night = at(1.0, 28800).toMutableList()
        night.add(28800.0 + 57600.0)
        assertClose(1.0, sampleCadenceSeconds(night)!!, 1e-12)
        val jitter = mutableListOf(0.0)
        for (i in 0 until 200) {
            jitter.add(jitter.last() + if (i % 2 == 0) 1.0 else 2.0)
        }
        assertTrue(sampleCadenceSeconds(jitter) != null)
    }

    @Test
    fun aStreamWithNoDominantModeHasNoCadence() {
        val mixed = mutableListOf(0.0)
        for (i in 0 until 99) {
            val step = when (i % 3) {
                0 -> 1.0
                1 -> 60.0
                else -> 600.0
            }
            mixed.add(mixed.last() + step)
        }
        assertNull(sampleCadenceSeconds(mixed))
    }

    private fun at(cadence: Double, n: Int): List<Double> = List(n) { it * cadence }
}

internal fun assertClose(expected: Double, actual: Double, tol: Double) {
    assertTrue(
        "expected $expected ± $tol but was $actual (delta ${abs(expected - actual)})",
        abs(expected - actual) <= tol,
    )
}
