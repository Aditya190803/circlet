package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepCadenceTest {
    @Test
    fun cadenceCoarserThanOneEpochAbstains() {
        val night = 8 * 3600
        val r = cardioStager(flatHr(300, night), stillAccel(300, night))
        assertTrue(r.base.stages.isEmpty())
        assertEquals(0.0, r.confidence, 0.0)
        assertEquals(0.0, r.base.wakePct, 0.0)
    }

    @Test
    fun epochGridIsRealSeconds() {
        val span = 3 * 3600
        val at1 = cardioStager(flatHr(1, span), stillAccel(1, span))
        val at5 = cardioStager(flatHr(5, span), stillAccel(5, span))
        assertEquals(360, at1.base.stages.size)
        assertEquals(at1.base.stages.size, at5.base.stages.size)
        assertEquals(30, at5.base.epochSec)
    }

    @Test
    fun oneHertzIsUntouched() {
        val span = 2 * 3600
        val r = cardioStager(flatHr(1, span), stillAccel(1, span))
        assertEquals(span / 30, r.base.stages.size)
    }

    @Test
    fun fiveSecondStreamReportsTheSameRestPeriodAsOneHertz() {
        val span = 3 * 3600
        val a = vanHeesSleepWindow(stillAccel(1, span))
        val b = vanHeesSleepWindow(stillAccel(5, span))
        assertTrue(a.present)
        assertTrue(b.present)
        assertEquals(a.value!!.sptSec.toDouble(), b.value!!.sptSec.toDouble(), 5.0)
        assertTrue(a.value!!.sptSec > 2 * 3600)
    }

    @Test
    fun coarserThanThePublishedVanHeesEpochAbstains() {
        val m = vanHeesSleepWindow(stillAccel(15, 3 * 3600))
        assertFalse(m.present)
        assertTrue(m.note!!.contains("cadence"))
    }

    @Test
    fun undecidableTailIsReportedInSeconds() {
        val span = 3 * 3600
        val a = vanHeesSleepWindow(stillAccel(1, span)).value!!
        val b = vanHeesSleepWindow(stillAccel(5, span)).value!!
        assertEquals(a.undecidableSec.toDouble(), b.undecidableSec.toDouble(), 5.0)
    }

    @Test
    fun theSamePhysicalDriftIsStagedTheSameAtOneHertzAndFiveSeconds() {
        val night = 8 * 3600
        val one = AdvancedSleepStager.detectSleep(driftGrav(1, night), hrTs(1, night))
        val five = AdvancedSleepStager.detectSleep(driftGrav(5, night), hrTs(5, night))
        assertTrue(one.isNotEmpty())
        assertTrue(five.isNotEmpty())
        val spanOne = one.first().end - one.first().start
        val spanFive = five.first().end - five.first().start
        assertTrue(abs(spanFive - spanOne) < spanOne * 0.05)
    }

    @Test
    fun pastTheCadenceCeilingTheStagerAbstains() {
        val night = 8 * 3600
        assertEquals(30.0, AdvancedSleepStager.maxStillCadenceSec, 0.0)
        assertTrue(AdvancedSleepStager.detectSleep(stillGrav(60, night), hrTs(60, night)).isEmpty())
        assertTrue(AdvancedSleepStager.detectSleep(stillGrav(300, night), hrTs(300, night)).isEmpty())
        assertTrue(AdvancedSleepStager.detectSleep(stillGrav(30, night), hrTs(30, night)).isNotEmpty())
    }

    private fun stillAccel(cadence: Int, spanSec: Int): List<AccelSample> {
        val out = ArrayList<AccelSample>()
        var t = 0
        while (t < spanSec) {
            out.add(AccelSample(t * 1000.0, 0.0, 0.0, 1.0))
            t += cadence
        }
        return out
    }

    private fun flatHr(cadence: Int, spanSec: Int, bpm: Double = 55.0): List<Double> {
        val out = ArrayList<Double>()
        var t = 0
        while (t < spanSec) {
            out.add(bpm)
            t += cadence
        }
        return out
    }

    private fun driftGrav(cadence: Int, spanSec: Int): List<GravTs> {
        val out = ArrayList<GravTs>()
        var t = 0
        while (t < spanSec) {
            out.add(GravTs(t, sin(0.004 * t), 0.0, cos(0.004 * t)))
            t += cadence
        }
        return out
    }

    private fun stillGrav(cadence: Int, spanSec: Int): List<GravTs> {
        val out = ArrayList<GravTs>()
        var t = 0
        while (t < spanSec) {
            out.add(GravTs(t, 0.0, 0.0, 1.0))
            t += cadence
        }
        return out
    }

    private fun hrTs(cadence: Int, spanSec: Int, bpm: Double = 52.0): List<HrTs> {
        val out = ArrayList<HrTs>()
        var t = 0
        while (t < spanSec) {
            out.add(HrTs(t, bpm))
            t += cadence
        }
        return out
    }
}
