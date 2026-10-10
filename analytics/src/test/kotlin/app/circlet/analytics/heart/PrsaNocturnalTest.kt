package app.circlet.analytics.heart

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrsaNocturnalTest {
    @Test
    fun dcPositiveAcNegativeNoRiskString() {
        val rr = ArrayList<Double>(400)
        for (i in 0 until 400) rr.add(1000.0 + 20.0 * sin(2.0 * PI * i / 20.0))
        val dc = decelerationCapacity(rr)
        val ac = accelerationCapacity(rr)
        assertTrue(dc.present)
        assertTrue(dc.value!!.capacity > 0.0)
        assertTrue(ac.value!!.capacity < 0.0)
        assertEquals("DC", dc.value!!.kind)
        assertFalse(dc.toJson { it.toJson() }.toString().contains("risk"))
    }

    @Test
    fun prsaAbsentWithoutEnoughBeats() {
        assertFalse(decelerationCapacity(listOf(1000.0, 1010.0, 990.0)).present)
    }

    @Test
    fun lEqualsOneIsRefused() {
        val rr = ArrayList<Double>(200)
        for (i in 0 until 200) rr.add(1000.0 + 20.0 * sin(2.0 * PI * i / 20.0))
        val dc = decelerationCapacity(rr, l = 1)
        assertFalse(dc.present)
        assertEquals(0.0, dc.confidence, 0.0)
        assertTrue(dc.note!!.contains("l≥2"))
        assertFalse(accelerationCapacity(rr, l = 1).present)
        assertTrue(decelerationCapacity(rr, l = 2).present)
    }

    @Test
    fun lowest30MinTracksQuietTrough() {
        val hr = ArrayList<Double>()
        for (i in 0 until 1800) hr.add(70.0)
        for (i in 0 until 1800) hr.add(50.0)
        repeat(50) { hr.add(0.0) }
        val m = nocturnalRhr(hr)
        assertEquals(50.0, m.value!!.low30Mean, 1e-9)
        assertEquals(50.0, m.value!!.p1, 1.0)
    }

    @Test
    fun dipBandClassification() {
        val day = ArrayList<Double>(400)
        val night = ArrayList<Double>(400)
        for (i in 0 until 400) {
            day.add(80.0)
            night.add(60.0)
        }
        val m = hrDip(day, night)
        assertEquals(25.0, m.value!!.dipPct, 1e-9)
        assertEquals("dipper", m.value!!.band)
        val rDay = ArrayList<Double>(400)
        val rNight = ArrayList<Double>(400)
        for (i in 0 until 400) {
            rDay.add(60.0)
            rNight.add(70.0)
        }
        assertEquals("riser", hrDip(rDay, rNight).value!!.band)
    }

    @Test
    fun lowest30MinNeedsContiguousWindow() {
        val ramp = ArrayList<Double>(900)
        for (i in 0 until 900) ramp.add(100.0 - i * 50.0 / 900.0)
        val m = nocturnalRhr(ramp)
        assertFalse(m.present)
        assertNull(m.value)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun offSkinGapsAreNotCompacted() {
        val scattered = ArrayList<Double>(28800)
        for (i in 0 until 28800) scattered.add(if (i % 16 == 0) 60.0 else 0.0)
        assertFalse(nocturnalRhr(scattered).present)
        val contiguous = ArrayList<Double>()
        repeat(1000) { contiguous.add(0.0) }
        repeat(1800) { contiguous.add(60.0) }
        repeat(1000) { contiguous.add(0.0) }
        val ok = nocturnalRhr(contiguous)
        assertTrue(ok.present)
        assertEquals(60.0, ok.value!!.low30Mean, 1e-9)
    }

    @Test
    fun minCoverageZeroDoesNotAdmitAllOffSkin() {
        val allOffSkin = List(2000) { 0.0 }
        val m = nocturnalRhr(allOffSkin, minCoverage = 0.0)
        assertFalse(m.present)
        assertNull(m.value)
    }

    @Test
    fun hrDipRefusesOneSample() {
        val m = hrDip(listOf(70.0), listOf(60.0))
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("$hrDipMinSamples"))
    }
}
