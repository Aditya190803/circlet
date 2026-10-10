package app.circlet.analytics.heart

import app.circlet.analytics.foundations.stddev
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrvFreqTest {
    @Test
    fun spectralPeakAtInjectedRsa() {
        val rr = ArrayList<Double>(256)
        val times = ArrayList<Double>(256)
        var t = 0.0
        for (i in 0 until 256) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rr.add(v)
            t += v
            times.add(t)
        }
        val m = hrvFreq(rr, times, artifactFraction = 0.0)
        assertTrue(m.present)
        assertFalse(m.value!!.hfGated)
        assertNotNull(m.value!!.hf)
        assertTrue(m.value!!.hf!! > 0.0)
    }

    @Test
    fun midSegmentGapIsRejected() {
        val rr = ArrayList<Double>()
        val times = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until 28) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rr.add(v)
            t += v
            times.add(t)
        }
        t += 45000.0
        for (i in 0 until 4) {
            rr.add(1000.0)
            t += 1000.0
            times.add(t)
        }
        val gapped = hrvFreq(rr, times, artifactFraction = 0.0)
        assertNull(gapped.value?.hf)
        val rrControl = ArrayList<Double>()
        val timesControl = ArrayList<Double>()
        t = 0.0
        while (t < 90000.0) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rrControl.add(v)
            t += v
            timesControl.add(t)
        }
        val control = hrvFreq(rrControl, timesControl, artifactFraction = 0.0)
        assertNotNull(control.value!!.hf)
    }

    @Test
    fun internalGapIsRejected() {
        val rr = ArrayList<Double>()
        val times = ArrayList<Double>()
        var t = 0.0
        while (t < 20000.0) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rr.add(v)
            t += v
            times.add(t)
        }
        t += 40000.0
        while (t < 66000.0) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rr.add(v)
            t += v
            times.add(t)
        }
        val gapped = hrvFreq(rr, times, artifactFraction = 0.0)
        assertNull(gapped.value?.hf)
    }

    @Test
    fun hfGatedAtArtifactHalf() {
        val rr = ArrayList<Double>(400)
        for (i in 0 until 400) rr.add(1000.0 + if (i % 2 == 0) 30.0 else -30.0)
        val times = ArrayList<Double>(400)
        var t = 0.0
        for (v in rr) {
            t += v
            times.add(t)
        }
        val m = hrvFreq(rr, times, artifactFraction = 0.5)
        assertTrue(m.value!!.hfGated)
        assertNull(m.value!!.hf)
        assertNotNull(m.value!!.lf)
    }

    @Test
    fun totalPowerNearSdnnSquaredAndRatioGridStable() {
        val nn = ArrayList<Double>(9000)
        val ts = ArrayList<Double>(9000)
        var t = 0.0
        for (i in 0 until 9000) {
            val tsec = t / 1000.0
            val v = 1000.0 +
                30.0 * sin(2.0 * PI * 0.10 * tsec) +
                20.0 * sin(2.0 * PI * 0.25 * tsec)
            nn.add(v)
            t += v
            ts.add(t)
        }
        val sd = stddev(nn)!!
        val v = hrvFreq(nn, ts, artifactFraction = 0.0).value!!
        assertEquals(450.0, v.lf!!, 45.0)
        assertEquals(200.0, v.hf!!, 20.0)
        assertEquals(sd * sd, v.total!!, 0.05 * sd * sd)
        val coarse = hrvFreq(nn, ts, artifactFraction = 0.0, oversample = 1.0)
        val fine = hrvFreq(nn, ts, artifactFraction = 0.0, oversample = 16.0)
        assertEquals(fine.value!!.lfhf!!, coarse.value!!.lfhf!!, 0.1)
    }

    @Test
    fun unresolvedBandIsAbsentNotZero() {
        val nn = ArrayList<Double>(400)
        for (i in 0 until 400) nn.add(1000.0 + (i % 7))
        val ts = ArrayList<Double>(400)
        var t = 0.0
        for (v in nn) {
            t += v
            ts.add(t)
        }
        val m = hrvFreq(nn, ts, artifactFraction = 0.0)
        assertNull(m.value!!.ulf)
        assertFalse(m.value!!.toJson().containsKey("ulf"))
    }

    @Test
    fun gatedHfIsNotInTotal() {
        val rr = ArrayList<Double>(400)
        val times = ArrayList<Double>(400)
        var t = 0.0
        for (i in 0 until 400) {
            val v = 1000.0 + 40.0 * sin(2.0 * PI * 0.25 * (t / 1000.0))
            rr.add(v)
            t += v
            times.add(t)
        }
        val clean = hrvFreq(rr, times, artifactFraction = 0.0)
        val gated = hrvFreq(rr, times, artifactFraction = 0.5)
        assertNotNull(clean.value!!.total)
        assertTrue(gated.value!!.hfGated)
        assertNull(gated.value!!.total)
        assertFalse(gated.value!!.toJson().containsKey("total"))
        assertTrue(gated.note!!.contains("total"))
    }

    @Test
    fun totalNamesTheBandsItSummed() {
        val nn = ArrayList<Double>(400)
        for (i in 0 until 400) nn.add(1000.0 + (i % 7))
        val ts = ArrayList<Double>(400)
        var t = 0.0
        for (v in nn) {
            t += v
            ts.add(t)
        }
        val v = hrvFreq(nn, ts, artifactFraction = 0.0).value!!
        assertNull(v.ulf)
        assertNotNull(v.totalBands)
        assertFalse(v.totalBands!!.contains("ulf"))
        val named = mapOf("ulf" to v.ulf, "vlf" to v.vlf, "lf" to v.lf, "hf" to v.hf)
        var sum = 0.0
        for (b in v.totalBands!!) sum += named[b]!!
        assertEquals(sum, v.total!!, 1e-9)
        assertEquals(v.totalBands, v.toJson()["total_bands"])
    }
}
