package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.Tier
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ENMO, MAD, and dynAmp. Vectors from OpenStrap motion_test.dart. */
class EnmoTest {
    @Test
    fun stillOneGVectorHasZeroEnmoAndMad() {
        val r = enmoSeries(still(120, 0.0, 0.0, 1.0))
        assertEquals(1.0, r.gRef, 1e-9)
        assertEquals(2, r.minutes.size)
        for (m in r.minutes) {
            assertEquals(0.0, m.enmo, 1e-9)
            assertEquals(0.0, m.mad, 1e-9)
            assertEquals(1.0, m.meanMag, 1e-9)
        }
        assertEquals(1.0, r.coverage, 0.0)
    }

    @Test
    fun knownOscillatingMagnitudeHasKnownEnmoAndMad() {
        val s = ArrayList<AccelSample>()
        for (i in 0 until 60) {
            val mag = if (i % 2 == 0) 1.0 else 1.2
            s.add(AccelSample(i * 1000.0, 0.0, 0.0, mag))
        }
        val m = enmoSeries(s, gRef = 1.0).minutes.single()
        assertEquals(0.10, m.enmo, 1e-9)
        assertEquals(1.1, m.meanMag, 1e-9)
        assertEquals(0.10, m.mad, 1e-9)
    }

    @Test
    fun autoCalibratedGRefRecoversANonOneStillReference() {
        val s = ArrayList<AccelSample>()
        for (i in 0 until 100) s.add(AccelSample(i * 1000.0, 0.0, 0.0, 1.03))
        s[50] = AccelSample(50 * 1000.0, 0.0, 0.0, 1.8)
        val r = enmoSeries(s)
        assertEquals(1.03, r.gRef, 1e-6)
    }

    @Test
    fun relativeIntensityBandsAreWithinUserPercentiles() {
        val enmo = List(100) { i -> i * 0.01 }
        val m = relativeIntensityBands(enmo)
        assertTrue(m.present)
        val b = m.value!!
        assertTrue(b.lightCut!! < b.moderateCut!!)
        assertTrue(b.moderateCut!! < b.vigorousCut!!)
        assertEquals(Tier.RELATIVE, m.tier)
        assertEquals("sedentary", b.labels.first())
        assertEquals("vigorous", b.labels.last())
        assertEquals(100, b.minutesInBand.values.sum())
    }

    @Test
    fun frozenCutPointsOverrideTheInputsOwnPercentiles() {
        val restDay = List(100) { i -> 0.005 + i * 0.00025 }
        val self = relativeIntensityBands(restDay)
        assertTrue(self.value!!.minutesInBand.getValue("vigorous") > 0)
        val frozen = relativeIntensityBands(
            restDay,
            frozenCuts = FrozenCuts(light = 0.05, moderate = 0.15, vigorous = 0.30),
        )
        assertEquals(0, frozen.value!!.minutesInBand.getValue("vigorous"))
        assertEquals(0, frozen.value!!.minutesInBand.getValue("moderate"))
        assertEquals(0.30, frozen.value!!.vigorousCut!!, 0.0)
        assertTrue(frozen.note!!.contains("FROZEN"))
    }

    @Test
    fun nonMonotoneFrozenCutPointsAreAbsent() {
        val m = relativeIntensityBands(
            List(100) { i -> i * 0.01 },
            frozenCuts = FrozenCuts(light = 0.30, moderate = 0.15, vigorous = 0.05),
        )
        assertFalse(m.present)
    }

    @Test
    fun emptyInputIsAbsent() {
        val m = relativeIntensityBands(emptyList())
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(enmoSeries(emptyList()).minutes.isEmpty())
    }

    @Test
    fun stillWristDynAmpIsZeroAtAnyGravityWhileEnmoMoves() {
        val high = enmoSeries(series(2, active = { false }, gz = 1.03), gRef = 1.0)
        val low = enmoSeries(series(2, active = { false }, gz = 0.94), gRef = 1.0)
        for (m in high.minutes) assertEquals(0.0, m.dynAmp, 1e-12)
        for (m in low.minutes) assertEquals(0.0, m.dynAmp, 1e-12)
        assertEquals(0.03, high.minutes.first().enmo, 1e-9)
        assertEquals(0.0, low.minutes.first().enmo, 1e-9)
    }

    @Test
    fun knownAlternatingMotionDynAmpIsNearThePerturbation() {
        val r = enmoSeries(series(2, active = { true }, amp = 0.2))
        for (m in r.minutes) assertTrue(m.dynAmp in 0.17..0.20)
    }

    @Test
    fun constantPerAxisOffsetCancelsExactly() {
        val base = series(6, active = { m -> m % 2 == 1 }, amp = 0.3, axis = 1)
        val bx = 0.05
        val by = -0.03
        val bz = 0.11
        val shifted = base.map { s -> AccelSample(s.tsMs, s.x + bx, s.y + by, s.z + bz) }
        val a = enmoSeries(base, gRef = 1.0)
        val b = enmoSeries(shifted, gRef = 1.0)
        assertEquals(a.minutes.size, b.minutes.size)
        for (i in a.minutes.indices) {
            assertEquals(a.minutes[i].dynAmp, b.minutes[i].dynAmp, 1e-9)
        }
        val dEnmo = abs(b.minutes.first().enmo - a.minutes.first().enmo)
        assertTrue(dEnmo > 0.05)
    }

    @Test
    fun aRecordingGapDoesNotManufactureADynAmpSpike() {
        val out = ArrayList<AccelSample>()
        for (i in 0 until 300) out.add(AccelSample(i * 1000.0, 0.0, 0.0, 1.0))
        for (i in 0 until 300) out.add(AccelSample(3_600_000.0 + i * 1000.0, 1.0, 0.0, 0.0))
        val r = enmoSeries(out)
        for (m in r.minutes) assertEquals(0.0, m.dynAmp, 1e-9)
    }

    @Test
    fun unsortedInputIsOrderedBeforeTheGravityWindow() {
        val ordered = series(2, active = { true }, amp = 0.25)
        val shuffled = ordered.reversed()
        val a = enmoSeries(ordered)
        val b = enmoSeries(shuffled)
        for (i in a.minutes.indices) {
            assertEquals(a.minutes[i].dynAmp, b.minutes[i].dynAmp, 1e-9)
        }
    }

    @Test
    fun dynAmpIsExportedInToJson() {
        val r = enmoSeries(series(1, active = { true }, amp = 0.2))
        assertTrue(r.minutes.single().toJson()["dyn_amp_g"] is Double)
    }

    private fun still(n: Int, x: Double, y: Double, z: Double): List<AccelSample> =
        List(n) { i -> AccelSample(i * 1000.0, x, y, z) }

    private fun series(
        minutes: Int,
        active: (Int) -> Boolean,
        amp: Double = 0.2,
        axis: Int = 0,
        gz: Double = 1.0,
    ): List<AccelSample> {
        val out = ArrayList<AccelSample>()
        for (m in 0 until minutes) {
            for (s in 0 until 60) {
                val i = m * 60 + s
                val p = if (active(m)) (if (i % 2 == 0) amp else -amp) else 0.0
                out.add(
                    AccelSample(
                        i * 1000.0,
                        if (axis == 0) p else 0.0,
                        if (axis == 1) p else 0.0,
                        gz + if (axis == 2) p else 0.0,
                    ),
                )
            }
        }
        return out
    }
}
