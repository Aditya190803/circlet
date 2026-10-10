package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.DartRandom
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedStagerTest {
    private data class Built(
        val grav: List<GravTs>,
        val hr: List<HrTs>,
        val rr: List<RrTs>,
    )

    private data class Blocks(val grav: List<GravTs>, val hr: List<HrTs>)

    private fun build(dayH: Int, nightH: Int, tailH: Int): Built {
        val grav = ArrayList<GravTs>()
        val hr = ArrayList<HrTs>()
        val rr = ArrayList<RrTs>()
        var t = 0
        fun active(secs: Int) {
            for (i in 0 until secs) {
                val phase = sin(i * 0.5)
                grav.add(GravTs(t, 0.3 * phase, 0.3, 0.9 * (1 - 0.2 * phase)))
                hr.add(HrTs(t, 72 + 2 * sin(i / 600.0)))
                t++
            }
        }
        active(dayH * 3600)
        for (i in 0 until nightH * 3600) {
            grav.add(GravTs(t, 0.001 * sin(i * 0.01), 0.001, 1.0))
            val bpm = 50 + 1.5 * sin(i / 1800.0)
            hr.add(HrTs(t, bpm))
            val rrMs = 60000.0 / bpm + if (i % 2 == 0) 20.0 else -20.0
            rr.add(RrTs(t, rrMs))
            t++
        }
        active(tailH * 3600)
        return Built(grav, hr, rr)
    }

    private fun stillBlocks(blocks: List<Pair<Int, Int>>): Blocks {
        val grav = ArrayList<GravTs>()
        val hr = ArrayList<HrTs>()
        for ((start, secs) in blocks) {
            for (i in 0 until secs) {
                grav.add(GravTs(start + i, 0.001 * sin(i * 0.01), 0.0, 1.0))
                hr.add(HrTs(start + i, 52.0))
            }
        }
        return Blocks(grav, hr)
    }

    private fun mainOf(sessions: List<SleepSession>): SleepSession =
        sessions.reduce { a, b ->
            if (AdvancedSleepStager.hypnogramMetrics(a).tstS >=
                AdvancedSleepStager.hypnogramMetrics(b).tstS
            ) {
                a
            } else {
                b
            }
        }

    private fun assertOnlyFour(stages: List<StageSegment>) {
        val allowed = setOf("wake", "light", "deep", "rem")
        for (s in stages) assertTrue(allowed.contains(s.stage))
    }

    @Test
    fun stillLowHrNightIsOneSessionWithAFourClassHypnogram() {
        val d = build(2, 7, 1)
        val sessions = AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr)
        assertTrue(sessions.isNotEmpty())
        val main = mainOf(sessions)
        assertTrue(main.end - main.start > 6 * 3600)
        assertTrue(main.end - main.start <= 8 * 3600)
        assertOnlyFour(main.stages)
        assertTrue(main.efficiency > 0.85)
        val m = AdvancedSleepStager.hypnogramMetrics(main)
        assertTrue(m.tstS > 0)
        assertTrue(m.tstS <= m.tibS)
        assertEquals(m.tstS / 60.0, m.deepMin + m.remMin + m.lightMin, 1e-6)
    }

    @Test
    fun allActiveCaptureHasNoQualifyingSleep() {
        val rnd = DartRandom(7)
        val grav = ArrayList<GravTs>()
        val hr = ArrayList<HrTs>()
        for (i in 0 until 4 * 3600) {
            grav.add(
                GravTs(
                    i,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1,
                ),
            )
            hr.add(HrTs(i, 75 + rnd.nextDouble() * 10))
        }
        val m = AdvancedSleepStager.mainSleep(grav, hr)
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
    }

    @Test
    fun tooShortStillBlockIsRejected() {
        val d = build(1, 1, 1)
        assertTrue(AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr).isEmpty())
    }

    @Test
    fun sixteenHourCapDropsAnOverLongStillSpan() {
        val d = build(1, 17, 1)
        assertTrue(AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr).isEmpty())
    }

    @Test
    fun aRecordingGapCannotMergeAShortRestIntoTheNextNight() {
        val nextNight = 18 * 3600
        val d = stillBlocks(listOf(0 to 10 * 60, nextNight to 6 * 3600))
        val sessions = AdvancedSleepStager.detectSleep(d.grav, d.hr)
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().start >= nextNight)
        val span = sessions.single().end - sessions.single().start
        assertTrue(span in 5 * 3600..7 * 3600)
    }

    @Test
    fun aSubNinetyMinHoleWithSleepBandEdgeHrStaysOneSession() {
        val d = stillBlocks(listOf(0 to 2 * 3600, 2 * 3600 + 45 * 60 to 6 * 3600))
        val sessions = AdvancedSleepStager.detectSleep(d.grav, d.hr)
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().start < 60)
        assertTrue(sessions.single().end > 8 * 3600 + 40 * 60)
    }

    private fun buildCycled(): Built {
        val rnd = DartRng(42)
        val grav = ArrayList<GravTs>()
        val hr = ArrayList<HrTs>()
        val rr = ArrayList<RrTs>()
        var t = 0
        var rrClockMs = 0.0
        fun emitSecond(bpm: Double) {
            grav.add(GravTs(t, 0.001 * sin(t * 0.01), 0.001, 1.0))
            hr.add(HrTs(t, bpm))
            t++
        }
        fun emitPhase(secs: Int, bpm: Double, breathPeriodFn: (Int) -> Double) {
            val endT = t + secs
            var beatIdx = 0
            while (t < endT) {
                emitSecond(bpm)
                val baseRr = 60000.0 / bpm
                val period = breathPeriodFn(beatIdx)
                val rsa = 25.0 * sin(2 * PI * (rrClockMs / 1000.0) / period)
                var rrMs = baseRr + rsa
                rrClockMs += rrMs
                beatIdx++
                if (rnd.nextDouble() < 0.08) continue
                if (rnd.nextDouble() < 0.02) {
                    rrMs *= if (rnd.nextBool()) 1.8 else 0.55
                }
                rr.add(RrTs(t, rrMs))
            }
        }
        emitPhase(3600, 58.0) { 4.2 }
        repeat(4) {
            emitPhase(25 * 60, 56.0) { 4.2 }
            emitPhase(20 * 60, 49.0) { 4.8 }
            emitPhase(15 * 60, 56.0) { 4.2 }
            emitPhase(20 * 60, 59.0) { 3.0 + 4.0 * rnd.nextDouble() }
            emitPhase(20 * 60, 56.0) { 4.2 }
        }
        return Built(grav, hr, rr)
    }

    private fun assertRealDeepAndRem(method: StagingMethod) {
        val d = buildCycled()
        val sessions = AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr, method = method)
        assertTrue(sessions.isNotEmpty())
        val main = mainOf(sessions)
        val m = AdvancedSleepStager.hypnogramMetrics(main)
        assertTrue(m.deepMin > 0.0)
        assertTrue(m.remMin > 0.0)
        val totalStageMin = m.deepMin + m.remMin + m.lightMin
        assertTrue(m.lightPct < 85.0)
        assertTrue(m.deepMin + m.remMin > totalStageMin * 0.10)
    }

    @Test
    fun v1DetectsRealDeepAndRem() {
        assertRealDeepAndRem(StagingMethod.v1)
    }

    @Test
    fun cardioDetectsRealDeepAndRem() {
        assertRealDeepAndRem(StagingMethod.cardio)
    }

    @Test
    fun v1PathEmitsOnlyTheFourLabels() {
        val d = build(2, 7, 1)
        val v1 = AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr, method = StagingMethod.v1)
        assertTrue(v1.isNotEmpty())
        assertOnlyFour(v1.first().stages)
        val m = AdvancedSleepStager.mainSleep(d.grav, d.hr, rr = d.rr, method = StagingMethod.v1)
        assertTrue(m.present)
        assertTrue(m.note!!.contains("v1"))
    }

    @Test
    fun v2PathEmitsOnlyTheFourLabels() {
        val d = build(2, 7, 1)
        val v2 = AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr, method = StagingMethod.v2)
        assertTrue(v2.isNotEmpty())
        assertOnlyFour(v2.first().stages)
        val m = AdvancedSleepStager.mainSleep(d.grav, d.hr, rr = d.rr, method = StagingMethod.v2)
        assertTrue(m.present)
        assertTrue(m.note!!.contains("v2"))
    }

    @Test
    fun cardioPathEmitsOnlyTheFourLabels() {
        val d = build(2, 7, 1)
        val sessions = AdvancedSleepStager.detectSleep(d.grav, d.hr, rr = d.rr)
        assertTrue(sessions.isNotEmpty())
        assertOnlyFour(sessions.first().stages)
        val m = AdvancedSleepStager.mainSleep(d.grav, d.hr, rr = d.rr)
        assertTrue(m.present)
        assertTrue(m.note!!.contains("cardio"))
    }

    @Test
    fun aasmMetricsFromACraftedHypnogram() {
        val session = SleepSession(
            start = 0,
            end = 1000,
            efficiency = 0.0,
            stages = listOf(
                StageSegment(0, 100, "wake"),
                StageSegment(100, 400, "light"),
                StageSegment(400, 600, "deep"),
                StageSegment(600, 800, "rem"),
                StageSegment(800, 850, "wake"),
                StageSegment(850, 1000, "light"),
            ),
            restingHr = null,
            avgHrv = null,
        )
        val m = AdvancedSleepStager.hypnogramMetrics(session)
        assertEquals(1000, m.tibS)
        assertEquals(850, m.tstS)
        assertEquals(100, m.solS)
        assertEquals(900, m.sptS)
        assertEquals(50, m.wasoS)
        assertEquals(1, m.disturbances)
        assertEquals(500.0, m.remLatencyS, 0.0)
        assertEquals(200.0 / 60.0, m.deepMin, 1e-9)
        assertEquals(200.0 / 60.0, m.remMin, 1e-9)
        assertEquals(850.0 / 1000.0, m.efficiency, 1e-9)
    }
}

/**
 * Dart VM Random, including [nextBool], which is one state step (`nextInt(2) == 0`).
 * [DartRandom] only exposes [DartRandom.nextDouble]; the cycled fixture consumes both.
 */
private class DartRng(seed: Long) {
    private var state: Long = setupSeed(seed)

    init {
        repeat(4) { nextState() }
    }

    fun nextDouble(): Double {
        val hi = nextPowerOfTwo(1 shl 26)
        val lo = nextPowerOfTwo(1 shl 27)
        return (hi * POW2_27 + lo) / POW2_53
    }

    fun nextBool(): Boolean {
        nextState()
        return (state and 1L) == 0L
    }

    private fun nextPowerOfTwo(max: Int): Int {
        nextState()
        return ((state and 0xFFFFFFFFL) and (max - 1).toLong()).toInt()
    }

    private fun nextState() {
        val a = 0xffffda61L
        val stateLo = state and 0xFFFFFFFFL
        val stateHi = state ushr 32
        state = a * stateLo + stateHi
    }

    companion object {
        private const val POW2_27 = 134217728.0
        private const val POW2_53 = 9007199254740992.0

        private fun setupSeed(n0: Long): Long {
            var n = n0
            n = n.inv() + (n shl 21)
            n = n xor (n ushr 24)
            n *= 265
            n = n xor (n ushr 14)
            n *= 21
            n = n xor (n ushr 28)
            n += n shl 31
            if (n == 0L) n = 0x5a17
            return n
        }
    }
}
