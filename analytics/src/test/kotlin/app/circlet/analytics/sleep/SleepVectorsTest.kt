package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.DartRandom
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors from sleep_test.dart for the groups this package owns. */
class SleepVectorsTest {
    @Test
    fun resolverIsConsultedPastTheFirstSample() {
        val day = buildOvernight()
        val transitionTs = BASE + 3 * 3600
        val seen = HashSet<Int>()
        val s = segmentSleep(day.accel, day.hr, tzOffsetResolver = { ts ->
            seen.add(ts)
            if (ts < transitionTs) -18000 else -14400
        })
        assertTrue(s.present)
        assertTrue(seen.any { it >= transitionTs })
    }

    @Test
    fun explicitFixedTzOffsetSegments() {
        val day = buildOvernight()
        val s = segmentSleep(day.accel, day.hr, tzOffsetSec = -18000)
        assertTrue(s.present)
    }

    @Test
    fun squareWaveActivityFindsTheInactivityBlock() {
        val accel = ArrayList<AccelSample>()
        var t = 0.0
        for (i in 0 until 12 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        val nightStart = accel.size
        repeat(7 * 3600) {
            accel.add(AccelSample(t, 0.02, 0.02, 1.0))
            t += 1000.0
        }
        val nightEnd = accel.size
        for (i in 0 until 5 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        val m = vanHeesSleepWindow(accel)
        assertTrue(m.present)
        val w = m.value!!
        assertTrue(abs(w.onsetIdx - nightStart) < 10 * 60)
        assertTrue(abs(w.offsetIdx - nightEnd) < 10 * 60)
        assertTrue(w.sptSec > 6 * 3600)
        assertTrue(w.sptSec < 8 * 3600)
    }

    @Test
    fun allActiveHasNoSleepWindow() {
        val rnd = DartRandom(7)
        val accel = ArrayList<AccelSample>()
        for (i in 0 until 3600) {
            accel.add(
                AccelSample(
                    i * 1000.0,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                ),
            )
        }
        val m = vanHeesSleepWindow(accel)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun unstampedOffsetSampleKeepsOnsetMs() {
        val accel = ArrayList<AccelSample>()
        var t = 1000.0
        for (i in 0 until 12 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        repeat(7 * 3600) {
            accel.add(AccelSample(t, 0.02, 0.02, 1.0))
            t += 1000.0
        }
        val nightEnd = accel.size
        for (i in 0 until 5 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        val zeroWindowStart = nightEnd - 600
        for (idx in zeroWindowStart until nightEnd) {
            val a = accel[idx]
            accel[idx] = AccelSample(0.0, a.x, a.y, a.z)
        }
        val m = vanHeesSleepWindow(accel)
        assertTrue(m.present)
        val w = m.value!!
        assertTrue(w.offsetIdx in zeroWindowStart..nightEnd)
        assertNotNull(w.onsetMs)
        assertNull(w.offsetMs)
    }

    @Test
    fun unstampedOnsetSampleKeepsOffsetMs() {
        val accel = ArrayList<AccelSample>()
        var t = 1000.0
        for (i in 0 until 12 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        val nightStart = accel.size
        repeat(7 * 3600) {
            accel.add(AccelSample(t, 0.02, 0.02, 1.0))
            t += 1000.0
        }
        for (i in 0 until 5 * 3600) {
            val phase = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
            t += 1000.0
        }
        val a = accel[nightStart]
        accel[nightStart] = AccelSample(0.0, a.x, a.y, a.z)
        val m = vanHeesSleepWindow(accel)
        assertTrue(m.present)
        val w = m.value!!
        assertEquals(nightStart, w.onsetIdx)
        assertNull(w.onsetMs)
        assertNotNull(w.offsetMs)
    }

    @Test
    fun stillLowHrNightIsConsistent() {
        val accel = accel(2, 7, 1)
        val hr = hr(2, 7, 1)
        val s = segmentSleep(accel, hr, hrBaseline = List(60) { 70.0 })
        assertTrue(s.present)
        assertNotNull(s.window)
        assertTrue(s.inBedSec!! > 6 * 3600)
        assertTrue(s.inBedSec!! < 8 * 3600)
        assertTrue(s.efficiencyPct!! > 85.0)
        assertTrue(s.tstSec!! > roundHalfAwayFromZero(0.85 * s.inBedSec!!).toInt())
        var tst = 0
        var waso = 0
        var nrem = 0
        var rem = 0
        var wake = 0
        var first = -1
        var last = -1
        for (i in s.stages.indices) {
            when (s.stages[i]) {
                OsStage.WAKE -> wake++
                else -> {
                    tst++
                    if (s.stages[i] == OsStage.NREM) nrem++
                    if (s.stages[i] == OsStage.REM) rem++
                    if (first < 0) first = i
                    last = i
                }
            }
        }
        if (first >= 0) {
            for (i in first..last) {
                if (s.stages[i] == OsStage.WAKE) waso++
            }
        }
        assertEquals(s.inBedSec, s.stages.size)
        assertEquals(tst, s.tstSec)
        assertEquals(wake, s.wakeSec)
        assertEquals(nrem, s.nremSec)
        assertEquals(rem, s.remSec)
        assertEquals(waso, s.wasoSec)
        assertEquals(s.inBedSec, s.nremSec!! + s.remSec!! + s.wakeSec!!)
        assertEquals(100.0 * tst / s.inBedSec!!, s.efficiencyPct!!, 1e-6)
        val j = s.toJson()
        assertEquals(s.inBedSec, j["in_bed_sec"])
        assertEquals(s.tstSec, j["tst_sec"])
        assertTrue((j["confidence"] as Number).toDouble() > 0.0)
        val windowConf = (s.inBedSec!!.toDouble() / (7 * 3600)).coerceIn(0.3, 0.95)
        val expectedConf = ((windowConf + 0.35) / 2.0).coerceIn(0.0, kMaxSleepConfidence)
        assertEquals(expectedConf, s.confidence, 1e-9)
        assertEquals(s.stages.size, s.stages4.size)
        val allowed = setOf("wake", "light", "deep", "rem")
        var light4 = 0
        var deep4 = 0
        var rem4 = 0
        var wake4 = 0
        for (i in s.stages4.indices) {
            val lbl = s.stages4[i]
            assertTrue(allowed.contains(lbl))
            when (lbl) {
                "wake" -> {
                    wake4++
                    assertEquals(OsStage.WAKE, s.stages[i])
                }
                "light" -> {
                    light4++
                    assertEquals(OsStage.NREM, s.stages[i])
                }
                "deep" -> {
                    deep4++
                    assertEquals(OsStage.NREM, s.stages[i])
                }
                "rem" -> {
                    rem4++
                    assertEquals(OsStage.REM, s.stages[i])
                }
            }
        }
        assertEquals(s.nremSec, s.lightSec!! + s.deepSec!!)
        assertEquals(light4, s.lightSec)
        assertEquals(deep4, s.deepSec)
        assertEquals(s.remSec, rem4)
        assertEquals(s.wakeSec, wake4)
        assertEquals(s.inBedSec, light4 + deep4 + rem4 + wake4)
        assertTrue(s.deepSec!! <= s.nremSec!!)
        assertEquals(s.lightSec, j["light_sec"])
        assertEquals(s.deepSec, j["deep_sec"])
        assertEquals(true, j["deep_low_confidence"])
    }

    @Test
    fun briefMidNightMovementsDoNotFragmentTheWindow() {
        val moves = listOf(2 * 3600, 4 * 3600, 5 * 3600 + 1800)
        val s = segmentSleep(accel(2, 7, 1, moves), hr(2, 7, 1))
        assertTrue(s.present)
        assertTrue(s.inBedSec!! > 6 * 3600)
    }

    @Test
    fun noQualifyingSleepIsAbsent() {
        val rnd = DartRandom(7)
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (i in 0 until 4 * 3600) {
            accel.add(
                AccelSample(
                    i * 1000.0,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                ),
            )
            hr.add(75 + rnd.nextDouble() * 10)
        }
        val s = segmentSleep(accel, hr)
        assertFalse(s.present)
        assertEquals(0.0, s.confidence, 0.0)
        assertNull(s.tstSec)
        assertNull(s.window)
        assertTrue(s.stages.isEmpty())
    }

    @Test
    fun tooShortStillBlockIsAbsent() {
        val s = segmentSleep(accel(2, 2, 1), hr(2, 2, 1))
        assertFalse(s.present)
        assertEquals(0.0, s.confidence, 0.0)
    }

    @Test
    fun forcedWindowHonorsAShortUserWindow() {
        val a = accel(2, 2, 1)
        val h = hr(2, 2, 1)
        assertFalse(segmentSleep(a, h).present)
        val s = segmentSleep(a, h, forcedWindow = ForcedWindow(7200, 14400))
        assertTrue(s.present)
        assertNotNull(s.window)
        assertEquals(14400 - 7200, s.inBedSec)
        assertTrue(s.tstSec!! > roundHalfAwayFromZero(0.5 * s.inBedSec!!).toInt())
        assertEquals(s.inBedSec, s.stages.size)
        assertEquals(s.inBedSec, s.nremSec!! + s.remSec!! + s.wakeSec!!)
        assertEquals(s.nremSec, s.lightSec!! + s.deepSec!!)
    }

    @Test
    fun forcedWindowBypassesTheNoAutoWindowCase() {
        val rnd = DartRandom(7)
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        for (i in 0 until 4 * 3600) {
            accel.add(
                AccelSample(
                    i * 1000.0,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                ),
            )
            hr.add(75 + rnd.nextDouble() * 10)
        }
        assertFalse(segmentSleep(accel, hr).present)
        val s = segmentSleep(accel, hr, forcedWindow = ForcedWindow(3600, 3 * 3600))
        assertTrue(s.present)
        assertEquals(2 * 3600, s.inBedSec)
        assertEquals(s.inBedSec, s.stages.size)
    }

    @Test
    fun overnightMainSleepBeatsALongerDaytimeNap() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var t = 0.0
        fun active(secs: Int) {
            for (i in 0 until secs) {
                val phase = sin(i * 0.5)
                accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
                hr.add(72 + 2 * sin(i / 600.0))
                t += 1000.0
            }
        }
        fun stillSleep(secs: Int, bpm: Double) {
            for (i in 0 until secs) {
                accel.add(AccelSample(t, 0.02, 0.02, 1.0))
                hr.add(bpm + 1.5 * sin(i / 1800.0))
                t += 1000.0
            }
        }
        active(2 * 3600)
        stillSleep(4 * 3600, 49.0)
        active(6 * 3600)
        stillSleep(5 * 3600, 52.0)
        active(2 * 3600)
        val s = segmentSleep(accel, hr, hrBaseline = List(120) { 72.0 })
        assertTrue(s.present)
        assertTrue(s.inBedSec!! < 5 * 3600)
        assertTrue(s.inBedSec!! > 3 * 3600)
    }

    @Test
    fun splitOvernightFragmentsGroupIntoOneMainSleep() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var t = 0.0
        fun active(secs: Int, baseHr: Double = 72.0) {
            for (i in 0 until secs) {
                val phase = sin(i * 0.5)
                accel.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
                hr.add(baseHr + 2 * sin(i / 600.0))
                t += 1000.0
            }
        }
        fun stillSleep(secs: Int, bpm: Double) {
            for (i in 0 until secs) {
                accel.add(AccelSample(t, 0.02, 0.02, 1.0))
                hr.add(bpm + 1.5 * sin(i / 1800.0))
                t += 1000.0
            }
        }
        active(2 * 3600)
        stillSleep(3 * 3600, 49.0)
        active(30 * 60, 85.0)
        stillSleep(2 * 3600, 50.0)
        active(4 * 3600)
        val s = segmentSleep(accel, hr, hrBaseline = List(120) { 72.0 })
        assertTrue(s.present)
        assertTrue(s.inBedSec!! > 5 * 3600)
        assertTrue(s.wasoSec!! > 20 * 60)
    }

    @Test
    fun briefArousalsAreRescored() {
        val dayH = 2
        val nightH = 7
        val tailH = 1
        val arousals = listOf(
            90 * 60 to 90 * 60 + 120,
            200 * 60 to 200 * 60 + 180,
            300 * 60 to 300 * 60 + 150,
        )
        fun inArousal(s: Int) = arousals.any { s >= it.first && s < it.second }
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until dayH * 3600) {
            val p = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * p, 0.3, 0.9 * (1 - 0.2 * p)))
            hr.add(72 + (i % 5).toDouble())
            t += 1000.0
        }
        for (i in 0 until nightH * 3600) {
            if (inArousal(i)) {
                accel.add(AccelSample(t, 0.5, -0.4, 0.6))
                hr.add(82 + 3 * sin(i / 30.0))
            } else {
                accel.add(AccelSample(t, 0.02, 0.02, 1.0))
                hr.add(50 + 1.5 * sin(i / 1800.0))
            }
            t += 1000.0
        }
        for (i in 0 until tailH * 3600) {
            val p = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * p, 0.3, 0.9 * (1 - 0.2 * p)))
            hr.add(72 + (i % 5).toDouble())
            t += 1000.0
        }
        val s = segmentSleep(accel, hr, hrBaseline = List(60) { 72.0 })
        assertTrue(s.present)
        assertTrue(s.efficiencyPct!! > 88.0)
        assertTrue(s.wasoSec!! < 60 * 60)
    }

    @Test
    fun aSustainedThirtyMinBlockScoresAsWake() {
        val dayH = 2
        val nightH = 7
        val tailH = 1
        val blkStart = 180 * 60
        val blkEnd = blkStart + 30 * 60
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until dayH * 3600) {
            val p = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * p, 0.3, 0.9 * (1 - 0.2 * p)))
            hr.add(72 + (i % 5).toDouble())
            t += 1000.0
        }
        for (i in 0 until nightH * 3600) {
            if (i >= blkStart && i < blkEnd) {
                val p = sin(i * 0.7)
                accel.add(AccelSample(t, 0.4 * p, -0.3, 0.85 * (1 - 0.2 * p)))
                hr.add(92 + (i % 9).toDouble())
            } else {
                accel.add(AccelSample(t, 0.02, 0.02, 1.0))
                hr.add(50 + (i % 3).toDouble() - 1)
            }
            t += 1000.0
        }
        for (i in 0 until tailH * 3600) {
            val p = sin(i * 0.5)
            accel.add(AccelSample(t, 0.3 * p, 0.3, 0.9 * (1 - 0.2 * p)))
            hr.add(72 + (i % 5).toDouble())
            t += 1000.0
        }
        val s = segmentSleep(accel, hr, hrBaseline = List(60) { 72.0 }, tzOffsetSec = 0)
        assertTrue(s.present)
        assertTrue(s.wasoSec!! > 18 * 60)
    }

    @Test
    fun aTwentyFiveMinHoleStaysUnobserved() {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var t = 21 * 3600 * 1000.0
        fun active(secs: Int) {
            for (i in 0 until secs) {
                val p = sin(i * 0.5)
                accel.add(AccelSample(t, 0.3 * p, 0.3, 0.9 * (1 - 0.2 * p)))
                hr.add(75.0)
                t += 1000.0
            }
        }
        fun still(secs: Int) {
            repeat(secs) {
                accel.add(AccelSample(t, 0.02, 0.02, 1.0))
                hr.add(52.0)
                t += 1000.0
            }
        }
        active(3600)
        still(45 * 60)
        t += 25 * 60 * 1000.0
        still(6 * 3600)
        active(3600)
        val s = segmentSleep(accel, hr, hrBaseline = List(60) { 60.0 }, tzOffsetSec = 0)
        assertTrue(s.present)
        assertTrue(s.unobservedSec!! > 20 * 60)
    }

    @Test
    @Suppress("DEPRECATION")
    fun cardiopulmonaryCouplingAbstains() {
        val nn = ArrayList<Double>()
        val times = ArrayList<Double>()
        var t = 0.0
        repeat(1800) {
            val rr = 1000 + 40 * sin(2 * PI * 0.25 * (t / 1000.0))
            t += rr
            nn.add(rr)
            times.add(t)
        }
        val m = cardiopulmonaryCoupling(nn, times)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("respiration channel"))
    }

    @Test
    fun jitteryNremRemStreamConsolidates() {
        fun flick(base: OsStage, other: OsStage, e: Int) = if (e % 12 == 6) other else base
        val raw = ArrayList<OsStage>()
        for (e in 0 until 60) raw.add(flick(OsStage.NREM, OsStage.REM, e))
        for (e in 0 until 40) raw.add(flick(OsStage.REM, OsStage.NREM, e))
        for (e in 0 until 60) raw.add(flick(OsStage.NREM, OsStage.REM, e))
        val rawTrans = transitions(raw)
        val consolidated = consolidateSleepStages(raw, 30)
        val conTrans = transitions(consolidated)
        assertTrue(rawTrans > 20)
        assertTrue(conTrans < 8)
        assertTrue(conTrans < rawTrans / 3)
        assertTrue(consolidated.toSet().subtract(setOf(OsStage.WAKE, OsStage.NREM, OsStage.REM)).isEmpty())
        val rt = totals(raw)
        val ct = totals(consolidated)
        assertTrue(abs(ct.getValue(OsStage.NREM) - rt.getValue(OsStage.NREM)) < 30)
        assertTrue(abs(ct.getValue(OsStage.REM) - rt.getValue(OsStage.REM)) < 30)
        assertEquals(rt.getValue(OsStage.WAKE), ct.getValue(OsStage.WAKE))
    }

    @Test
    fun aGenuineRemEpisodeIsPreservedAndABriefGapIsBridged() {
        val raw = ArrayList<OsStage>()
        repeat(20) { raw.add(OsStage.NREM) }
        repeat(6) { raw.add(OsStage.REM) }
        raw.add(OsStage.NREM)
        repeat(5) { raw.add(OsStage.REM) }
        repeat(20) { raw.add(OsStage.NREM) }
        val c = consolidateSleepStages(raw, 30)
        assertEquals(12, c.count { it == OsStage.REM })
        assertEquals(2, transitions(c))
    }

    @Test
    fun aNremGapLongerThanTheRemAroundItIsNotBridged() {
        val raw = ArrayList<OsStage>()
        repeat(100) { raw.add(OsStage.NREM) }
        repeat(2) { raw.add(OsStage.REM) }
        repeat(9) { raw.add(OsStage.NREM) }
        repeat(2) { raw.add(OsStage.REM) }
        repeat(100) { raw.add(OsStage.NREM) }
        val c = consolidateSleepStages(raw, 30)
        assertEquals(0, c.count { it == OsStage.REM })
    }

    @Test
    fun deepIsWiderThanRemAtTheSameMinutes() {
        val s = stageIntervals(12000, 3600, 3600, 21600, kMaxSleepConfidence)
        assertTrue(s.deep.hiSec - s.deep.loSec > s.rem.hiSec - s.rem.loSec)
        assertEquals(3600, s.deep.pointSec)
    }

    @Test
    fun aBetterObservedNightGetsANarrowerInterval() {
        fun rem(conf: Double): StageInterval =
            stageIntervals(12000, 3600, 3600, 21600, conf).rem
        val good = rem(kMaxSleepConfidence)
        val poor = rem(0.0)
        assertTrue(good.hiSec - good.loSec < poor.hiSec - poor.loSec)
    }

    @Test
    fun zeroDeepPublishesAFloor() {
        val s = stageIntervals(18000, 0, 0, 18000, 0.5)
        assertEquals(0, s.deep.loSec)
        assertEquals(kStageIntervalFloorSec, s.deep.hiSec)
    }

    @Test
    fun noStageIntervalExceedsTheSleepItIsPartOf() {
        val s = stageIntervals(1000, 5000, 6000, 12000, 0.0)
        assertTrue(s.deep.hiSec <= 12000)
        assertTrue(s.rem.hiSec <= 12000)
        assertTrue(s.light.loSec >= 0)
    }

    @Test
    fun lightInheritsDeepsAbsoluteHalfWidth() {
        val s = stageIntervals(14400, 3600, 3600, 21600, 0.4)
        assertEquals(s.deep.hiSec - s.deep.loSec, s.light.hiSec - s.light.loSec)
    }

    @Test
    fun anAbsentNightHasNoRanges() {
        assertNull(SleepSegmentation.absent.stageRanges)
        assertNull(SleepSegmentation.absent.toJson()["deep_range_sec"])
    }

    private data class Day(val accel: List<AccelSample>, val hr: List<Double>)

    private fun buildOvernight(): Day {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        var i = 0
        fun seg(mins: Int, active: Boolean, bpm: Double) {
            repeat(mins * 60) {
                val ts = (BASE + i) * 1000.0
                val x = if (active) if (i % 2 == 0) 0.0 else 0.25 else 0.0
                accel.add(AccelSample(ts, x, 0.0, if (active) 0.97 else 1.0))
                hr.add(bpm)
                i++
            }
        }
        seg(120, true, 78.0)
        seg(420, false, 48.0)
        seg(120, true, 78.0)
        return Day(accel, hr)
    }

    private fun accel(dayH: Int, nightH: Int, tailH: Int, moveAt: List<Int> = emptyList()): List<AccelSample> {
        val out = ArrayList<AccelSample>()
        var t = 0.0
        fun active(secs: Int) {
            for (i in 0 until secs) {
                val phase = sin(i * 0.5)
                out.add(AccelSample(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
                t += 1000.0
            }
        }
        active(dayH * 3600)
        val moves = moveAt.toSet()
        for (i in 0 until nightH * 3600) {
            if (moves.contains(i)) {
                out.add(AccelSample(t, 0.5, -0.4, 0.6))
            } else {
                out.add(AccelSample(t, 0.02, 0.02, 1.0))
            }
            t += 1000.0
        }
        active(tailH * 3600)
        return out
    }

    private fun hr(dayH: Int, nightH: Int, tailH: Int): List<Double> {
        val hr = ArrayList<Double>()
        for (i in 0 until dayH * 3600) hr.add(72 + 2 * sin(i / 600.0))
        for (i in 0 until nightH * 3600) hr.add(50 + 1.5 * sin(i / 1800.0))
        for (i in 0 until tailH * 3600) hr.add(72 + 2 * sin(i / 600.0))
        return hr
    }

    private fun transitions(s: List<OsStage>): Int {
        var t = 0
        for (i in 1 until s.size) if (s[i] != s[i - 1]) t++
        return t
    }

    private fun totals(s: List<OsStage>): Map<OsStage, Int> {
        val m = mutableMapOf(OsStage.WAKE to 0, OsStage.NREM to 0, OsStage.REM to 0)
        for (x in s) m[x] = m.getValue(x) + 1
        return m
    }

    companion object {
        private const val BASE = 1700000000
    }
}
