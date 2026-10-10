package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.Tier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nap detector. Whole of OpenStrap nap_test.dart — no external capture. */
class NapTest {
    @Test
    fun a30MinuteNapIsDetected() {
        val d = Day()
        d.active(120)
        d.still(30)
        d.active(120)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.present)
        assertEquals(1, m.value!!.size)
        val nap = m.value!!.single()
        assertEquals(30.0 * 60.0, nap.tstSec.toDouble(), 90.0)
        assertTrue(nap.tibSec >= nap.tstSec)
        assertTrue(nap.confidence in 0.2..0.85)
    }

    @Test
    fun a20MinuteNapIsDetected() {
        val d = Day()
        d.active(90)
        d.still(20)
        d.active(90)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(1, m.value!!.size)
        assertEquals(20.0 * 60.0, m.value!!.single().tstSec.toDouble(), 90.0)
    }

    @Test
    fun a12MinuteRestIsNotANap() {
        val d = Day()
        d.active(90)
        d.still(12)
        d.active(90)
        assertTrue(detectNaps(d.accel, d.hr).value!!.isEmpty())
    }

    @Test
    fun twoSeparateNapsStaySeparate() {
        val d = Day()
        d.active(60)
        d.still(20)
        d.active(60)
        d.still(25)
        d.active(60)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(2, m.value!!.size)
        assertEquals(20.0 * 60.0, m.value!![0].tstSec.toDouble(), 90.0)
        assertEquals(25.0 * 60.0, m.value!![1].tstSec.toDouble(), 90.0)
    }

    @Test
    fun aBriefArousalIsBridgedAndCostsTstButNotTib() {
        val d = Day()
        d.active(90)
        d.still(20)
        d.active(2)
        d.still(20)
        d.active(90)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(1, m.value!!.size)
        val nap = m.value!!.single()
        assertEquals(42.0 * 60.0, nap.tibSec.toDouble(), 120.0)
        assertTrue(nap.tstSec < nap.tibSec)
        assertTrue(nap.efficiency < 1.0)
    }

    @Test
    fun stillWithNoHrDipIsNotANap() {
        val d = Day()
        d.active(90, bpm = 80.0)
        d.still(40, bpm = 80.0)
        d.active(90, bpm = 80.0)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("no HR dip"))
    }

    @Test
    fun aSedentaryDayIsNotEightNaps() {
        val d = Day()
        repeat(8) {
            d.active(6, bpm = 96.0)
            d.still(25, bpm = 72.0)
        }
        d.active(60, bpm = 96.0)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("no HR dip"))
    }

    @Test
    fun aTenPercentHrContrastIsNotANap() {
        val d = Day()
        repeat(8) {
            d.active(6, bpm = 84.0)
            d.still(25, bpm = 76.0)
        }
        d.active(60, bpm = 84.0)
        assertTrue(detectNaps(d.accel, d.hr).value!!.isEmpty())
    }

    @Test
    fun anOffWristSpanIsRejected() {
        val d = Day()
        d.active(90)
        d.still(40)
        d.active(90)
        val offStart = 90 * 60
        val offEnd = offStart + 40 * 60
        val m = detectNaps(d.accel, d.hr, wristOff = listOf(listOf(offStart, offEnd)))
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("off-wrist"))
    }

    @Test
    fun aChargingOrWorkoutSpanIsRejected() {
        val d = Day()
        d.active(90)
        d.still(40)
        d.active(90)
        val exStart = 90 * 60
        val exEnd = exStart + 40 * 60
        val m = detectNaps(d.accel, d.hr, exclude = listOf(listOf(exStart, exEnd)))
        assertTrue(m.value!!.isEmpty())
    }

    @Test
    fun overlappingOffWristSpansCountTheirUnion() {
        val d = Day()
        d.active(90)
        d.still(40)
        d.active(90)
        val napStart = 90 * 60
        val dupStart = napStart + 5 * 60
        val dupEnd = dupStart + 12 * 60
        val m = detectNaps(
            d.accel,
            d.hr,
            wristOff = listOf(listOf(dupStart, dupEnd), listOf(dupStart, dupEnd)),
        )
        assertTrue(m.present)
        assertEquals(1, m.value!!.size)
    }

    @Test
    fun thinHrCoverageAbstainsForThatBout() {
        val d = Day()
        d.active(90)
        d.still(40, bpm = 0.0)
        d.active(90)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("unverifiable"))
    }

    @Test
    fun aBlockLongerThanSixHoursIsNotANap() {
        val d = Day()
        d.active(60)
        d.still(7 * 60)
        d.active(60)
        assertTrue(detectNaps(d.accel, d.hr).value!!.isEmpty())
    }

    @Test
    fun theMainSleepWindowIsCarvedOut() {
        val d = Day()
        d.active(60)
        d.still(120)
        d.active(60)
        val start = 60 * 60
        val end = start + 120 * 60
        val m = detectNaps(d.accel, d.hr, mainSleep = SleepWindowSpan(start, end))
        assertTrue(m.present)
        assertTrue(m.value!!.isEmpty())
    }

    @Test
    fun tooLittleDataIsAbsent() {
        val m = detectNaps(emptyList(), emptyList())
        assertFalse(m.present)
        assertNull(m.value)
        assertEquals(0.0, m.confidence, 0.0)
        assertEquals(Tier.ESTIMATE, m.tier)
        assertTrue(m.note!!.contains("too little data"))
    }

    @Test
    fun aDayWithNoAwakeHrIsAbsent() {
        val d = Day()
        d.active(90, bpm = 0.0)
        d.still(30, bpm = 0.0)
        d.active(90, bpm = 0.0)
        val m = detectNaps(d.accel, d.hr)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("awake daytime HR"))
    }

    @Test
    fun tooFewAwakeHrSamplesAbstain() {
        val d = Day()
        d.active(2, bpm = 80.0)
        d.active(120, bpm = 0.0)
        d.still(30)
        d.active(120, bpm = 0.0)
        val m = detectNaps(d.accel, d.hr)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("need"))
    }

    @Test
    fun aJudgedDayWithNoNapIsAnEmptyList() {
        val d = Day()
        d.active(240)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.present)
        assertTrue(m.value!!.isEmpty())
    }

    @Test
    fun aBoutStillRunningAtTheRecordEndIsDeferred() {
        val d = Day()
        d.active(120)
        d.still(90)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("deferred"))
    }

    @Test
    fun theSameBoutIsEmittedOnceTheRecordShowsItEnding() {
        val d = Day()
        d.active(120)
        d.still(90)
        d.active(60)
        assertEquals(1, detectNaps(d.accel, d.hr).value!!.size)
    }

    @Test
    fun anAwakeningInsideTheUnfinishedNightDoesNotFreeAPhantomNap() {
        val d = Day()
        d.still(360, bpm = 50.0)
        d.active(1020, bpm = 82.0)
        d.still(170, bpm = 50.0)
        d.active(6, bpm = 70.0)
        d.still(64, bpm = 50.0)
        val m = detectNaps(d.accel, d.hr, mainSleep = SleepWindowSpan(0, 360 * 60))
        assertTrue(m.present)
        assertTrue(m.value!!.isEmpty())
        assertTrue(m.note!!.contains("deferred"))
    }

    @Test
    fun aBoutAlreadyRunningAtTheRecordStartIsFlagged() {
        val d = Day()
        d.still(40)
        d.active(120)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(1, m.value!!.size)
        assertTrue(m.value!!.single().startsAtRecordEdge)
        assertTrue(m.note!!.contains("already in progress at the first sample"))
        assertEquals(true, m.value!!.single().toJson()["starts_at_record_edge"])
    }

    @Test
    fun aNapWithObservedSidesIsNotFlagged() {
        val d = Day()
        d.active(120)
        d.still(30)
        d.active(120)
        assertFalse(detectNaps(d.accel, d.hr).value!!.single().startsAtRecordEdge)
    }

    @Test
    fun theEdgeFlagPropagatesForwardThroughAChainedFragment() {
        val d = Day()
        d.still(40)
        d.active(6, bpm = 70.0)
        d.still(40)
        d.active(120)
        val naps = detectNaps(d.accel, d.hr).value!!
        assertEquals(2, naps.size)
        assertTrue(naps.all { it.startsAtRecordEdge })
    }

    @Test
    fun aRealNapIsStillDetectedOnASleepDominatedWindow() {
        val d = Day()
        d.active(50, bpm = 78.0)
        d.still(30, bpm = 60.0)
        d.active(70, bpm = 78.0)
        d.still(180, bpm = 48.0)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(1, m.value!!.size)
        assertEquals(30.0 * 60.0, m.value!!.single().tstSec.toDouble(), 90.0)
    }

    @Test
    fun aRealNapIsStillDetectedOnASedentaryDay() {
        val d = Day()
        repeat(4) {
            d.active(6, bpm = 96.0)
            d.still(25, bpm = 72.0)
        }
        d.still(50, bpm = 56.0)
        repeat(4) {
            d.active(6, bpm = 96.0)
            d.still(25, bpm = 72.0)
        }
        d.active(60, bpm = 96.0)
        val m = detectNaps(d.accel, d.hr)
        assertEquals(1, m.value!!.size)
    }

    @Test
    fun aGapInsideAStillBlockDoesNotBecomeOneLongNap() {
        val d = Day()
        d.active(90)
        d.still(10)
        d.gap(120)
        d.still(10)
        d.active(90)
        assertTrue(detectNaps(d.accel, d.hr).value!!.isEmpty())
    }

    @Test
    fun tibMatchesTheReportedBounds() {
        val d = Day()
        d.active(90)
        d.still(30)
        d.active(90)
        val nap = detectNaps(d.accel, d.hr).value!!.single()
        assertEquals(nap.endSec - nap.startSec, nap.tibSec)
    }

    @Test
    fun detectionDoesNotDependOnWallClockTimeOfDay() {
        val d = Day()
        d.active(120)
        d.still(30)
        d.active(120)
        val baseline = detectNaps(d.accel, d.hr)
        for (h in listOf(3, 7, 11, 14, 19, 23)) {
            val m = detectNaps(shifted(d.accel, h), d.hr)
            assertEquals(baseline.value!!.size, m.value!!.size)
            assertEquals(baseline.value!!.single().tstSec, m.value!!.single().tstSec)
        }
    }

    @Test
    fun aFiftyMinuteAllInvalidBlockEmitsNoNap() {
        val d = Day()
        d.active(120)
        d.unmeasuredAccel(50)
        d.active(120)
        val m = detectNaps(d.accel, d.hr)
        assertTrue(m.present)
        assertTrue(m.value!!.isEmpty())
    }

    @Test
    fun aNapIsReportedAtItsObservedLength() {
        val d = Day()
        d.active(120)
        d.still(20)
        d.unmeasuredAccel(25)
        d.active(120)
        val nap = detectNaps(d.accel, d.hr).value!!.single()
        assertEquals(20.0 * 60.0, nap.tibSec.toDouble(), 60.0)
        assertEquals(nap.tibSec, nap.tstSec)
    }

    @Test
    fun aFullyMeasuredNapOfTheSameShapeStillLands() {
        val d = Day()
        d.active(120)
        d.still(45)
        d.active(120)
        assertTrue(detectNaps(d.accel, d.hr).value!!.single().tibSec > 40 * 60)
    }

    private fun shifted(a: List<AccelSample>, hours: Int): List<AccelSample> {
        val delta = hours * 3600.0 * 1000.0
        return a.map { s -> AccelSample(s.tsMs + delta, s.x, s.y, s.z, s.valid) }
    }

    private class Day {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        private var i = 0

        fun gap(minutes: Int) {
            i += minutes * 60
        }

        fun active(minutes: Int, bpm: Double = 82.0) {
            repeat(minutes * 60) {
                val deg = 45.0 + 35.0 * sin(i * 0.4)
                val rad = deg * PI / 180.0
                accel.add(AccelSample(i * 1000.0, cos(rad), 0.0, sin(rad)))
                hr.add(bpm)
                i++
            }
        }

        fun unmeasuredAccel(minutes: Int, bpm: Double = 60.0) {
            repeat(minutes * 60) {
                accel.add(AccelSample(i * 1000.0, 0.0, 0.0, 0.0, valid = false))
                hr.add(bpm)
                i++
            }
        }

        fun still(minutes: Int, bpm: Double = 54.0) {
            repeat(minutes * 60) {
                accel.add(AccelSample(i * 1000.0, 0.0, 0.0, 1.0))
                hr.add(bpm)
                i++
            }
        }
    }
}
