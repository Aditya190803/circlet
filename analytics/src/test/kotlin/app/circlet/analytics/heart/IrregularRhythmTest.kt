package app.circlet.analytics.heart

import app.circlet.analytics.foundations.Tier
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrregularRhythmTest {
    @Test
    fun organisedSinusDoesNotFlag() {
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) rr.add(1000.0 + 15.0 * sin(i / 8.0))
        val m = irregularBeatScreen(rr)
        assertTrue(m.present)
        assertEquals(Tier.ESTIMATE, m.tier)
        assertFalse(m.value!!.flag)
        assertTrue(m.value!!.sd1sd2 < 0.70)
    }

    @Test
    fun irregularRhythmFlags() {
        val rnd = HeartDartRandom(7)
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) {
            rr.add(800.0 + (if (rnd.nextBool()) 250.0 else -50.0) + rnd.nextInt(120))
        }
        val m = irregularBeatScreen(rr)
        assertTrue(m.present)
        assertTrue(m.value!!.flag)
        assertTrue(m.value!!.pnnPct >= 30.0)
    }

    @Test
    fun tooFewBeatsAbsent() {
        val rr = ArrayList<Double>(100)
        for (i in 0 until 100) rr.add(1000.0)
        assertFalse(irregularBeatScreen(rr).present)
    }

    @Test
    fun highArtifactSuppressesScreen() {
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) rr.add(1000.0)
        val m = irregularBeatScreen(rr, artifactFraction = 0.5)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("artifact"))
    }

    @Test
    fun scatterInTwoOfTwelveWindowsDoesNotFlagWithTimes() {
        val rnd = HeartDartRandom(7)
        fun organised(n: Int): List<Double> {
            val out = ArrayList<Double>(n)
            for (i in 0 until n) out.add(900.0 + 15.0 * sin(i / 8.0))
            return out
        }
        fun scattered(n: Int): List<Double> {
            val out = ArrayList<Double>(n)
            for (i in 0 until n) {
                out.add(800.0 + (if (rnd.nextDouble() < 0.5) 250.0 else -50.0) + rnd.nextInt(120))
            }
            return out
        }
        val orgBlocks = ArrayList<List<Double>>()
        for (i in 0 until 10) orgBlocks.add(organised(300))
        val scatBlocks = listOf(scattered(1800), scattered(1800))
        val blocks = ArrayList<List<Double>>()
        blocks.addAll(orgBlocks.subList(0, 5))
        blocks.add(scatBlocks[0])
        blocks.addAll(orgBlocks.subList(5, orgBlocks.size))
        blocks.add(scatBlocks[1])
        val rr = ArrayList<Double>()
        for (b in blocks) rr.addAll(b)
        val windowMs = 5 * 60000.0
        val times = ArrayList<Double>()
        var windowStart = 0.0
        for (b in blocks) {
            val step = min(b.min(), windowMs * 0.999 / b.size)
            for (i in b.indices) times.add(windowStart + i * step)
            windowStart += windowMs
        }
        val withoutTimes = irregularBeatScreen(rr)
        assertTrue(withoutTimes.value!!.flag)
        assertTrue(withoutTimes.value!!.pnnPct >= 30.0)
        val withTimes = irregularBeatScreen(rr, nnTimesMs = times)
        assertFalse(withTimes.value!!.flag)
    }

    @Test
    fun outOfRangeArtifactsDoNotFakeWindowFlag() {
        val organised = ArrayList<Double>(3600)
        for (i in 0 until 3600) {
            organised.add(if (i % 6 == 5) 5000.0 else 900.0 + 15.0 * sin(i / 8.0))
        }
        val times = ArrayList<Double>(organised.size)
        var t = 0.0
        for (v in organised) {
            t += v
            times.add(t)
        }
        assertFalse(irregularBeatScreen(organised, nnTimesMs = times).value!!.flag)
    }

    @Test
    fun beatsEitherSideOfATimeHoleAreNotDifferenced() {
        val rr = ArrayList<Double>()
        val times = ArrayList<Double>()
        var t = 0.0
        for (s in 0 until 40) {
            val base = if (s % 2 == 0) 1000.0 else 600.0
            t += 60000.0
            for (i in 0 until 60) {
                val v = base + 5.0 * sin(i / 3.0)
                t += v
                rr.add(v)
                times.add(t)
            }
        }
        val m = irregularBeatScreen(rr, nnTimesMs = times)
        assertTrue(m.present)
        assertEquals(0.0, m.value!!.pnnPct, 0.0)
        assertTrue(m.value!!.sd1 < 10.0)
        assertFalse(m.value!!.flag)
    }

    private fun fragmented(rr: MutableList<Double>, times: MutableList<Double>, start: Double) {
        var t = start
        for (i in 0 until 42) {
            val v = when (i) {
                1 -> 975.0
                0, 2 -> 900.0
                else -> 840.0 + (i * 37) % 196
            }
            t += if (i <= 2) v else v + 3000.0
            rr.add(v)
            times.add(t)
        }
    }

    @Test
    fun beatsSplitByHolesAreNotAWindow() {
        val rr = ArrayList<Double>()
        val times = ArrayList<Double>()
        for (w in 0 until 15) fragmented(rr, times, w * 300000.0)
        assertFalse(irregularBeatScreen(rr, nnTimesMs = times).present)
    }

    @Test
    fun fragmentedWindowsDoNotVote() {
        val rnd = HeartDartRandom(3)
        val rr = ArrayList<Double>()
        val times = ArrayList<Double>()
        var slot = 0
        fun contiguous(next: () -> Double) {
            var t = slot++ * 300000.0
            while (t < slot * 300000.0 - 2000.0) {
                val v = next()
                t += v
                rr.add(v)
                times.add(t)
            }
        }
        for (w in 0 until 3) contiguous { 500.0 + rnd.nextInt(600) }
        for (w in 0 until 4) contiguous { 800.0 + rnd.nextInt(10) }
        for (w in 0 until 6) fragmented(rr, times, slot++ * 300000.0)
        val m = irregularBeatScreen(rr, nnTimesMs = times)
        assertTrue(m.present)
        assertFalse(m.value!!.flag)
    }

    @Test
    fun mismatchedTimesFallBackToWholeSpan() {
        val rnd = HeartDartRandom(7)
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) {
            rr.add(800.0 + (if (rnd.nextBool()) 250.0 else -50.0) + rnd.nextInt(120))
        }
        val m = irregularBeatScreen(rr, nnTimesMs = listOf(1.0, 2.0, 3.0))
        assertTrue(m.value!!.flag)
    }

    @Test
    fun sustainedScatterStillFlags() {
        val rnd = HeartDartRandom(7)
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) {
            rr.add(800.0 + (if (rnd.nextBool()) 250.0 else -50.0) + rnd.nextInt(120))
        }
        val times = ArrayList<Double>(rr.size)
        var t = 0.0
        for (v in rr) {
            t += v
            times.add(t)
        }
        assertTrue(irregularBeatScreen(rr, nnTimesMs = times).value!!.flag)
    }
}
