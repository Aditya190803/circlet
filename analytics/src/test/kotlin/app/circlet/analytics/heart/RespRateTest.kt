package app.circlet.analytics.heart

import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.correctRr
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespRateTest {
    @Test
    fun rsaQuarterHertzIsAbout15() {
        val s = syntheticRsaRr(modHz = 0.25, hrBpm = 60.0, ampMs = 40.0, beats = 500)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        assertTrue(m.note, m.present)
        assertEquals(15.0, m.value!!.brpm!!, 1.5)
        assertEquals(Tier.HIGH, m.tier)
        assertTrue(m.confidence > 0.0)
    }

    @Test
    fun rsaFifthHertzIsAbout12() {
        val s = syntheticRsaRr(modHz = 0.20, hrBpm = 65.0, ampMs = 35.0, beats = 500)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        assertTrue(m.note, m.present)
        assertEquals(12.0, m.value!!.brpm!!, 1.5)
    }

    @Test
    fun rsaAbove24IsReportedNotFolded() {
        for (trueBrpm in listOf(26.4, 28.8)) {
            val s = syntheticRsaRr(modHz = trueBrpm / 60.0, hrBpm = 75.0, ampMs = 40.0, beats = 600)
            val corr = correctRr(s.rr)
            val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
            assertTrue(m.note, m.present)
            assertEquals(trueBrpm, m.value!!.brpm!!, 1.5)
        }
    }

    @Test
    fun rsaUnresolvableBeatRateIsAbsent() {
        val s = syntheticRsaRr(modHz = 26.0 / 60.0, hrBpm = 43.0, ampMs = 40.0, beats = 600)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("alias"))
    }

    @Test
    fun rsaNoteStatesEnforcedCeiling() {
        val s = syntheticRsaRr(modHz = 0.25, hrBpm = 60.0, ampMs = 40.0, beats = 500)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        assertTrue(m.note!!.contains("could resolve up to"))
    }

    @Test
    fun rsaEightHourNightResolvesMedianRate() {
        for (seed in listOf(11, 12, 13)) {
            val s = rsaNight(seed)
            val corr = correctRr(s.rr, rrTsMs = s.times)
            val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
            assertTrue(m.note, m.present)
            assertEquals("seed $seed got ${m.value!!.brpm}", 17.5, m.value!!.brpm!!, 0.3)
            assertTrue(m.note, m.confidence > 0.85)
        }
    }

    @Test
    fun shuffledSurrogateNightIsWithheld() {
        val s = rsaNight(11)
        val corr = correctRr(s.rr, rrTsMs = s.times)
        val shuffled = corr.nn.toMutableList()
        shuffled.dartShuffle(HeartDartRandom(3))
        val m = rsaRespRate(shuffled, corr.nnTimesMs, artifactFraction = 0.0)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("sub-windows"))
    }

    @Test
    fun trimmingTheNightBarelyMovesTheRate() {
        val s = rsaNight(11)
        val corr = correctRr(s.rr, rrTsMs = s.times)
        val full = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 0.0)
        val k = floor(corr.nn.size * 0.9).toInt()
        val trimmed = rsaRespRate(
            corr.nn.subList(0, k),
            corr.nnTimesMs.subList(0, k),
            artifactFraction = 0.0,
        )
        assertTrue(full.present && trimmed.present)
        assertTrue(kotlin.math.abs(full.value!!.brpm!! - trimmed.value!!.brpm!!) < 0.5)
    }

    @Test
    fun seventyFivePercentCoverageIsNotAnAlias() {
        for (seed in listOf(11, 12, 13)) {
            val full = gappyRsaNight(seed)
            val gap = gappyRsaNight(seed, dropouts = listOf(1.0 to 1.85, 4.0 to 4.9))
            val cf = correctRr(full.rr, rrTsMs = full.times)
            val cg = correctRr(gap.rr, rrTsMs = gap.times)
            val spanSec = (cg.nnTimesMs.last() - cg.nnTimesMs.first()) / 1000.0
            assertTrue(spanSec / (cg.nn.size - 1) > 1.25)
            val mf = rsaRespRate(cf.nn, cf.nnTimesMs, artifactFraction = 1.0 - cf.cleanFraction)
            val mg = rsaRespRate(cg.nn, cg.nnTimesMs, artifactFraction = 1.0 - cg.cleanFraction)
            assertTrue(mf.note, mf.present)
            assertTrue("seed $seed: ${mg.note}", mg.present)
            assertEquals(mf.value!!.brpm!!, mg.value!!.brpm!!, 1.0)
            assertEquals(15.0, mg.value!!.brpm!!, 0.8)
            assertTrue(mg.value!!.usableSubwindows!! < mg.value!!.subwindows!!)
        }
    }

    @Test
    fun withheldNoteStatesTrueHeartRate() {
        val s = gappyRsaNight(
            seed = 5,
            hrBpm = 43.0,
            hours = 2.0,
            resp0Brpm = 20.0,
            resp1Brpm = 20.0,
            dropouts = listOf(0.5 to 0.9),
        )
        val c = correctRr(s.rr, rrTsMs = s.times)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("alias"))
        val hr = Regex("heart rate ([0-9.]+) bpm").find(m.note!!)
        assertNotNull(m.note, hr)
        assertEquals(43.0, hr!!.groupValues[1].toDouble(), 1.0)
    }

    @Test
    fun twelveContiguousMinutesPublishAtLowConfidence() {
        val s = gappyRsaNight(7, dropouts = listOf(0.0 to 3.4, 3.6 to 7.0))
        val c = correctRr(s.rr, rrTsMs = s.times)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertTrue(m.note, m.present)
        assertEquals(15.0, m.value!!.brpm!!, 0.8)
        assertEquals(3, m.value!!.usableSubwindows)
        assertTrue(m.confidence <= 0.25)
    }

    @Test
    fun twelveMinutesScatteredStayAbsent() {
        val dropouts = ArrayList<Pair<Double, Double>>()
        for (k in 0 until 4) dropouts.add((k * 0.25 + 0.05) to (k * 0.25 + 0.25))
        val s = gappyRsaNight(7, hours = 1.0, dropouts = dropouts)
        val bursts = ArrayList<DoubleArray>()
        bursts.add(doubleArrayOf(s.times.first(), s.times.first()))
        for (x in s.times.drop(1)) {
            if (x - bursts.last()[1] > 60e3) bursts.add(doubleArrayOf(x, x))
            bursts.last()[1] = x
        }
        assertEquals(4, bursts.size)
        for (b in bursts) assertTrue((b[1] - b[0]) / 1000.0 in 170.0..185.0)
        var sum = 0.0
        for (v in s.rr) sum += v
        assertEquals(12.0, sum / 60e3, 0.5)
        val c = correctRr(s.rr, rrTsMs = s.times)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("sub-windows"))
        assertTrue(m.note!!.contains("covered by clean beats"))
        assertFalse(m.note!!.contains("alias"))
    }

    @Test
    fun holeySubwindowsAreGappyNotLowBeatRate() {
        val dropouts = ArrayList<Pair<Double, Double>>()
        for (k in 0 until 24) {
            dropouts.add(((k * 300 + 100) / 3600.0) to ((k * 300 + 200) / 3600.0))
        }
        val s = gappyRsaNight(9, hours = 2.0, dropouts = dropouts)
        val c = correctRr(s.rr, rrTsMs = s.times)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("covered by clean beats"))
        assertFalse(m.note!!.contains("alias"))
        assertFalse(m.note!!.contains("beat rate too low"))
    }

    @Test
    fun subwindowCeilingComesFromMedianBeatInterval() {
        val dropouts = ArrayList<Pair<Double, Double>>()
        for (k in 0 until 48) {
            dropouts.add(((k * 150 + 60) / 3600.0) to ((k * 150 + 85) / 3600.0))
        }
        val s = gappyRsaNight(3, hours = 2.0, dropouts = dropouts)
        val c = correctRr(s.rr, rrTsMs = s.times)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertTrue(m.note, m.present)
        assertEquals(15.0, m.value!!.brpm!!, 1.0)
        assertEquals(46, m.value!!.subwindows)
        assertEquals(46, m.value!!.usableSubwindows)
    }

    @Test
    fun slowStretchSubwindowsAreDropped() {
        val a = gappyRsaNight(5, hrBpm = 56.0, hours = 1.4, resp0Brpm = 15.0, resp1Brpm = 15.0)
        val b = gappyRsaNight(6, hrBpm = 40.0, hours = 0.6, resp0Brpm = 15.0, resp1Brpm = 15.0)
        val rr = ArrayList<Double>()
        rr.addAll(a.rr)
        rr.addAll(b.rr)
        val t = ArrayList<Double>()
        t.addAll(a.times)
        for (x in b.times) t.add(a.times.last() + x)
        val c = correctRr(rr, rrTsMs = t)
        val m = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 1.0 - c.cleanFraction)
        assertTrue(m.note, m.present)
        assertEquals(15.0, m.value!!.brpm!!, 1.0)
        assertTrue(m.value!!.subwindows!! - m.value!!.usableSubwindows!! >= 10)
    }

    @Test
    fun atCeilingNoteNamesRejectingCeilings() {
        val rnd = HeartDartRandom(4)
        val nn = ArrayList<Double>()
        val t = ArrayList<Double>()
        var tMs = 0.0
        for (hr in listOf(52.0, 58.0)) {
            if (tMs > 0.0) tMs += 900e3
            val end = tMs + 3600e3
            while (tMs < end) {
                val v = 60000.0 / hr +
                    (if (nn.size % 2 == 0) 30.0 else -30.0) +
                    (rnd.nextDouble() - 0.5) * 0.5
                tMs += v
                nn.add(v)
                t.add(tMs)
            }
        }
        val m = rsaRespRate(nn, t, artifactFraction = 0.0)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("at/above"))
        val nums = Regex("ceiling \\(([0-9.]+)(?:–([0-9.]+))? br/min\\)").find(m.note!!)
        assertNotNull(m.note, nums)
        assertTrue(nums!!.groupValues[1].toDouble() in 25.0..27.0)
        assertTrue(nums.groupValues[2].toDouble() in 28.5..30.0)
    }

    @Test
    fun confidenceScalesWithUsableSubwindows() {
        val s = rsaNight(11)
        val c = correctRr(s.rr, rrTsMs = s.times)
        fun firstSec(sec: Double): TimedRr {
            val k = c.nnTimesMs.indexOfFirst { it - c.nnTimesMs.first() > sec * 1000.0 } + 1
            return TimedRr(c.nn.subList(0, k), c.nnTimesMs.subList(0, k))
        }
        val full = rsaRespRate(c.nn, c.nnTimesMs, artifactFraction = 0.0)
        assertTrue(full.confidence > 0.85)
        assertEquals(full.value!!.subwindows, full.value!!.usableSubwindows)
        val hour = firstSec(3600.0)
        val h = rsaRespRate(hour.rr, hour.times, artifactFraction = 0.0)
        assertEquals(23, h.value!!.usableSubwindows)
        assertTrue(h.confidence >= 0.85)
        val half = firstSec(1950.0)
        val m33 = rsaRespRate(half.rr, half.times, artifactFraction = 0.0)
        assertEquals(12, m33.value!!.usableSubwindows)
        assertTrue(m33.confidence < 0.6)
        val q = firstSec(900.0)
        val m15 = rsaRespRate(q.rr, q.times, artifactFraction = 0.0)
        assertTrue(m15.note, m15.present)
        assertEquals(5, m15.value!!.usableSubwindows)
        assertTrue(m15.confidence <= 0.45)
        val j = m15.value!!.toJson()
        assertEquals(5, j["usable_subwindows"])
        assertEquals(5, j["subwindows"])
    }

    @Test
    fun rsaAbsentOnTooFewBeats() {
        val m = rsaRespRate(listOf(800.0, 810.0, 790.0), listOf(800.0, 1610.0, 2400.0), artifactFraction = 0.0)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun riivFifthHertzIsAbout12() {
        val s = syntheticRiivAdc(hz = 0.2, seconds = 150)
        val m = riivRespRate(s.rr, s.times)
        assertTrue(m.note, m.present)
        assertEquals(12.0, m.value!!.brpm!!, 1.0)
        assertEquals(Tier.RELATIVE, m.tier)
    }

    @Test
    fun riivQuarterHertzIsAbout15() {
        val s = syntheticRiivAdc(hz = 0.25, seconds = 150)
        val m = riivRespRate(s.rr, s.times)
        assertTrue(m.note, m.present)
        assertEquals(15.0, m.value!!.brpm!!, 1.0)
    }

    @Test
    fun karlenGateFusesAgreementAndKeepsRsaOnDisagreement() {
        val rsa = syntheticRsaRr(modHz = 0.25, beats = 500)
        val corr = correctRr(rsa.rr)
        val mRsa = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        val adc = syntheticRiivAdc(hz = 0.25, seconds = 150)
        val mRiiv = riivRespRate(adc.rr, adc.times)
        val fused = fuseRespRate(mRsa, mRiiv)
        assertTrue(fused.present)
        assertTrue(fused.value!!.agreed)
        assertEquals("fused", fused.value!!.decision)
        assertEquals(15.0, fused.value!!.brpm!!, 1.5)
        assertTrue(fused.confidence >= mRsa.confidence)
        val adcHi = syntheticRiivAdc(hz = 0.40, seconds = 150)
        val mRiivHi = riivRespRate(adcHi.rr, adcHi.times)
        val dis = fuseRespRate(mRsa, mRiivHi)
        assertTrue(dis.present)
        assertFalse(dis.value!!.agreed)
        assertEquals("disagree", dis.value!!.decision)
        assertEquals(15.0, dis.value!!.brpm!!, 1.5)
    }

    @Test
    fun nyquistAbove30IsRefused() {
        val s = syntheticRiivAdc(hz = 0.45, seconds = 150)
        val m = riivRespRate(s.rr, s.times)
        if (m.present) assertTrue(m.value!!.brpm!! < 30.0)
    }

    @Test
    fun brvVariableRatesHaveCvAndSlope() {
        val m = breathingRateVariability(listOf(14.0, 15.0, 13.0, 16.0, 12.0, 17.0, 11.0))
        assertTrue(m.present)
        assertTrue(m.value!!.sdBrpm > 0.0)
        assertTrue(m.value!!.cv > 0.0)
        assertNotNull(m.value!!.trendSlope)
        assertEquals(7, m.value!!.nWindows)
    }

    @Test
    fun brvUnderThreeWindowsIsAbsent() {
        val m = breathingRateVariability(listOf(14.0, 15.0))
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun modHz025PeakTimes60EqualsBrpm() {
        assertPeakMatches(0.25)
    }

    @Test
    fun modHz020PeakTimes60EqualsBrpm() {
        assertPeakMatches(0.20)
    }

    @Test
    fun modHz030PeakTimes60EqualsBrpm() {
        assertPeakMatches(0.30)
    }

    private fun assertPeakMatches(hz: Double) {
        val s = syntheticRsaRr(modHz = hz, beats = 500)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        assertTrue(m.note, m.present)
        val v = m.value!!
        assertNotNull(v.brpm)
        assertNotNull(v.peakHz)
        assertNotNull(v.power)
        assertEquals(v.brpm!!, v.peakHz!! * 60.0, 1e-9)
        assertEquals(hz * 60.0, v.brpm!!, 1.5)
    }

    @Test
    fun jsonPairRoundTripsConsistently() {
        val s = syntheticRsaRr(modHz = 0.25, beats = 500)
        val corr = correctRr(s.rr)
        val m = rsaRespRate(corr.nn, corr.nnTimesMs, artifactFraction = 1.0 - corr.cleanFraction)
        val j = m.value!!.toJson()
        assertEquals(j["brpm"] as Double, (j["peak_hz"] as Double) * 60.0, 1e-4)
    }
}
