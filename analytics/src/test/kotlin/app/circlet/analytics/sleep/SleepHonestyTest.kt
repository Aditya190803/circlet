package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.LombScargle
import app.circlet.analytics.foundations.LsPoint
import app.circlet.analytics.foundations.freqGrid
import app.circlet.analytics.foundations.lombScargle
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Honesty regressions from sleep_honesty_test.dart, except sleep-cycle binning. */
class SleepHonestyTest {
    @Test
    fun aForcedWindowHoldingZeroSamplesYieldsNoSleep() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (i in 0 until 4 * 3600) {
            accel.add(AccelSample((T0 + i) * 1000.0, 0.0, 0.0, 1.0))
            hr.add(55.0)
        }
        val onset = T0 + 50000
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(onset, onset + 8 * 3600))
        assertFalse(s.present)
        assertNull(s.tstSec)
        assertNull(s.efficiencyPct)
        assertTrue(s.stages.isEmpty())
    }

    @Test
    fun cardioStagerAbstainsWhenHeartRateIsAbsent() {
        val accel = List(2 * 3600) { i -> AccelSample((T0 + i) * 1000.0, 0.0, 0.0, 1.0) }
        val hr = List(2 * 3600) { 0.0 }
        val r = cardioStager(hr, accel)
        assertTrue(r.base.stages.isEmpty())
        assertEquals(0.0, r.confidence, 0.0)
        val withHr = List(2 * 3600) { 52.0 }
        assertTrue(cardioStager(withHr, accel).base.stages.isNotEmpty())
    }

    @Test
    fun strapOnTheNightstandIsNotAPerfectSleep() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var i = 0
        val start = MIDNIGHT + 21 * 3600
        fun seg(secs: Int, active: Boolean) {
            for (k in 0 until secs) {
                val x = if (active) if (k % 2 == 0) 0.0 else 0.3 else 0.005
                accel.add(AccelSample((start + i) * 1000.0, x, 0.0, if (active) 0.95 else 1.0))
                hr.add(0.0)
                i++
            }
        }
        seg(2 * 3600, true)
        seg(8 * 3600, false)
        seg(2 * 3600, true)
        val s = segmentSleep(accel, hr, tzOffsetSec = 0)
        assertFalse(s.present)
        assertNull(s.tstSec)
        assertNull(s.efficiencyPct)
    }

    @Test
    fun undecodedAccelIsNotStillness() {
        val hr = List(8 * 3600) { 52.0 }
        val absent = segmentSleep(
            nightAccel(valid = false),
            hr,
            forcedWindow = ForcedWindow(T0, T0 + 8 * 3600),
        )
        assertFalse(absent.present)
        assertNull(absent.tstSec)
        assertNull(absent.efficiencyPct)
        assertTrue(absent.absenceReason!!.contains("observed"))
        val present = segmentSleep(
            nightAccel(valid = true),
            hr,
            forcedWindow = ForcedWindow(T0, T0 + 8 * 3600),
        )
        assertTrue(present.present)
    }

    @Test
    fun aSixHourDropoutMakesTheNightAbsent() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        fun block(fromSec: Int, secs: Int) {
            for (k in 0 until secs) {
                accel.add(AccelSample((T0 + fromSec + k) * 1000.0, 0.005, 0.0, 1.0))
                hr.add(52.0)
            }
        }
        block(0, 3600)
        block(7 * 3600, 3600)
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 8 * 3600))
        assertFalse(s.present)
        assertNull(s.tstSec)
        assertNull(s.wakeSec)
        assertNull(s.efficiencyPct)
        assertTrue(s.absenceReason!!.contains("observed"))
    }

    @Test
    fun aTwoHourDropoutIsUnobservedNotWaso() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (k in 0 until 8 * 3600) {
            if (k >= 3 * 3600 && k < 5 * 3600) continue
            accel.add(AccelSample((T0 + k) * 1000.0, 0.005, 0.0, 1.0))
            hr.add(52.0)
        }
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 8 * 3600))
        assertTrue(s.present)
        assertEquals(8 * 3600, s.inBedSec)
        assertEquals(7200, s.unobservedSec)
        assertTrue(s.wasoSec!! < 120)
        assertEquals(8 * 3600 - 7200, s.tstSec!! + s.wakeSec!!)
        assertTrue(s.efficiencyPct!! > 95.0)
        assertEquals("unobserved", s.stages4[4 * 3600])
    }

    @Test
    fun aShortDropoutIsStillCarriedForward() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (k in 0 until 4 * 3600) {
            if (k >= 3600 && k < 3600 + 45) continue
            accel.add(AccelSample((T0 + k) * 1000.0, 0.005, 0.0, 1.0))
            hr.add(52.0)
        }
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 4 * 3600))
        assertTrue(s.tstSec!! > roundHalfAwayFromZero(0.9 * 4 * 3600).toInt())
    }

    @Test
    fun cardioWebsterBridgesOnlyRealFlankingSleep() {
        val sm = fragmented(15, 4, 5)
        websterRescoreCardio(sm, 30)
        assertEquals(4 * 8, wakeInBody(sm))
    }

    @Test
    fun autonomicWebsterBridgesOnlyRealFlankingSleep() {
        val sm = fragmented(15, 4, 5)
        websterRescoreAutonomic(sm, 30)
        assertEquals(4 * 8, wakeInBody(sm))
    }

    @Test
    fun aFragmentedNightKeepsItsWaso() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var i = 0
        fun push(secs: Int, awake: Boolean) {
            for (k in 0 until secs) {
                val ph = sin(k * 0.5)
                if (awake) {
                    accel.add(AccelSample((T0 + i) * 1000.0, 0.35 * ph, 0.3, 0.9 * (1 - 0.2 * ph)))
                    hr.add(88.0)
                } else {
                    accel.add(AccelSample((T0 + i) * 1000.0, 0.005, 0.0, 1.0))
                    hr.add(50.0)
                }
                i++
            }
        }
        push(60 * 60, false)
        repeat(6) {
            push(8 * 60, true)
            push(3 * 60, false)
        }
        push(180 * 60, false)
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 306 * 60))
        assertTrue(s.wasoSec!! > 25 * 60)
        assertTrue(s.efficiencyPct!! < 95.0)
    }

    @Test
    fun aFragmentedNightIsScoredAtItsCircadianCentre() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var i = 0
        val start = MIDNIGHT + 16 * 3600
        fun active(secs: Int, bpm: Double = 76.0) {
            for (k in 0 until secs) {
                val ph = sin(k * 0.5)
                accel.add(AccelSample((start + i) * 1000.0, 0.3 * ph, 0.3, 0.9 * (1 - 0.2 * ph)))
                hr.add(bpm + 2 * sin(k / 600.0))
                i++
            }
        }
        fun sleep(secs: Int, bpm: Double = 50.0) {
            for (k in 0 until secs) {
                accel.add(AccelSample((start + i) * 1000.0, 0.02, 0.02, 1.0))
                hr.add(bpm + 1.5 * sin(k / 1800.0))
                i++
            }
        }
        active(2 * 3600)
        sleep(6 * 3600)
        active(90 * 60, 90.0)
        sleep(2 * 3600)
        active(45 * 60, 90.0)
        sleep(2 * 3600)
        active(45 * 60, 90.0)
        sleep(2 * 3600)
        active(2 * 3600)
        val anchor = 33840
        val s = segmentSleep(
            accel,
            hr,
            hrBaseline = List(200) { 76.0 },
            tzOffsetSec = 0,
            habitualMidsleepSec = anchor,
        )
        assertTrue(s.present)
        val onsetSod = (s.window!!.onsetMs!! / 1000.0).toInt() % 86400
        assertEquals((1 * 3600 + 32 * 60).toDouble(), onsetSod.toDouble(), 120.0)
        assertTrue(s.inBedSec!! > 7 * 3600)
    }

    @Test
    fun perTimestampResolverKeepsTheHabitualAnchor() {
        val stdOffset = -5 * 3600
        val dstOffset = -4 * 3600
        val localMidsleep = 3 * 3600
        val switchDay = 8
        val switchTs = MIDNIGHT + switchDay * 86400
        fun offsetAt(ts: Int) = if (ts < switchTs) stdOffset else dstOffset
        val history = ArrayList<MidsleepBlock>()
        for (d in 0 until 16) {
            val off = if (d < switchDay) stdOffset else dstOffset
            val mid = MIDNIGHT + d * 86400 + localMidsleep - off
            history.add(MidsleepBlock(mid - 4 * 3600, mid + 4 * 3600, "d$d"))
        }
        val dstCorrect = habitualMidsleepSecFromHistory(history, tzOffsetResolver = { offsetAt(it) })
        assertNotNull(dstCorrect)
        assertEquals(localMidsleep.toDouble(), dstCorrect!!.toDouble(), 2.0)
        val frozen = habitualMidsleepSecFromHistory(history, tzOffsetSeconds = dstOffset)
        assertEquals((localMidsleep + 1800).toDouble(), frozen!!.toDouble(), 60.0)
        assertTrue(abs(frozen - dstCorrect) > 1500)
    }

    @Test
    fun aStillRecordDoesNotCertifyTheFinalFiveMinutes() {
        val n = 3600
        val win = 300
        val m = vanHeesSleepWindow(tailRecord(3280))
        assertTrue(m.present)
        val w = m.value!!
        assertFalse(w.immobile.last())
        assertEquals(3301, w.sptSec)
        assertTrue(w.offsetIdx <= n - win + 1)
        assertEquals(win - 1, w.undecidableSec)
        assertEquals(n, w.immobileUnknown.size)
        assertTrue(w.immobileUnknown.subList(n - win + 1, n).all { it })
        for (i in 0 until n - win + 1) assertFalse(w.immobileUnknown[i])
        assertEquals(win - 1, w.toJson()["undecidable_sec"])
    }

    @Test
    fun aMoveInsideTheTailIsResolvedPerSecond() {
        val m = vanHeesSleepWindow(tailRecord(3400))
        assertTrue(m.present)
        val w = m.value!!
        assertFalse(w.immobile[3350])
        assertFalse(w.immobileUnknown[3350])
        assertFalse(w.immobile[3500])
        assertTrue(w.immobileUnknown[3500])
        assertTrue(w.immobileUnknown[3350] != w.immobileUnknown[3500])
        assertEquals(193, w.undecidableSec)
    }

    @Test
    @Suppress("DEPRECATION")
    fun cardiopulmonaryCouplingIsAlwaysAbsent() {
        val nn = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        repeat(3600) {
            val rr = 1000 + 40 * sin(2 * PI * 0.25 * (t / 1000.0))
            t += rr
            nn.add(rr)
            ts.add(t)
        }
        val m = cardiopulmonaryCoupling(nn, ts)
        assertFalse(m.present)
        assertNull(m.value)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("respiration channel"))
    }

    @Test
    fun theWithdrawnRatioWasTheRrPeriodogram() {
        val nn = ArrayList<Double>()
        val tSec = ArrayList<Double>()
        var t = 0.0
        repeat(3600) {
            val rr = 1000 + 40 * sin(2 * PI * 0.25 * (t / 1000.0))
            t += rr
            nn.add(rr)
            tSec.add(t / 1000.0)
        }
        val freqs = freqGrid(0.001, 0.45, 200)
        val ls = lombScargle(tSec, nn, freqs)!!
        val rrRatio = ls.bandPower(0.1, 0.4) / ls.bandPower(0.01, 0.1)
        val coupling = LombScargle(ls.spectrum.map { LsPoint(it.freqHz, sqrt(it.psd * it.psd)) })
        val oldCpc = coupling.bandPower(0.1, 0.4) / coupling.bandPower(0.01, 0.1)
        assertEquals(1.0, oldCpc / rrRatio, 1e-4)
    }

    @Test
    fun ppgDropoutsAreNotCreditedAsSleep() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (k in 0 until 6 * 3600) {
            accel.add(AccelSample((T0 + k) * 1000.0, 0.005, 0.0, 1.0))
            val dropout = (k >= 3600 && k < 2 * 3600) || (k >= 4 * 3600 && k < 5 * 3600)
            hr.add(if (dropout) 0.0 else 52.0)
        }
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 6 * 3600))
        assertTrue(s.present)
        assertEquals(7140, s.unobservedSec)
        assertTrue(s.tstSec!! <= 6 * 3600 - 7140)
        assertEquals("unobserved", s.stages4[90 * 60])
    }

    @Test
    fun anAbstainedRunIsUnobservedNotWaso() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        val gapA = 4 * 3600
        val gapB = 6 * 3600
        for (k in 0 until 8 * 3600) {
            val inGap = (k >= gapA && k < gapA + 120) || (k >= gapB && k < gapB + 120)
            if (inGap) continue
            accel.add(AccelSample((T0 + k) * 1000.0, 0.005, 0.0, 1.0))
            val mid = k >= gapA + 120 && k < gapB
            hr.add(if (mid && (k - gapA - 120) % 660 >= 300) 0.0 else 55.0)
        }
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 8 * 3600))
        assertTrue(s.present)
        assertEquals(0, s.wasoSec)
        assertEquals("unobserved", s.stages4[5 * 3600])
        assertTrue(s.unobservedSec!! > 7000)
    }

    @Test
    fun theLongestStretchStopsAtAHole() {
        val s = blocks(
            listOf(
                Triple(0, 3 * 3600, false),
                Triple(3 * 3600 + 2400, 8 * 3600 - (3 * 3600 + 2400), false),
            ),
        )
        assertTrue(s.present)
        assertEquals(2400, s.unobservedSec)
        assertEquals(15600, s.longestSleepRunSec)
        assertTrue(s.longestSleepRunSec!! < s.tstSec!!)
        assertEquals(0, s.sustainedAwakenings)
    }

    @Test
    fun aSustainedWakeCountsAndTheHoleBesideItDoesNot() {
        val s = blocks(
            listOf(
                Triple(0, 3 * 3600, false),
                Triple(3 * 3600 + 2400, 600, true),
                Triple(3 * 3600 + 3000, 8 * 3600 - (3 * 3600 + 3000), false),
            ),
        )
        assertEquals(2400, s.unobservedSec)
        assertEquals(600, s.wakeSec)
        assertEquals(1, s.sustainedAwakenings)
        assertEquals(15000, s.longestSleepRunSec)
    }

    @Test
    fun aWakeShorterThanTheBarIsNotAnAwakening() {
        val s = blocks(
            listOf(
                Triple(0, 4 * 3600, false),
                Triple(4 * 3600, 120, true),
                Triple(4 * 3600 + 120, 8 * 3600 - (4 * 3600 + 120), false),
            ),
        )
        assertEquals(0, s.sustainedAwakenings)
        assertEquals(kSustainedAwakeningSec, s.toJson()["awakening_min_sec"])
    }

    private fun nightAccel(valid: Boolean): List<AccelSample> =
        List(8 * 3600) { k -> AccelSample((T0 + k) * 1000.0, 0.0, 0.0, 0.0, valid = valid) }

    private fun tailRecord(moveAt: Int, moveLen: Int = 10): List<AccelSample> {
        val n = 3600
        return List(n) { i ->
            if (i >= moveAt && i < moveAt + moveLen) {
                AccelSample(
                    (T0 + i) * 1000.0,
                    if (i % 2 == 0) 0.87 else 0.0,
                    0.0,
                    if (i % 2 == 0) 0.5 else 1.0,
                )
            } else {
                AccelSample((T0 + i) * 1000.0, 0.0, 0.0, 1.0)
            }
        }
    }

    private fun fragmented(leadMin: Int, wakeMin: Int, reps: Int): MutableList<OsStage> {
        val out = ArrayList<OsStage>()
        fun push(s: OsStage, mins: Int) {
            repeat(mins * 2) { out.add(s) }
        }
        push(OsStage.NREM, leadMin)
        repeat(reps) {
            push(OsStage.WAKE, wakeMin)
            push(OsStage.NREM, 1)
        }
        push(OsStage.WAKE, 20)
        return out
    }

    private fun wakeInBody(sm: List<OsStage>): Int {
        var first = -1
        var last = -1
        for (i in sm.indices) {
            if (sm[i] != OsStage.WAKE) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return 0
        var c = 0
        for (i in first..last) if (sm[i] == OsStage.WAKE) c++
        return c
    }

    private fun blocks(parts: List<Triple<Int, Int, Boolean>>): SleepSegmentation {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for ((from, secs, active) in parts) {
            for (k in 0 until secs) {
                accel.add(
                    AccelSample(
                        (T0 + from + k) * 1000.0,
                        if (active) if (k % 2 == 0) 0.0 else 0.35 else 0.004,
                        0.0,
                        if (active) 0.93 else 1.0,
                    ),
                )
                hr.add(if (active) 78.0 else 50.0)
            }
        }
        return segmentSleep(accel, hr, forcedWindow = ForcedWindow(T0, T0 + 8 * 3600))
    }

    companion object {
        private const val T0 = 1700000000
        private val MIDNIGHT = T0 - (T0 % 86400)
    }
}
