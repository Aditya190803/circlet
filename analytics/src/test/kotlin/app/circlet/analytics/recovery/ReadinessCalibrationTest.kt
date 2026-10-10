package app.circlet.analytics.recovery

import app.circlet.analytics.foundations.DartRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessCalibrationTest {
    private fun around(c: Double, step: Double): List<Double> =
        List(14) { i -> c + if (i % 2 == 0) step else -step }

    @Test
    fun hrvAndRhrShare070AsMeanOfOrientedZ() {
        val m = readinessComposite(
            listOf(
                hrvInput(4.0, around(4.5, 0.1)),
                rhrInput(60.0, around(52.0, 2.0)),
                respInput(14.0, around(14.0, 0.5)),
                tempInput(2000.0, around(2000.0, 2.0), settledFraction = 0.97),
            ),
        )
        val c = m.drivers!!.associate { it.label to it.contribution }
        fun oz(label: String): Double =
            m.drivers!!.first { it.label == label }.detail!!.split("=").last().toDouble()
        val zH = oz("HRV")
        val zR = oz("RHR")
        assertEquals(0.70 * (zH + zR) / 2.0, m.value!!.compositeZ, 1e-5)
        assertEquals(0.35 * zH, c["HRV"]!!, 1e-5)
        assertEquals(0.35 * zR, c["RHR"]!!, 1e-5)
    }

    @Test
    fun oneAutonomicMemberCarriesTheWhole070() {
        val r = combineReadinessZ(
            listOf(
                ReadinessComponentZ("HRV", 0.40, -1.0),
                ReadinessComponentZ("RR", 0.20, 1.0),
            ),
        )
        assertEquals(0.90, r.weightSum, 1e-12)
        assertEquals((0.70 * -1.0 + 0.20 * 1.0) / 0.90, r.z, 1e-12)
    }

    @Test
    fun correlatedHrvRhrDipAgreesAndDisagreementCancels() {
        val agree = combineReadinessZ(
            listOf(
                ReadinessComponentZ("HRV", 0.4, -2.0),
                ReadinessComponentZ("RHR", 0.3, -2.0),
                ReadinessComponentZ("RR", 0.2, 2.0),
                ReadinessComponentZ("temp", 0.1, 2.0),
            ),
        )
        assertEquals(-0.8, agree.z, 1e-12)
        val disagree = combineReadinessZ(
            listOf(
                ReadinessComponentZ("HRV", 0.4, -2.0),
                ReadinessComponentZ("RHR", 0.3, 2.0),
            ),
        )
        assertEquals(0.0, disagree.z, 1e-12)
    }

    @Test
    fun under14NightsUncalibratedLogistic() {
        val r = calibratedReadinessScore(-1.0, List(13) { 0.5 })
        assertNull(r.sigma)
        assertEquals(100.0 / (1.0 + exp(1.0)), r.score, 1e-9)
        val m = readinessComposite(
            listOf(
                hrvInput(4.5, around(4.5, 0.1)),
                rhrInput(52.0, around(52.0, 2.0)),
            ),
            compositeZHistory = List(5) { 0.0 },
        )
        assertEquals(
            mapOf("status" to "calibrating", "nights" to 5),
            m.value!!.toJson()["calibration"],
        )
        assertTrue(m.note!!.contains("calibrating:have=5,need=14"))
    }

    @Test
    fun atLeast14NightsRescaledByDesignSigmaOverSigmaHat() {
        val hist = List(20) { i -> if (i % 2 == 0) 1.0 else -1.0 }
        val r = calibratedReadinessScore(-1.0, hist)
        val sigma = 1.0 * 1.4826
        assertEquals(sigma, r.sigma!!, 1e-9)
        assertEquals(20, r.nights)
        assertEquals(100.0 / (1.0 + exp(0.65 / sigma)), r.score, 1e-9)
    }

    @Test
    fun sigmaHatIsFloored() {
        val r = calibratedReadinessScore(-0.3, List(20) { 0.0 })
        assertEquals(readinessCalibrationSigmaFloor, r.sigma)
        assertEquals(100.0 / (1.0 + exp(0.65)), r.score, 1e-9)
    }

    @Test
    fun highVarianceUserRestTodayShareFallsTowardDesignedFivePercent() {
        val rnd = DartRandom(7)
        fun gauss(): Double =
            sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2.0 * PI * rnd.nextDouble())
        val zs = List(600) { 1.26 * gauss() }
        var restOld = 0
        var restNew = 0
        var scored = 0
        for (i in 28 until zs.size) {
            val hist = zs.subList(i - 28, i)
            scored++
            if (100.0 / (1.0 + exp(-zs[i])) < 26.0) restOld++
            if (calibratedReadinessScore(zs[i], hist).score < 26.0) restNew++
        }
        assertTrue(restOld.toDouble() / scored > 0.12)
        val share = restNew.toDouble() / scored
        assertTrue(share >= 0.02 && share <= 0.09)
    }

    @Test
    fun historyCentredAtMinus035ScoresMedianNear50() {
        val rnd = DartRandom(11)
        fun gauss(): Double =
            sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2.0 * PI * rnd.nextDouble())
        val zs = List(400) { -0.35 + 0.9 * gauss() }
        val scores = ArrayList<Double>()
        for (i in 28 until zs.size) {
            scores.add(calibratedReadinessScore(zs[i], zs.subList(i - 28, i)).score)
        }
        scores.sort()
        assertEquals(50.0, scores[scores.size / 2], 4.0)
    }

    @Test
    fun sustainedStepDownReadsLowThenConvergesSlowly() {
        val zs = ArrayList<Double>()
        for (i in 0 until 28) zs.add(0.6 * sin(i * 2.4))
        for (i in 0 until 40) zs.add(-1.0 + 0.6 * sin((i + 28) * 2.4))
        val s = ArrayList<CalibratedReadinessScore>()
        for (i in 28 until zs.size) {
            s.add(calibratedReadinessScore(zs[i], zs.subList(i - 28, i)))
        }
        fun meanScore(xs: List<CalibratedReadinessScore>): Double {
            var sum = 0.0
            for (r in xs) sum += r.score
            return sum / xs.size
        }
        val first = meanScore(s.take(7))
        assertTrue(first < 37.0)
        val last = meanScore(s.takeLast(7))
        assertTrue(last > first)
        assertTrue(last < 50.0)
        assertTrue(s.last().centreRaw!! < -0.5)
        assertEquals(-readinessCentreCap, s.last().centre!!, 0.0)
    }

    @Test
    fun capBoundaryClampsCentreOutsideHalf() {
        val at = calibratedReadinessScore(0.0, List(20) { 0.5 })
        assertEquals(0.5, at.centreRaw!!, 0.0)
        assertEquals(0.5, at.centre!!, 0.0)
        val past = calibratedReadinessScore(0.0, List(20) { -0.8 })
        assertEquals(-0.8, past.centreRaw!!, 0.0)
        assertEquals(-0.5, past.centre!!, 0.0)
        assertEquals(100.0 / (1.0 + exp(-0.65 * 0.5 / 0.3)), past.score, 1e-9)
        val m = readinessComposite(
            listOf(
                hrvInput(4.5, around(4.5, 0.1)),
                rhrInput(52.0, around(52.0, 2.0)),
            ),
            compositeZHistory = List(20) { -0.8 },
        )
        @Suppress("UNCHECKED_CAST")
        val cal = m.value!!.toJson()["calibration"] as Map<String, Any?>
        assertEquals(-0.8, cal["centre_raw"] as Double, 0.0)
        assertEquals(-0.5, cal["centre"] as Double, 0.0)
        assertTrue(m.note!!.contains("centre=-0.5,centre_raw=-0.8"))
    }

    @Test
    fun oneIllnessNightCannotMakeALowNightNormal() {
        val hist = listOf(4.56, 3.568, 4.623, 4.397, 4.662, 4.794, 4.653, 4.478)
        val m = readinessLnRmssd(hist)
        assertEquals(4.4653, m.value!!.rolling7Mean, 1e-3)
        assertEquals(4.623, m.value!!.rolling7Median, 1e-9)
        assertEquals("suppressed", m.value!!.band)
        assertTrue(m.value!!.z!! < -1.0)
    }
}
