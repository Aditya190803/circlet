package app.circlet.analytics

import app.circlet.analytics.fitness.vo2maxSubmaxEstimate
import app.circlet.analytics.insight.recommendedBedtime
import app.circlet.analytics.insight.recommendedBedtimeMinutes
import app.circlet.analytics.insight.tagCorrelations
import app.circlet.analytics.strain.AccelPoint
import app.circlet.analytics.strain.BanisterSex
import app.circlet.analytics.strain.Calories
import app.circlet.analytics.strain.CalorieProfile
import app.circlet.analytics.strain.HeartRateZones
import app.circlet.analytics.strain.HrPoint
import app.circlet.analytics.strain.QUIET_WAKING_HRR
import app.circlet.analytics.strain.StrainScorer
import app.circlet.analytics.strain.banisterTrimp
import app.circlet.analytics.strain.baselineTrimp
import app.circlet.analytics.strain.ctlAtlTsb
import app.circlet.analytics.strain.dailyQuietWakingHrr
import app.circlet.analytics.strain.netTrimpAboveQuiet
import app.circlet.analytics.strain.personalQuietWakingHrr
import app.circlet.analytics.strain.sessionHrCeiling
import app.circlet.analytics.strain.strainCurveFromSeries
import app.circlet.analytics.strain.strainFromNetTrimp
import app.circlet.analytics.strain.strainScore
import app.circlet.analytics.strain.strainScoreFromSeries
import app.circlet.analytics.strain.strainScoreMetric
import app.circlet.analytics.strain.targetStrain
import app.circlet.analytics.wellness.IllnessState
import app.circlet.analytics.wellness.illnessCusum
import app.circlet.analytics.workout.hrRecovery
import app.circlet.core.AbsentReason
import app.circlet.core.CorrelationMetric
import app.circlet.core.Metric
import app.circlet.core.emptyDay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortedVectorsTest {
    @Test
    fun strainAnchorsMatchTheOpenStrapTable() {
        assertTrue(strainOfDay(dayHr(960, 85.0)) < 2.0)
        val walk = strainOfDay(dayHr(960, 85.0, listOf(60 to 105.0)))
        assertTrue(walk in 2.0..4.0)
        val run = strainOfDay(dayHr(960, 85.0, listOf(45 to 145.0)))
        assertTrue(run in 8.0..11.0)
        val hard = strainOfDay(dayHr(960, 85.0, listOf(90 to 165.0)))
        assertTrue(hard in 14.0..17.0)
        val maximal = strainOfDay(dayHr(960, 85.0, listOf(300 to 160.0)))
        assertTrue(maximal in 19.0..21.0)
        assertTrue(strainOfDay(dayHr(960, 85.0, listOf(190 to 160.0))) < 21.0)
        assertEquals(21.0, strainOfDay(dayHr(960, 85.0, listOf(195 to 160.0))), 1e-9)
        assertTrue(strainOfDay(dayHr(135, 85.0)) < 1.0)
        val easy = strainOfDay(dayHr(960, 85.0, listOf(30 to 130.0)))
        val mid = strainOfDay(dayHr(960, 85.0, listOf(60 to 150.0)))
        val harder = strainOfDay(dayHr(960, 85.0, listOf(120 to 170.0)))
        assertTrue(easy < mid && mid < harder)
        assertEquals(21.0, strainScore(1e9, 960.0, QUIET_WAKING_HRR), 1e-9)
        assertEquals(0.0, strainScore(0.0, 960.0, QUIET_WAKING_HRR), 0.0)
        assertEquals(0.0, strainScore(1.0, 960.0, QUIET_WAKING_HRR), 0.0)
    }

    @Test
    fun quietWakingLevelIsTheUsersNotAConstant() {
        val rhr = 55.0
        val hrMax = 187.0
        val quietBpm = rhr + 0.274 * (hrMax - rhr)
        fun scored(hr: List<Double>, quietHrr: Double? = null): Double {
            val level = quietHrr ?: dailyQuietWakingHrr(hr, rhr, hrMax)!!
            val trimp = banisterTrimp(hr, rhr, hrMax, BanisterSex.MALE)
            return strainScore(trimp.value!!, hr.size.toDouble(), level)
        }
        val nothing = List(960) { quietBpm }
        assertEquals(11.93, scored(nothing, 0.20), 0.05)
        assertEquals(0.0, scored(nothing), 0.0)
        val walk = List(900) { quietBpm } + List(60) { 105.0 }
        val run = List(915) { quietBpm } + List(45) { 145.0 }
        val hard = List(870) { quietBpm } + List(90) { 165.0 }
        assertEquals(12.61, scored(walk, 0.20), 0.05)
        assertEquals(2.78, scored(walk), 0.05)
        assertEquals(8.72, scored(run), 0.05)
        assertEquals(16.49, scored(hard), 0.05)
        val anchorQuiet = List(960) { 85.0 }
        assertEquals(0.1969, dailyQuietWakingHrr(anchorQuiet, 60.0, hrMax)!!, 0.001)
        assertEquals(0.0, strainOfDay(anchorQuiet), 0.0)
        val hike = List(960) { rhr + 0.45 * (hrMax - rhr) }
        assertNull(dailyQuietWakingHrr(hike, rhr, hrMax))
        val busy = List(960) { rhr + 0.35 * (hrMax - rhr) }
        assertEquals(0.35, dailyQuietWakingHrr(busy, rhr, hrMax)!!, 1e-9)
        val day = List(960) { quietBpm }
        assertNull(dailyQuietWakingHrr(day, null, hrMax))
        assertNull(dailyQuietWakingHrr(day, rhr, null))
        assertNull(dailyQuietWakingHrr(day.take(30), rhr, hrMax))
        assertNull(dailyQuietWakingHrr(List(960) { 0.0 }, rhr, hrMax))
    }

    @Test
    fun guardedNetDoesNotLetQuietTimeDebitExercise() {
        val rhr = 50.0
        val hrMax = 187.0
        fun q(x: Double) = rhr + x * (hrMax - rhr)
        val lowQuietRun = List(45) { 145.0 } + List(855) { q(0.10) }
        val jitter = List(900) { i -> if (i % 2 == 0) q(0.17) else q(0.23) }
        fun lump(hr: List<Double>, quiet: Double, rest: Double = rhr, max: Double = hrMax): Double {
            val t = banisterTrimp(hr, rest, max, BanisterSex.MALE)
            return strainScore(t.value!!, hr.size.toDouble(), quiet)
        }
        fun scored(hr: List<Double>, quiet: Double, rest: Double = rhr, max: Double = hrMax) =
            strainScoreFromSeries(hr, rest, max, quiet, BanisterSex.MALE, true)
        assertEquals(0.0, lump(lowQuietRun, 0.20), 0.0)
        assertEquals(9.38, scored(lowQuietRun, 0.20).value!!, 0.02)
        assertEquals(9.77, scored(lowQuietRun, 0.10).value!!, 0.02)
        assertEquals(0.46, lump(jitter, 0.20), 0.02)
        assertEquals(lump(jitter, 0.20), scored(jitter, 0.20).value!!, 1e-9)
        fun at(hr: List<Double>) = scored(hr, 0.20, 60.0, 187.0).value!!
        assertEquals(0.0, at(dayHr(960, 85.0)), 0.0)
        assertEquals(2.70, at(dayHr(960, 85.0, listOf(60 to 105.0))), 0.01)
        assertEquals(8.88, at(dayHr(960, 85.0, listOf(45 to 145.0))), 0.01)
        assertEquals(16.65, at(dayHr(960, 85.0, listOf(90 to 165.0))), 0.01)
        assertTrue(at(dayHr(960, 85.0, listOf(190 to 160.0))) < 21.0)
        assertEquals(21.0, at(dayHr(960, 85.0, listOf(195 to 160.0))), 1e-9)
        val t = banisterTrimp(dayHr(960, 85.0, listOf(45 to 145.0)), 60.0, 187.0, BanisterSex.MALE).value!!
        assertEquals(
            strainScore(t, 960.0, QUIET_WAKING_HRR),
            strainFromNetTrimp(t - baselineTrimp(960.0, QUIET_WAKING_HRR)),
            1e-12,
        )
        assertEquals(0.0, strainFromNetTrimp(-5.0), 0.0)
        assertEquals(0.0, strainFromNetTrimp(Double.NaN), 0.0)
        val settled = scored(lowQuietRun, 0.20)
        val calibrating = strainScoreFromSeries(lowQuietRun, rhr, hrMax, 0.20, BanisterSex.MALE, false)
        assertEquals(settled.value!!, calibrating.value!!, 0.0)
        assertEquals(0.6, settled.confidence, 0.0)
        assertEquals(0.45, calibrating.confidence, 0.0)
        assertTrue(calibrating.note.startsWith("calibrating"))
        for (hr in listOf(lowQuietRun, jitter, dayHr(960, 85.0, listOf(45 to 145.0)))) {
            val n = netTrimpAboveQuiet(hr, rhr, hrMax, 0.20, BanisterSex.MALE)!!
            assertTrue(scored(hr, 0.20).value!! >= strainFromNetTrimp(n.exercise) - 1e-9)
        }
    }

    @Test
    fun strainCurveBanksExercise() {
        val rhr = 50.0
        val hrMax = 187.0
        fun q(x: Double) = rhr + x * (hrMax - rhr)
        val hr = List(120) { q(0.10) } + List(45) { 145.0 } + List(735) { q(0.10) }
        val curve = strainCurveFromSeries(hr, rhr, hrMax, 0.20, BanisterSex.MALE)!!
        assertEquals(900, curve.size)
        assertEquals(9.38, curve[164], 0.02)
        for (i in 164 until curve.size) assertTrue(curve[i] >= curve[164] - 1e-9)
        assertEquals(
            strainScoreFromSeries(hr, rhr, hrMax, 0.20, BanisterSex.MALE, true).value!!,
            curve.last(),
            0.0,
        )
        val living = List(120) { q(0.30) } + List(45) { 145.0 } + List(735) { q(0.10) }
        val livingCurve = strainCurveFromSeries(living, rhr, hrMax, 0.20, BanisterSex.MALE)!!
        assertEquals(10.74, livingCurve[164], 0.02)
        assertEquals(9.38, livingCurve.last(), 0.02)
        val short = strainCurveFromSeries(listOf(145.0, Double.NaN, 0.0, 145.0), rhr, hrMax, 0.20, BanisterSex.MALE)!!
        assertEquals(short[0], short[1], 0.0)
        assertEquals(short[0], short[2], 0.0)
        assertTrue(short[3] > short[0])
        assertNull(strainCurveFromSeries(hr, rhr, hrMax, null, BanisterSex.MALE))
    }

    @Test
    fun personalQuietNeedsThreeDays() {
        assertEquals("need_baseline:have=0,need=3", personalQuietWakingHrr(emptyList()).note)
        assertEquals("need_baseline:have=2,need=3", personalQuietWakingHrr(listOf(0.2, 0.3)).note)
        val three = personalQuietWakingHrr(listOf(0.20, 0.30, 0.25))
        assertEquals(0.25, three.value!!.hrr, 1e-12)
        assertEquals(3, three.value!!.days)
        assertFalse(three.value!!.settled)
        assertEquals(0.4, three.confidence, 0.0)
        val seven = personalQuietWakingHrr(List(7) { 0.2 })
        assertTrue(seven.value!!.settled)
        assertEquals(0.7, seven.confidence, 0.0)
        val window = personalQuietWakingHrr(List(12) { 0.39 } + List(28) { 0.20 })
        assertEquals(0.20, window.value!!.hrr, 0.0)
        assertEquals(28, window.value!!.days)
        val filtered = personalQuietWakingHrr(listOf(Double.NaN, 0.0, 0.5, 0.2, 0.2, 0.2))
        assertEquals(0.20, filtered.value!!.hrr, 0.0)
        assertEquals(3, filtered.value!!.days)
    }

    @Test
    fun strainScoreMetricAbstainsOnMissingInputs() {
        assertFalse(strainScoreMetric(300.0, null, QUIET_WAKING_HRR, true).present)
        assertFalse(strainScoreMetric(null, 960.0, QUIET_WAKING_HRR, true).present)
        val missing = strainScoreMetric(392.9, 960.0, null, true)
        assertFalse(missing.present)
        assertTrue(missing.note.contains("quiet-waking"))
        val settled = strainScoreMetric(392.9, 960.0, QUIET_WAKING_HRR, true)
        val cal = strainScoreMetric(392.9, 960.0, QUIET_WAKING_HRR, false)
        assertEquals(settled.value!!, cal.value!!, 0.0)
        assertTrue(cal.confidence < settled.confidence)
        assertTrue(cal.note.contains("calibrating"))
        assertFalse(settled.note.contains("calibrating"))
        assertTrue(settled.present && settled.value!! > 14.0)
        val hr = List(100) { 120.0 }
        assertFalse(strainScoreFromSeries(hr, 50.0, 187.0, null, BanisterSex.MALE, true).present)
        assertFalse(strainScoreFromSeries(hr, 50.0, 187.0, 0.45, BanisterSex.MALE, true).present)
        assertTrue(strainScoreFromSeries(hr, 50.0, 187.0, 0.40, BanisterSex.MALE, true).present)
    }

    @Test
    fun banisterOneMinuteMatchesPublishedSexCoefficients() {
        val male = StrainScorer.banisterTrimp(listOf(150.0), 50.0, 140.0, listOf(1.0), false)
        val female = StrainScorer.banisterTrimp(listOf(150.0), 50.0, 140.0, listOf(1.0), true)
        assertEquals(1.801589, male, 1e-5)
        assertEquals(2.024984, female, 1e-5)
        assertEquals(StrainScorer.banisterY((150.0 - 50.0) / 140.0, false), 0.64 * kotlin.math.exp(1.92 * (100.0 / 140.0)), 1e-12)
    }

    @Test
    fun baselineAndLoadModel() {
        assertEquals(180.4, baselineTrimp(960.0, 0.20), 0.5)
        assertEquals(baselineTrimp(960.0, 0.20) / 2.0, baselineTrimp(480.0, 0.20), 1e-9)
        assertTrue(baselineTrimp(960.0, 0.274) > baselineTrimp(960.0, 0.20))
        assertEquals(baselineTrimp(960.0, 0.40), baselineTrimp(960.0, 0.90), 1e-9)
        val steady = ctlAtlTsb(List(60) { 50.0 })
        assertTrue(steady.present)
        assertEquals(50.0, steady.value!!.ctl, 1.0)
        assertTrue(abs(steady.value!!.tsb) < 1.0)
        val spiked = ctlAtlTsb(List(60) { 50.0 } + 300.0)
        assertTrue(spiked.value!!.atl > spiked.value!!.ctl)
        assertTrue(spiked.value!!.tsb < 0)
        assertFalse(ctlAtlTsb(listOf(500.0)).present)
        assertTrue(ctlAtlTsb(listOf(500.0)).note.contains("need_baseline"))
        assertFalse(ctlAtlTsb(List(13) { 50.0 }).present)
        assertTrue(ctlAtlTsb(List(14) { 50.0 }).present)
        val faded = ctlAtlTsb(listOf(600.0) + List(20) { 0.0 })
        assertTrue(faded.value!!.ctl < 90.0)
        assertTrue(faded.value!!.atl < faded.value!!.ctl)
    }

    @Test
    fun trimpToStrainClampsTheZeroToHundredScale() {
        assertEquals(0.0, StrainScorer.trimpToStrain(0.0), 0.0)
        assertEquals(100.0, StrainScorer.trimpToStrain(7200.0), 0.02)
        assertEquals(100.0, StrainScorer.trimpToStrain(14400.0), 0.0)
        assertEquals(100.0, StrainScorer.trimpToStrain(1e9), 0.0)
        assertTrue(StrainScorer.trimpToStrain(335.0) < 100.0)
    }

    @Test
    fun zonesFollowTanakaEdges() {
        val set = HeartRateZones.zones(40.0)
        assertEquals("tanaka", set.source)
        assertEquals(180.0, set.maxHr, 1e-9)
        assertEquals(0, set.zoneNumber(89.9))
        assertEquals(1, set.zoneNumber(90.0))
        assertEquals(2, set.zoneNumber(108.0))
        assertEquals(5, set.zoneNumber(180.0))
        val points = listOf(110.0, 130.0, 150.0, 170.0, 190.0).mapIndexed { i, bpm ->
            HrPoint(i * 60_000.0, bpm)
        }
        val time = HeartRateZones.timeInZone(points, HeartRateZones.zonesFromMaxHr(200.0))!!
        val minutes = time.toRoundedMinuteMap()
        assertEquals(1, minutes.getValue("z1"))
        assertEquals(1, minutes.getValue("z2"))
        assertEquals(1, minutes.getValue("z3"))
        assertEquals(1, minutes.getValue("z4"))
        assertEquals(1, minutes.getValue("z5"))
        assertEquals(60.0, time.secondsInZone(1), 1e-6)
    }

    @Test
    fun caloriesMatchMifflinAndTheKeytelGate() {
        assertEquals(1780.0, Calories.mifflinBmrKcalDay(80.0, 180.0, 30.0, "male"), 1e-9)
        assertEquals(1614.0, Calories.mifflinBmrKcalDay(80.0, 180.0, 30.0, "female"), 1e-9)
        val textbook = CalorieProfile(80.0, 180.0, 30.0, "male")
        val rest = Calories.dailyEnergy(List(1440) { 70.0 }, textbook, 190.0, 60.0)!!
        assertEquals(0.0, rest.active, 1e-6)
        assertEquals(1780.0, rest.basal, 1.0)
        assertEquals(0.0, Calories.dailyEnergy(List(1440) { 111.0 }, textbook, 190.0, 60.0)!!.active, 1e-6)
        assertTrue(Calories.dailyEnergy(List(1440) { 111.0 }, textbook, 187.0, 60.0)!!.active > 0.0)
        val profile = CalorieProfile(70.0, 170.0, 30.0, "male")
        assertEquals(0.0, Calories.dailyEnergy(List(960) { 100.0 }, profile, 187.0, 60.0)!!.active, 1e-6)
        val mixed = List(915) { 100.0 } + List(45) { 145.0 }
        assertEquals(553.0, Calories.dailyEnergy(mixed, profile, 187.0, 60.0)!!.active, 5.0)
        assertEquals(106.68, Calories.activeGateHr(184.2, 55.0)!!, 0.01)
    }

    @Test
    fun observedCeilingHoldsOnlyWhileMoving() {
        val moving = run(0.0, 40, 180.0, 0.20)
        val held = sessionHrCeiling(moving.first, moving.second, "gen4")
        assertTrue(held.present)
        assertEquals(180.0, held.value!!.bpm, 0.0)
        assertTrue(held.value!!.heldSeconds >= 15)
        val spikedHr = moving.first.toMutableList()
        spikedHr[20] = HrPoint(spikedHr[20].tsMs, 205.0)
        val base = run(0.0, 40, 150.0, 0.20)
        val spiked = base.first.toMutableList()
        spiked[20] = HrPoint(spiked[20].tsMs, 205.0)
        assertEquals(150.0, sessionHrCeiling(spiked, base.second, "gen4").value!!.bpm, 0.0)
        val still = run(0.0, 40, 180.0, 0.001)
        val quiet = sessionHrCeiling(still.first, still.second, "gen4")
        assertFalse(quiet.present)
        assertTrue(quiet.note.contains("no_held_ceiling"))
        val mid = run(0.0, 40, 175.0, 0.06)
        assertFalse(sessionHrCeiling(mid.first, mid.second, "gen4").present)
        assertTrue(sessionHrCeiling(mid.first, mid.second, "gen5").present)
        for (id in listOf(null, "", "imported", "gen6")) {
            val refused = sessionHrCeiling(moving.first, moving.second, id)
            assertFalse(refused.present)
        }
    }

    @Test
    fun vo2maxGatesMatchTheClinicalVectors() {
        val run = vo2maxSubmaxEstimate(2.78, 150.0, 600, 55.0, 185.0)
        assertTrue(run.present)
        assertTrue(run.value!! in 30.0..70.0)
        assertTrue(run.confidence in 0.0..0.6)
        val graded = vo2maxSubmaxEstimate(2.78, 150.0, 600, 55.0, 185.0, 4.0)
        assertTrue(graded.present && graded.value!! > run.value!!)
        assertTrue(vo2maxSubmaxEstimate(1.4, 120.0, 600, 58.0, 180.0).present)
        assertTrue(vo2maxSubmaxEstimate(2.78, 150.0, 120, 55.0, 185.0).note.contains("too short"))
        assertTrue(vo2maxSubmaxEstimate(4.0, 179.0, 600, 55.0, 185.0).note.contains("%HRR band"))
        assertTrue(vo2maxSubmaxEstimate(1.2, 70.0, 600, 55.0, 185.0).note.contains("%HRR band"))
        assertTrue(vo2maxSubmaxEstimate(2.78, 90.0, 600, 75.0, 90.0).note.contains("unreliable"))
        assertFalse(vo2maxSubmaxEstimate(0.0, 150.0, 600, 55.0, 185.0).present)
    }

    @Test
    fun heartRateRecoveryVectors() {
        val hr = ArrayList<Int>()
        repeat(30) { hr.add(170) }
        val endIdx = hr.size - 1
        for (s in 1..70) hr.add((170 - (40.0 * s / 60.0)).toInt().coerceIn(40, 200))
        val drop = hrRecovery(hr, endIdx, 60)
        assertTrue(drop.present)
        assertEquals(170.0, drop.value!!.peakHr, 0.0)
        assertEquals(40.0, drop.value!!.dropBpm, 3.0)
        assertFalse(hrRecovery(List(100) { 165 }, 30, 60).present)

        val sparseHr = ArrayList<Int>()
        val sparseTs = ArrayList<Int>()
        var t = 0
        for (i in 0 until 30) {
            sparseHr.add(180 - i)
            sparseTs.add(t)
            t += 20
        }
        repeat(200) { i ->
            val bpm = 151 - i
            sparseHr.add(if (bpm < 100) 100 else bpm)
            sparseTs.add(t)
            t += 1
        }
        val sparse = hrRecovery(sparseHr, 29, tsSec = sparseTs, peakWindowSec = 30)
        assertTrue(sparse.present)
        assertEquals(152.0, sparse.value!!.peakHr, 0.0)
        assertEquals(41.0, sparse.value!!.dropBpm, 0.0)
        assertEquals(0.7, sparse.confidence, 1e-9)

        val denseHr = ArrayList<Int>()
        val denseTs = ArrayList<Int>()
        repeat(30) { i ->
            denseHr.add(170)
            denseTs.add(i)
        }
        for (s in 1..70) {
            denseHr.add((170 - (40.0 * s / 60.0)).toInt())
            denseTs.add(29 + s)
        }
        val dense = hrRecovery(denseHr, 29, tsSec = denseTs, peakWindowSec = 30)
        assertEquals(0.9, dense.confidence, 1e-9)

        val holeHr = ArrayList<Int>()
        val holeTs = ArrayList<Int>()
        repeat(30) { i ->
            holeHr.add(170)
            holeTs.add(i)
        }
        for (s in 1..120) {
            if (s in 56..79) continue
            holeHr.add((170 - 0.5 * s).toInt())
            holeTs.add(29 + s)
        }
        assertFalse(hrRecovery(holeHr, 29, tsSec = holeTs).present)
    }

    @Test
    fun targetStrainIsTheBandMidpoint() {
        assertEquals(2.5, targetStrain(30).score.value!!, 1e-9)
        assertEquals(7.5, targetStrain(50).score.value!!, 1e-9)
        assertEquals(11.5, targetStrain(70).score.value!!, 1e-9)
        assertEquals(15.5, targetStrain(90).score.value!!, 1e-9)
        assertNull(targetStrain(null).score.value)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, targetStrain(null).score.absentReason)
    }

    @Test
    fun bedtimeBacksOffFromASevenAmWake() {
        val minutes = recommendedBedtimeMinutes(8 * 3600.0, 7 * 60.0, 90.0)
        assertEquals(1326.666666, minutes.value!!.bedtimeMinOfDay, 1e-4)
        val zone = ZoneId.of("UTC")
        val wake = LocalDate.of(2026, 6, 2)
        val bed = recommendedBedtime(Duration.ofHours(8), 90.0, zone, wake)!!
        val local = bed.atZone(zone)
        assertEquals(wake.minusDays(1), local.toLocalDate())
        assertEquals(22, local.hour)
        assertNull(recommendedBedtime(Duration.ofHours(8), null, zone, wake))

        val slept = emptyDay(wake.minusDays(1), AbsentReason.NOT_ENOUGH_DATA).copy(
            sleep = Metric.of(
                app.circlet.core.SleepSession(
                    id = "main",
                    kind = app.circlet.core.SleepKind.MAIN,
                    start = wake.minusDays(1).atTime(23, 0).atZone(zone).toInstant(),
                    end = wake.atTime(7, 0).atZone(zone).toInstant(),
                    epochs = emptyList(),
                    cycles = emptyList(),
                    latency = Duration.ZERO,
                    efficiency = Metric.of(90, app.circlet.core.Confidence.HIGH),
                    wakeEvents = emptyList(),
                    timeInBed = Duration.ofHours(8),
                    timeAsleep = Duration.ofHours(7),
                ),
                app.circlet.core.Confidence.HIGH,
            ),
            sleepNeed = app.circlet.core.SleepNeed(Metric.of(Duration.ofHours(8), app.circlet.core.Confidence.MEDIUM), null),
        )
        val coach = app.circlet.analytics.insight.CircletInsight()
        val tonight = coach.recommendedBedtime(
            listOf(slept),
            zone,
            wake.minusDays(1).atTime(18, 0).atZone(zone).toInstant(),
        )
        assertEquals(bed, tonight)
        assertNull(coach.recommendedBedtime(listOf(emptyDay(wake, AbsentReason.NOT_ENOUGH_DATA)), zone, wake.atStartOfDay(zone).toInstant()))
        val points = coach.trend(listOf(slept), app.circlet.core.MetricKind.STRAIN)
        assertEquals(1, points.size)
        assertEquals(slept.date, points.single().date)
    }

    @Test
    fun journalEffectIsTheMeanDifferenceOnceEachSideHasTwoDays() {
        val start = LocalDate.of(2026, 6, 1)
        val days = (0 until 6).map { offset ->
            val date = start.plusDays(offset.toLong())
            val score = if (offset == 1 || offset == 2) 80 else 50
            emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(
                recovery = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).recovery.copy(
                    score = Metric.of(score, app.circlet.core.Confidence.HIGH),
                ),
            )
        }
        val entries = (0 until 4).map { offset ->
            app.circlet.core.JournalEntry(
                start.plusDays(offset.toLong()),
                if (offset < 2) setOf("caffeine") else emptySet(),
                "",
            )
        }
        val rows = tagCorrelations(days, entries)
        val caffeine = rows.single { it.tagId == "caffeine" && it.metric == CorrelationMetric.RECOVERY }
        assertEquals(30.0, caffeine.effect.value!!, 1e-9)
        assertEquals(2, caffeine.daysWith)
        assertEquals(2, caffeine.daysWithout)
        val engine = app.circlet.analytics.insight.CircletInsight()
        val catalogue = listOf(
            app.circlet.core.JournalTag("caffeine", "caffeine", true),
            app.circlet.core.JournalTag("travel", "travel", false),
        )
        val fromEngine = engine.correlations(days, catalogue, entries)
        assertEquals(catalogue.size * CorrelationMetric.entries.size, fromEngine.size)
        val unused = fromEngine.single { it.tagId == "travel" && it.metric == CorrelationMetric.RECOVERY }
        assertNull(unused.effect.value)
        assertEquals(0, unused.daysWith)
        val thin = tagCorrelations(
            days,
            listOf(app.circlet.core.JournalEntry(start, setOf("alcohol"), "")),
        )
        val alcohol = thin.single { it.tagId == "alcohol" && it.metric == CorrelationMetric.RECOVERY }
        assertNull(alcohol.effect.value)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, alcohol.effect.absentReason)
    }

    @Test
    fun illnessCusumStaysGreenUntilTheBaselineExists() {
        val dates = (1..6).map { "2026-06-${it.toString().padStart(2, '0')}" }
        val days = illnessCusum(dates, List(6) { 55.0 })
        assertTrue(days.all { it.state == IllnessState.GREEN })
        assertTrue(days.last().need!!.contains("need_baseline"))
    }
}

private fun dayHr(totalMin: Int, quietHr: Double, bouts: List<Pair<Int, Double>> = emptyList()): List<Double> {
    val hr = MutableList(totalMin) { quietHr }
    var i = 0
    for ((mins, bpm) in bouts) {
        repeat(mins) {
            if (i < totalMin) hr[i++] = bpm
        }
    }
    return hr
}

private fun strainOfDay(hr: List<Double>, quietHrr: Double = QUIET_WAKING_HRR): Double {
    val trimp = banisterTrimp(hr, 60.0, 187.0, BanisterSex.MALE)
    check(trimp.present)
    return strainScore(trimp.value!!, hr.size.toDouble(), quietHrr)
}

private fun run(t0: Double, seconds: Int, bpm: Double, dev: Double): Pair<List<HrPoint>, List<AccelPoint>> {
    val hr = ArrayList<HrPoint>(seconds)
    val accel = ArrayList<AccelPoint>(seconds)
    for (i in 0 until seconds) {
        val t = t0 + i * 1000.0
        hr.add(HrPoint(t, bpm))
        accel.add(AccelPoint(t, 0.0, 0.0, 1.0 + dev))
    }
    return hr to accel
}
