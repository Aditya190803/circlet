// Ported from OpenStrap analytics/test/onehz/clinical_test.dart.
// Groups: time-domain HRV, RR over-count, robust nocturnal RMSSD, sleep-session RMSSD.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.DartRandom
import app.circlet.analytics.sleep.nightHrvShape
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrvTimeTest {
    private fun rsaNn(hrBpm: Double, respBrpm: Double, ampMs: Double, beats: Int = 3000): List<Double> {
        val base = 60000.0 / hrBpm
        val f = respBrpm / 60.0
        val out = ArrayList<Double>(beats)
        var tSec = 0.0
        for (i in 0 until beats) {
            val v = base + ampMs * sin(2.0 * PI * f * tSec)
            out.add(v)
            tSec += v / 1000.0
        }
        return out
    }

    private fun beatEnds(nn: List<Double>, t0Ms: Double = 0.0): List<Double> {
        val out = ArrayList<Double>(nn.size)
        var t = t0Ms
        for (v in nn) {
            t += v
            out.add(t)
        }
        return out
    }

    private fun gauss(rnd: DartRandom): Double =
        sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2.0 * PI * rnd.nextDouble())

    private fun floorSec(ms: Double): Int = floor(ms / 1000.0).toInt()

    private fun ceilSec(ms: Double): Int = ceil(ms / 1000.0).toInt()

    private fun slowNight(seed: Int, drift: Double, rsa: Double, jitter: Double = 60.0): Pair<List<Double>, List<Double>> {
        val rnd = DartRandom(seed.toLong())
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        var e0 = 0.0
        while (t < 6 * 3600e3) {
            val base = 60000.0 / (50.0 + drift * sin(2.0 * PI * t / 10800e3))
            val e1 = (rnd.nextDouble() - 0.5) * jitter
            val v = base + rsa * sin(2.0 * PI * 0.3 * t / 1000.0) + e1 - e0
            e0 = e1
            t += v
            rr.add(v)
            ts.add(t)
        }
        return rr to ts
    }

    @Test
    fun rmssdSdnnPnn50OnSteppedSeries() {
        val nn = listOf(800.0, 800.0, 800.0, 900.0, 900.0, 900.0)
        val m = hrvTime(nn)
        val v = m.value!!
        assertEquals(44.72136, v.rmssd!!, 1e-4)
        assertEquals(54.77226, v.sdnn!!, 1e-4)
        assertEquals(20.0, v.pnn50!!, 1e-9)
        assertEquals(6, v.nBeats)
        assertEquals("HIGH", m.tier)
    }

    @Test
    fun absentOnTooFewBeats() {
        assertFalse(hrvTime(listOf(800.0)).present)
    }

    @Test
    fun whiteNoiseRefusesRmssd() {
        val rnd = DartRandom(7)
        val jitter = List(600) { 1000.0 + (rnd.nextDouble() - 0.5) * 120.0 }
        val noisy = hrvTime(jitter)
        assertTrue(noisy.value!!.diffAcf1!! < kNnDiffAcf1Floor)
        assertEquals(-0.5, noisy.value!!.diffAcf1!!, 0.1)
        assertNull(noisy.value!!.rmssd)
        assertNull(noisy.value!!.pnn50)
        assertNotNull(noisy.value!!.sdnn)
        assertTrue(noisy.note!!.contains("rmssd_refused:acf1="))

        val smooth = List(600) { i -> 1000.0 + 40.0 * sin(2.0 * PI * i / 16.0) }
        val clean = hrvTime(smooth)
        assertTrue(clean.value!!.diffAcf1!! > kNnDiffAcf1Floor)
        assertNotNull(clean.value!!.rmssd)
        assertNotNull(clean.value!!.pnn50)
    }

    @Test
    fun slowHeartRsaNearNyquistIsNotJitter() {
        val rnd = DartRandom(3)
        val rr = List(4800) { i ->
            1333.0 + 30.0 * sin(2.0 * PI * i / 2.5) + (rnd.nextDouble() - 0.5) * 10.0
        }
        val ts = beatEnds(rr)
        val h = hrvTime(rr, nnTimesMs = ts)
        assertTrue(h.value!!.diffAcf1!! < kNnDiffAcf1Floor)
        assertNotNull(h.value!!.rmssd)
        assertTrue(nocturnalRmssd(rr, ts).present)
        val ss = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(ts.last()))
        assertTrue(ss.present)
        assertEquals(40.3, ss.value!!, 2.0)
    }

    @Test
    fun jitterOnlyWindowsNeverSupplyTheExemptedHeadline() {
        val rnd = DartRandom(11)
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        for (w in 0 until 50) {
            val rsa = w % 5 < 2
            for (i in 0 until 225) {
                val v = if (rsa) {
                    1333.0 + 60.0 * sin(2.0 * PI * i / 2.5) + (rnd.nextDouble() - 0.5) * 10.0
                } else {
                    1333.0 + (rnd.nextDouble() - 0.5) * 20.0
                }
                t += v
                rr.add(v)
                ts.add(t)
            }
        }
        val n = nocturnalRmssd(rr, ts)
        assertTrue(n.value!! > 70.0)
        val ss = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(t) + 1)
        assertTrue(ss.value!! > 70.0)
    }

    @Test
    fun longJitterStaysRefused() {
        val rnd = DartRandom(5)
        val white = List(4800) { 1000.0 + (rnd.nextDouble() - 0.5) * 120.0 }
        val e = List(4801) { (rnd.nextDouble() - 0.5) * 60.0 }
        val timing = List(4800) { i -> 1333.0 + e[i + 1] - e[i] }
        val alt = List(4800) { i -> 1333.0 + if (i % 2 == 0) 30.0 else -30.0 }
        for (s in listOf(white, timing, alt)) {
            val m = hrvTime(s)
            assertTrue(m.value!!.diffAcf1!! < kNnDiffAcf1Floor)
            assertNull(m.value!!.rmssd)
            assertTrue(m.note!!.contains("rmssd_refused:acf1="))
        }
        val diffs = listOf(List(white.size - 1) { i -> white[i + 1] - white[i] })
        assertNull(nnDiffNoiseShare(diffs))
    }

    @Test
    fun shortBeatTimeJitterCannotPassAsRsa() {
        for (seed in 0 until 400) {
            val rnd = DartRandom(seed.toLong())
            val e = List(681) { 20.0 * gauss(rnd) }
            val rr = List(680) { i -> 1333.0 + e[i + 1] - e[i] }
            assertNull("seed $seed", hrvTime(rr).value!!.rmssd)
        }
    }

    @Test
    fun alternationThatSlipsPhaseCannotPassAsRsa() {
        for (seed in 0 until 100) {
            val rnd = DartRandom(seed.toLong())
            var s = 1.0
            val rr = ArrayList<Double>(1000)
            val ts = ArrayList<Double>(1000)
            var t = 0.0
            for (i in 0 until 1000) {
                if (rnd.nextDouble() >= 0.2) s = -s
                val v = 1333.0 + s * 30.0 + 5.0 * gauss(rnd)
                t += v
                rr.add(v)
                ts.add(t)
            }
            assertNull("seed $seed", hrvTime(rr, nnTimesMs = ts).value!!.rmssd)
            assertFalse("seed $seed", nocturnalRmssd(rr, ts).present)
            assertFalse(
                "seed $seed",
                sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(t)).present,
            )
        }
    }

    @Test
    fun noisierShortRunsCannotDiluteTheNoiseShare() {
        val rnd = DartRandom(11)
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        fun run(len: Int, amp: Double) {
            t += 5000.0
            for (i in 0 until len) {
                val v = 1000.0 + (rnd.nextDouble() - 0.5) * 2.0 * amp
                t += v
                rr.add(v)
                ts.add(t)
            }
        }
        repeat(12) { run(400, 10.0) }
        repeat(150) { run(40, 30.0) }
        val h = hrvTime(rr, nnTimesMs = ts)
        assertTrue(h.value!!.diffAcf1!! < kNnDiffAcf1Floor)
        assertNull(h.value!!.rmssd)
        assertFalse(nocturnalRmssd(rr, ts).present)
    }

    @Test
    fun oneLoudJitterBurstCannotPassAsRsa() {
        var gated = 0
        for (seed in 0 until 200) {
            val rnd = DartRandom(seed.toLong())
            val e = List(3001) { i -> 5.0 * gauss(rnd) * (if (i in 1500 until 1564) 20.0 else 1.0) }
            val rr = ArrayList<Double>(3000)
            val ts = ArrayList<Double>(3000)
            var t = 0.0
            for (i in 1..3000) {
                val v = 1333.0 + e[i] - e[i - 1]
                t += v
                rr.add(v)
                ts.add(t)
            }
            val h = hrvTime(rr, nnTimesMs = ts)
            if (h.value!!.diffAcf1!! >= kNnDiffAcf1Floor) continue
            gated++
            assertNull("seed $seed", h.value!!.rmssd)
            assertFalse("seed $seed", nocturnalRmssd(rr, ts).present)
        }
        assertTrue(gated > 150)
    }

    @Test
    fun runEdgeArtifactsStayRefused() {
        val rnd = DartRandom(13)
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var t = 0.0
        for (r in 0 until 40) {
            t += 5000.0
            for (i in 0 until 100) {
                val v = 1000.0 + (rnd.nextDouble() - 0.5) * 10.0 + if (i == 1) 120.0 else 0.0
                t += v
                rr.add(v)
                ts.add(t)
            }
        }
        val h = hrvTime(rr, nnTimesMs = ts)
        assertTrue(h.value!!.diffAcf1!! < kNnDiffAcf1Floor)
        assertNull(h.value!!.rmssd)
        assertFalse(nocturnalRmssd(rr, ts).present)
        val ss = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(t))
        assertFalse(ss.present)
        assertTrue(ss.note!!.contains("rmssd_refused:acf1="))
    }

    @Test
    fun coarseGridCannotPassAsRsa() {
        for (seed in 0 until 6) {
            val rnd = DartRandom(seed.toLong())
            var t = 0.0
            val tq = ArrayList<Double>(2401)
            for (i in 0..2400) {
                t += 1013.0 + 2.0 * gauss(rnd)
                tq.add(roundHalfAway(t / 40.0) * 40.0)
            }
            val rr = List(tq.size - 1) { i -> tq[i + 1] - tq[i] }
            val ts = tq.subList(1, tq.size)
            val h = hrvTime(rr, nnTimesMs = ts)
            assertTrue("seed $seed", h.value!!.diffAcf1!! < kNnDiffAcf1Floor)
            assertNull("seed $seed", h.value!!.rmssd)
            assertFalse("seed $seed", nocturnalRmssd(rr, ts).present)
            assertFalse(
                "seed $seed",
                sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(ts.last())).present,
            )
        }
        val rnd = DartRandom(3)
        val rr = List(4800) { i ->
            roundHalfAway(
                1333.0 + 30.0 * sin(2.0 * PI * i / 2.5) + (rnd.nextDouble() - 0.5) * 10.0,
            )
        }
        assertNotNull(hrvTime(rr).value!!.rmssd)
    }

    @Test
    fun normalHrRsaIsUntouched() {
        val rnd = DartRandom(9)
        val rr = List(2400) { i ->
            1000.0 + 30.0 * sin(2.0 * PI * i / 4.0) + (rnd.nextDouble() - 0.5) * 10.0
        }
        val m = hrvTime(rr)
        assertTrue(m.value!!.diffAcf1!! > kNnDiffAcf1Floor)
        assertEquals(30.0, m.value!!.rmssd!!, 2.0)
    }

    @Test
    fun slowHeartRsaSteadyInHzPublishes() {
        for (seed in 0 until 2) {
            val (rr, ts) = slowNight(seed, 8.0, 10.0, 20.0)
            val ss = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(ts.last()))
            assertTrue("seed $seed ${ss.note}", ss.present)
            assertTrue(ss.note!!.contains("steady in Hz"))
            assertEquals(0.3, ss.confidence, 0.0)
            assertTrue("seed $seed", nocturnalRmssd(rr, ts).present)
            assertNotNull(hrvTime(rr, nnTimesMs = ts).value!!.rmssd)

            val (fr, ft) = slowNight(seed, 0.0, 10.0)
            assertFalse(
                "seed $seed",
                sleepSessionWindowedRmssd(fr, ft, startSec = 1, endSec = floorSec(ft.last())).present,
            )
            val (lr, lt) = slowNight(seed, 8.0, 10.0)
            assertFalse(
                "seed $seed",
                sleepSessionWindowedRmssd(lr, lt, startSec = 1, endSec = floorSec(lt.last())).present,
            )
            assertFalse("seed $seed", nocturnalRmssd(lr, lt).present)
        }
    }

    @Test
    fun wanderingBreathingPublishesNearTruth() {
        for (seed in 0 until 8) {
            val rnd = DartRandom(seed.toLong())
            val br0 = 16.0 + 3.0 * rnd.nextDouble()
            val brA = 2.0 + rnd.nextDouble()
            val dA = 0.3 + 0.2 * rnd.nextDouble()
            val ph = 6.28 * rnd.nextDouble()
            val rr = ArrayList<Double>()
            val ts = ArrayList<Double>()
            val clean = ArrayList<Double>()
            var t = 0.0
            var phi = 0.0
            var e0 = 0.0
            var br = br0
            var depth = 40.0
            while (t < 6 * 3600e3) {
                val hr = 50.0 + 8.0 * sin(2.0 * PI * t / 10800e3 + ph)
                val c = 60000.0 / hr + depth * sin(phi)
                val e1 = (rnd.nextDouble() - 0.5) * 40.0
                val v = c + e1 - e0
                e0 = e1
                phi += 2.0 * PI * br / 60.0 * c / 1000.0
                if (phi > 2.0 * PI) {
                    phi -= 2.0 * PI
                    val g = gauss(rnd).coerceIn(-2.0, 1.5)
                    br = br0 + brA * g
                    depth = 40.0 * (1.0 + dA * (2.0 * rnd.nextDouble() - 1.0))
                }
                t += v
                clean.add(c)
                rr.add(v)
                ts.add(t)
            }
            var ss = 0.0
            for (i in 1 until clean.size) {
                val d = clean[i] - clean[i - 1]
                ss += d * d
            }
            val truth = sqrt(ss / (clean.size - 1))
            val got = listOf(
                hrvTime(rr, nnTimesMs = ts).value!!.rmssd,
                nocturnalRmssd(rr, ts).value,
                sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(ts.last())).value,
            )
            for (g in got) {
                assertNotNull("seed $seed", g)
                val ratio = g!! / truth
                assertTrue("seed $seed: $g vs $truth", ratio in (1.0 / 1.3)..1.3)
            }
        }
    }

    @Test
    fun beatTimeJitterWithDriftingHrStaysRefused() {
        for (seed in 0 until 4) {
            val (rr, ts) = slowNight(seed, 8.0, 0.0)
            assertNull(hrvTime(rr, nnTimesMs = ts).value!!.rmssd)
            assertFalse("seed $seed", nocturnalRmssd(rr, ts).present)
            assertFalse(
                "seed $seed",
                sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = floorSec(ts.last())).present,
            )
        }
    }

    @Test
    fun confidenceCarriesJitterAndArtifact() {
        val smooth = List(600) { i -> 1000.0 + 40.0 * sin(2.0 * PI * i / 16.0) }
        assertEquals(0.95, hrvTime(smooth).confidence, 1e-9)
        assertEquals(0.85, hrvTime(smooth, artifactFraction = 0.15).confidence, 1e-9)
        val rnd = DartRandom(7)
        val jitter = List(600) { 1000.0 + (rnd.nextDouble() - 0.5) * 120.0 }
        assertEquals(0.3, hrvTime(jitter).confidence, 0.0)
    }

    @Test
    fun successiveDifferencesDoNotSpanADroppedRun() {
        val nn = listOf(800.0, 800.0, 800.0, 900.0, 900.0, 900.0)
        val times = listOf(800.0, 1600.0, 2400.0, 5700.0, 6600.0, 7500.0)
        val seamed = hrvTime(nn).value!!
        assertEquals(100.0 / 5.0, seamed.pnn50!!, 1e-9)
        val clean = hrvTime(nn, nnTimesMs = times).value!!
        assertEquals(0.0, clean.rmssd!!, 0.0)
        assertEquals(0.0, clean.pnn50!!, 0.0)
        assertEquals(seamed.sdnn!!, clean.sdnn!!, 1e-12)
    }

    @Test
    fun duplicatedBeatsReadCoverageTwo() {
        val rr = List(1200) { 1000.0 }
        val ts = List(1200) { i -> 1e12 + (i + 1) * 1000.0 }
        val c = rrCoverage(rr, ts)!!
        assertEquals(1.0, c.coverage, 1e-9)
        assertFalse(c.overCounted)
        assertEquals(1200, c.beats)
        assertEquals(0, c.duplicateBeats)
        val rr2 = ArrayList<Double>(2400)
        val ts2 = ArrayList<Double>(2400)
        for (i in rr.indices) {
            rr2.add(rr[i]); rr2.add(rr[i])
            ts2.add(ts[i]); ts2.add(ts[i])
        }
        val d = rrCoverage(rr2, ts2)!!
        assertEquals(2.0, d.coverage, 1e-9)
        assertTrue(d.overCounted)
        assertEquals(1200, d.duplicateBeats)
        assertEquals(2.0, d.toJson()["rr_coverage"] as Double, 1e-9)
    }

    @Test
    fun shortOrMismatchedCoverageIsNull() {
        val rr = List(500) { 1000.0 }
        val ts = List(500) { i -> (i + 1) * 1000.0 }
        assertNull(rrCoverage(rr, ts))
        assertNull(rrCoverage(listOf(1000.0), listOf(1000.0)))
        assertNull(rrCoverage(rr, ts.subList(1, ts.size)))
    }

    @Test
    fun implausibleIntervalIsCountedNeverSummed() {
        val rr = MutableList(1200) { 1000.0 }
        rr[600] = 65535.0
        val ts = List(1200) { i -> (i + 1) * 1000.0 }
        val c = rrCoverage(rr, ts)!!
        assertEquals(1, c.implausibleBeats)
        assertEquals(1199.0 / 1200.0, c.coverage, 1e-9)
        assertFalse(c.overCounted)
    }

    @Test
    fun implausibleFirstIntervalCannotWidenTheSpan() {
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        for (i in 0 until 1200) {
            val t = 1e12 + (i + 1) * 1000.0
            rr.add(1000.0)
            ts.add(t)
            if (i % 8 == 7) {
                rr.add(1000.0)
                ts.add(t)
            }
        }
        assertEquals(1.125, rrCoverage(rr, ts)!!.coverage, 1e-3)
        assertTrue(rrCoverage(rr, ts)!!.overCounted)
        rr[0] = 65535.0
        val c = rrCoverage(rr, ts)!!
        assertEquals(1, c.implausibleBeats)
        assertTrue("coverage ${c.coverage}", c.overCounted)
    }

    @Test
    fun gappyNightIsNotRefused() {
        val rr = List(1200) { 1000.0 }
        val ts = List(1200) { i -> (i + 1) * 1000.0 + if (i >= 600) 1.2e6 else 0.0 }
        assertEquals(0.5, rrCoverage(rr, ts)!!.coverage, 1e-9)
        val nn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 3600)
        val t0 = beatEnds(nn.subList(0, 1800), t0Ms = 1e12)
        val t1 = beatEnds(nn.subList(1800, nn.size), t0Ms = t0.last() + 1800e3)
        val t = t0 + t1
        val c = rrCoverage(nn, t)!!
        assertTrue(c.coverage < 0.7)
        val h = hrvTime(nn, nnTimesMs = t, coverage = c)
        assertNotNull(h.note, h.value!!.rmssd)
        assertTrue(nocturnalRmssd(nn, t, coverage = c).present)
        val d = sleepSessionRmssdDetail(
            nn,
            t,
            startSec = floorSec(t.first()),
            endSec = ceilSec(t.last()) + 1,
        )
        assertTrue(d.note, d.present)
        assertTrue(d.value!!.rrCoverage!! < 0.7)
    }

    @Test
    fun headlineRefusesAnOverCountedSession() {
        val rr = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 25200)
        val ts = beatEnds(rr, t0Ms = 1e12)
        val startSec = floorSec(ts.first())
        val endSec = ceilSec(ts.last()) + 1
        val ok = sleepSessionRmssdDetail(rr, ts, startSec = startSec, endSec = endSec)
        assertTrue(ok.note, ok.present)
        assertEquals(
            ok.value!!.rmssd,
            sleepSessionWindowedRmssd(rr, ts, startSec = startSec, endSec = endSec).value!!,
            0.0,
        )
        assertEquals(1.0, ok.value!!.rrCoverage!!, 0.01)
        assertTrue(ok.value!!.windows > 80)
        assertEquals(1.0, ok.value!!.toJson()["rr_coverage"] as Double, 0.01)
        val rr2 = ArrayList<Double>()
        val ts2 = ArrayList<Double>()
        for (i in rr.indices) {
            rr2.add(rr[i]); rr2.add(rr[i])
            ts2.add(ts[i]); ts2.add(ts[i])
        }
        val dup = sleepSessionRmssdDetail(rr2, ts2, startSec = startSec, endSec = endSec)
        assertFalse(dup.present)
        assertTrue(dup.note!!.startsWith("rr_overcount:coverage="))
        assertFalse(sleepSessionWindowedRmssd(rr2, ts2, startSec = startSec, endSec = endSec).present)
        val c = rrCoverage(rr2, ts2)!!
        val h = hrvTime(rr2, coverage = c)
        assertNull(h.value!!.rmssd)
        assertNull(h.value!!.pnn50)
        assertNotNull(h.value!!.sdnn)
        assertTrue(h.note!!.startsWith("rr_overcount:coverage="))
        val n = nocturnalRmssd(rr2, ts2, coverage = c)
        assertFalse(n.present)
        assertTrue(n.note!!.startsWith("rr_overcount:coverage="))
    }

    @Test
    fun overCountIsNotRescuedByBreathingFallback() {
        val rnd = DartRandom(0)
        val nn = ArrayList<Double>()
        val t = ArrayList<Double>()
        var ms = 0.0
        var e0 = 0.0
        while (ms < 6 * 3600e3) {
            val base = 60000.0 / (50.0 + 8.0 * sin(2.0 * PI * ms / 10800e3))
            val e1 = (rnd.nextDouble() - 0.5) * 20.0
            val v = base + 10.0 * sin(2.0 * PI * 0.3 * ms / 1000.0) + e1 - e0
            e0 = e1
            ms += v
            nn.add(v)
            t.add(ms)
        }
        val rescued = hrvTime(nn, nnTimesMs = t)
        assertNotNull(rescued.value!!.rmssd)
        assertTrue(rescued.note!!.contains("steady in Hz"))
        val raw2 = ArrayList<Double>()
        val t2 = ArrayList<Double>()
        for (i in nn.indices) {
            raw2.add(nn[i]); raw2.add(nn[i])
            t2.add(t[i]); t2.add(t[i])
        }
        val c = rrCoverage(raw2, t2)!!
        assertTrue(c.overCounted)
        val h = hrvTime(nn, nnTimesMs = t, coverage = c)
        assertNull(h.value!!.rmssd)
        assertNotNull(h.value!!.sdnn)
        assertEquals(0.3, h.confidence, 0.0)
        assertTrue(h.note!!.startsWith("rr_overcount:coverage="))
    }

    @Test
    fun duplicatedStretchIsCaughtPerWindow() {
        val clean = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 14400)
        val ts = beatEnds(clean, t0Ms = 1e12)
        val dupNn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 7200)
        val dupTs = beatEnds(dupNn, t0Ms = ts.last() + 2 * 3600e3)
        val rr = ArrayList<Double>()
        rr.addAll(clean)
        for (v in dupNn) {
            rr.add(v); rr.add(v)
        }
        val t = ArrayList<Double>()
        t.addAll(ts)
        for (x in dupTs) {
            t.add(x); t.add(x)
        }
        val startSec = floorSec(t.first())
        val endSec = ceilSec(t.last()) + 1
        assertFalse(rrCoverage(rr, t)!!.overCounted)
        val honest = sleepSessionRmssdDetail(clean, ts, startSec = startSec, endSec = endSec)
        val d = sleepSessionRmssdDetail(rr, t, startSec = startSec, endSec = endSec)
        assertTrue(d.note, d.present)
        assertTrue(d.value!!.overCountedWindows >= 23)
        assertEquals(d.value!!.overCountedWindows, d.value!!.toJson()["overcounted_windows"])
        val windowDelta = d.value!!.windows - honest.value!!.windows
        assertTrue(windowDelta in 0..1)
        assertEquals(honest.value!!.rmssd, d.value!!.rmssd, 0.02 * honest.value!!.rmssd)
        val onlyRr = ArrayList<Double>()
        val onlyTs = ArrayList<Double>()
        for (i in dupNn.indices) {
            onlyRr.add(dupNn[i]); onlyRr.add(dupNn[i])
            onlyTs.add(dupTs[i]); onlyTs.add(dupTs[i])
        }
        val only = sleepSessionRmssdDetail(
            onlyRr,
            onlyTs,
            startSec = floorSec(dupTs.first()),
            endSec = ceilSec(dupTs.last()) + 1,
        )
        assertFalse(only.present)
        assertTrue(only.note!!.startsWith("rr_overcount"))
    }

    @Test
    fun duplicatedTailInShortLastWindowIsCaught() {
        val clean = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 14400)
        val ts = beatEnds(clean, t0Ms = 1e12)
        val startSec = floorSec(ts.first())
        val tailNn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 120)
        val tailTs = beatEnds(tailNn, t0Ms = (startSec + 72 * 300 + 1) * 1000.0)
        val rr = ArrayList<Double>()
        rr.addAll(clean)
        for (v in tailNn) {
            rr.add(v); rr.add(v)
        }
        val t = ArrayList<Double>()
        t.addAll(ts)
        for (x in tailTs) {
            t.add(x); t.add(x)
        }
        val endSec = ceilSec(t.last()) + 1
        assertTrue(endSec - (startSec + 72 * 300) < 150)
        assertFalse(rrCoverage(rr, t)!!.overCounted)
        val honest = sleepSessionRmssdDetail(clean, ts, startSec = startSec, endSec = endSec)
        val d = sleepSessionRmssdDetail(rr, t, startSec = startSec, endSec = endSec)
        assertTrue(d.note, d.present)
        assertEquals(1, d.value!!.overCountedWindows)
        assertEquals(honest.value!!.windows, d.value!!.windows)
        assertEquals(honest.value!!.rmssd, d.value!!.rmssd, 0.0)
        val once = sleepSessionRmssdDetail(clean + tailNn, ts + tailTs, startSec = startSec, endSec = endSec)
        assertEquals(0, once.value!!.overCountedWindows)
        assertEquals(honest.value!!.windows + 1, once.value!!.windows)
    }

    @Test
    fun coverageDoesNotDependOnArrivalOrder() {
        val rr = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 1200)
        val ts = beatEnds(rr, t0Ms = 1e12)
        val sorted = rrCoverage(rr, ts)!!
        val idx = (rr.indices).toMutableList()
        dartShuffle(idx, DartRandom(1))
        val shuffled = rrCoverage(idx.map { rr[it] }, idx.map { ts[it] })!!
        assertEquals(sorted.coverage, shuffled.coverage, 1e-12)
        assertEquals(sorted.spanSec, shuffled.spanSec, 1e-9)
        val dupRr = ArrayList<Double>()
        val dupTs = ArrayList<Double>()
        for (i in rr.indices) {
            dupRr.add(rr[i]); dupRr.add(rr[i])
            dupTs.add(ts[i]); dupTs.add(ts[i])
        }
        val dIdx = (dupRr.indices).toMutableList()
        dartShuffle(dIdx, DartRandom(2))
        val dupShuffled = rrCoverage(dIdx.map { dupRr[it] }, dIdx.map { dupTs[it] })!!
        assertEquals(1200, rrCoverage(dupRr, dupTs)!!.duplicateBeats)
        assertEquals(1200, dupShuffled.duplicateBeats)
    }

    @Test
    fun nightlyShapeIsAbsentOnAnOverCountedStream() {
        val nn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 25200)
        val t = beatEnds(nn, t0Ms = 1e12)
        assertTrue(nightHrvShape(nn, t).present)
        val dupRr = ArrayList<Double>()
        val dupTs = ArrayList<Double>()
        for (i in nn.indices) {
            dupRr.add(nn[i]); dupRr.add(nn[i])
            dupTs.add(t[i]); dupTs.add(t[i])
        }
        val c = rrCoverage(dupRr, dupTs)!!
        val s = nightHrvShape(nn, t, coverage = c)
        assertFalse(s.present)
        assertTrue(s.note!!.startsWith("rr_overcount:coverage="))
        assertTrue(nightHrvShape(nn, t, coverage = rrCoverage(nn, t)).present)
    }

    @Test
    fun honestCoverageDoesNotChangeTheEstimate() {
        val nn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0)
        val t = beatEnds(nn, t0Ms = 1e12)
        val c = rrCoverage(nn, t)!!
        assertFalse(c.overCounted)
        val a = hrvTime(nn, nnTimesMs = t)
        val b = hrvTime(nn, nnTimesMs = t, coverage = c)
        assertEquals(a.value!!.rmssd!!, b.value!!.rmssd!!, 0.0)
        assertEquals(a.confidence, b.confidence, 0.0)
        assertEquals(a.note, b.note)
        assertEquals(nocturnalRmssd(nn, t).value!!, nocturnalRmssd(nn, t, coverage = c).value!!, 0.0)
    }

    @Test
    fun robustRmssdTracksTheStableLevel() {
        val nn = ArrayList<Double>(2400)
        val times = ArrayList<Double>(2400)
        var t = 0.0
        for (i in 0 until 2400) {
            val win = i / 300
            val inBurst = win == 2 || win == 5
            val v = if (inBurst) {
                1000.0 + 250.0 * sin(2.0 * PI * i / 4.0)
            } else {
                1000.0 + 16.0 * sin(2.0 * PI * i / 12.0)
            }
            nn.add(v)
            t += v
            times.add(t)
        }
        val whole = hrvTime(nn).value!!.rmssd!!
        val robust = nocturnalRmssd(nn, times).value!!
        assertTrue(whole > 100.0)
        assertTrue(robust < 40.0)
        assertTrue(robust < whole / 3.0)
    }

    @Test
    fun stageMaskKeepsOnlyNremWindows() {
        val nn = ArrayList<Double>(1200)
        val times = ArrayList<Double>(1200)
        var t = 0.0
        for (i in 0 until 1200) {
            nn.add(1000.0 + 10.0 * sin(2.0 * PI * i / 12.0))
            t += nn.last()
            times.add(t)
        }
        val mask = MutableList(1300) { true }
        for (s in 0 until 300) mask[s] = false
        val m = nocturnalRmssd(nn, times, stageMaskPerSec = mask)
        assertTrue(m.present)
        assertTrue(m.note!!.contains("PRV not ECG"))
    }

    @Test
    fun nocturnalAbsentWithoutEnoughBeats() {
        assertFalse(nocturnalRmssd(listOf(800.0, 810.0), listOf(800.0, 1610.0)).present)
    }

    @Test
    fun sessionRmssdMatchesTheMeanOfWindows() {
        val rr = ArrayList<Double>()
        val ts = ArrayList<Double>()
        fun addWindow(vals: List<Double>, startTsMs: Double) {
            var beat = startTsMs
            for (v in vals) {
                rr.add(v)
                ts.add(beat)
                beat += 1000.0
            }
        }
        addWindow(listOf(1000.0, 1010.0, 990.0, 1000.0, 1010.0, 990.0), 1000.0)
        addWindow(listOf(1000.0, 1050.0, 950.0, 1000.0, 1050.0, 950.0), 301000.0)
        val m = sleepSessionWindowedRmssd(
            rr,
            ts,
            startSec = 1,
            endSec = 601,
            windowSec = 300,
            minDiffsPerWindow = 1,
        )
        assertTrue(m.present)
        assertEquals((sqrt(220.0) + sqrt(5500.0)) / 2.0, m.value!!, 1e-6)
    }

    @Test
    fun aWindowWithTooFewDifferencesDoesNotCount() {
        val rr = ArrayList<Double>()
        for (i in 0 until 10) rr.add(if (i % 2 == 0) 1000.0 else 1010.0)
        rr.add(900.0)
        rr.add(1060.0)
        val ts = ArrayList<Double>()
        for (i in 0 until 10) ts.add(1000.0 + i * 1000.0)
        ts.add(301000.0)
        ts.add(302000.0)
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 601, minDiffsPerWindow = 5)
        assertTrue(m.present)
        assertEquals(10.0, m.value!!, 1e-9)
    }

    @Test
    fun dropsOutOfRangeAndEctopicBeats() {
        val rr = listOf(1000.0, 1000.0, 1000.0, 1000.0, 200.0, 1000.0, 1000.0, 1000.0)
        val ts = List(8) { i -> 1000.0 + i * 1000.0 }
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 301, minDiffsPerWindow = 1)
        assertTrue(m.present)
        assertEquals(0.0, m.value!!, 1e-9)
    }

    @Test
    fun noDifferenceAcrossARejectedBeat() {
        val rr = listOf(900.0, 900.0, 900.0, 900.0, 200.0, 1000.0, 1000.0, 1000.0)
        val ts = List(8) { i -> 1000.0 + i * 1000.0 }
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 301, minDiffsPerWindow = 1)
        assertTrue(m.present)
        assertEquals(0.0, m.value!!, 1e-9)
    }

    @Test
    fun headlineAbsentOnJitterNight() {
        val rnd = DartRandom(11)
        val rr = List(900) { 1000.0 + (rnd.nextDouble() - 0.5) * 120.0 }
        val ts = List(900) { i -> 1000.0 + i * 1000.0 }
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 1801)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("rmssd_refused:acf1="))
    }

    @Test
    fun noDifferenceAcrossASensorDropout() {
        val rr = listOf(900.0, 900.0, 900.0, 900.0, 1000.0, 1000.0, 1000.0)
        val ts = listOf(1000.0, 2000.0, 3000.0, 4000.0, 123000.0, 124000.0, 125000.0)
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 301, minDiffsPerWindow = 1)
        assertTrue(m.present)
        assertEquals(0.0, m.value!!, 1e-9)
    }

    @Test
    fun thinWindowCannotMoveTheHeadline() {
        val sixStart = 1000000000
        val sixRr = List(1800) { i -> 1000.0 + 16.0 * sin(2.0 * PI * i / 12.0) }
        val sixTs = beatEnds(sixRr, t0Ms = sixStart * 1000.0)
        val extra = List(10) { i -> if (i < 5) 1000.0 else 1300.0 }
        val rr2 = sixRr + extra
        val ts2 = sixTs + beatEnds(extra, t0Ms = (sixStart + 2110) * 1000.0)
        val base = sleepSessionWindowedRmssd(sixRr, sixTs, startSec = sixStart, endSec = sixStart + 1800)
        val thin = sleepSessionWindowedRmssd(rr2, ts2, startSec = sixStart, endSec = sixStart + 2400)
        assertTrue(base.present && thin.present)
        assertEquals(5.86, base.value!!, 0.05)
        assertEquals(base.value!!, thin.value!!, 1e-9)
        val old = sleepSessionWindowedRmssd(
            rr2,
            ts2,
            startSec = sixStart,
            endSec = sixStart + 2400,
            minDiffsPerWindow = 5,
        )
        assertTrue(old.note, old.present)
        assertEquals((6 * base.value!! + 100.0) / 7.0, old.value!!, 0.01)
        val d = sleepSessionRmssdDetail(rr2, ts2, startSec = sixStart, endSec = sixStart + 2400)
        assertEquals(6, d.value!!.windows)
        assertEquals(2, d.value!!.thinWindows)
        assertEquals(kMinDiffsPerRmssdWindow, d.value!!.minDiffsPerWindow)
        assertEquals(2, d.value!!.toJson()["thin_windows"])
        assertEquals(20, d.value!!.toJson()["min_diffs_per_window"])
        assertTrue(d.note!!.contains("2 window(s) had fewer than 20"))
    }

    @Test
    fun discardedThinWindowDoesNotReachAcf1() {
        val sixStart = 1000000000
        val sixRr = List(1800) { i -> 1000.0 + 16.0 * sin(2.0 * PI * i / 12.0) }
        val sixTs = beatEnds(sixRr, t0Ms = sixStart * 1000.0)
        val extra = List(20) { i -> if (i % 2 == 0) 910.0 else 1090.0 }
        val rr2 = sixRr + extra
        val ts2 = sixTs + beatEnds(extra, t0Ms = (sixStart + 2110) * 1000.0)
        val base = sleepSessionRmssdDetail(sixRr, sixTs, startSec = sixStart, endSec = sixStart + 2400)
        val thin = sleepSessionRmssdDetail(rr2, ts2, startSec = sixStart, endSec = sixStart + 2400)
        assertTrue(thin.note, base.present && thin.present)
        assertEquals(base.value!!.thinWindows + 1, thin.value!!.thinWindows)
        assertEquals(base.value!!.windows, thin.value!!.windows)
        assertEquals(base.value!!.rmssd, thin.value!!.rmssd, 1e-9)
        assertEquals(0.863, thin.value!!.diffAcf1!!, 0.01)
        assertEquals(base.value!!.diffAcf1!!, thin.value!!.diffAcf1!!, 1e-12)
        assertEquals(base.confidence, thin.confidence, 0.0)
    }

    @Test
    fun nineteenDifferencesDoNotCountTwentyDo() {
        fun window(beats: Int): Pair<List<Double>, List<Double>> {
            val rr = List(beats) { i -> 1000.0 + 16.0 * sin(2.0 * PI * i / 12.0) }
            return rr to beatEnds(rr, t0Ms = 1000.0)
        }
        val (aRr, aTs) = window(21)
        val ma = sleepSessionRmssdDetail(aRr, aTs, startSec = 1, endSec = 301)
        assertTrue(ma.note, ma.present)
        assertEquals(1, ma.value!!.windows)
        assertEquals(0, ma.value!!.thinWindows)
        val (bRr, bTs) = window(20)
        val mb = sleepSessionWindowedRmssd(bRr, bTs, startSec = 1, endSec = 301)
        assertFalse(mb.present)
        assertTrue(mb.note!!.startsWith("no valid 5-min windows"))
        assertTrue(mb.note!!.contains("fewer than 20"))
    }

    @Test
    fun floorIsAParameter() {
        val rr = List(20) { i -> 1000.0 + 16.0 * sin(2.0 * PI * i / 12.0) }
        val m = sleepSessionWindowedRmssd(
            rr,
            beatEnds(rr, t0Ms = 1000.0),
            startSec = 1,
            endSec = 301,
            minDiffsPerWindow = 19,
        )
        assertTrue(m.present)
    }

    @Test
    fun minDiffsConstantIsTwenty() {
        assertEquals(20, kMinDiffsPerRmssdWindow)
    }

    @Test
    fun noGapMeansNoChange() {
        val rr = listOf(900.0, 900.0, 900.0, 1000.0, 1000.0, 1000.0)
        val ts = listOf(1000.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0)
        val m = sleepSessionWindowedRmssd(rr, ts, startSec = 1, endSec = 301, minDiffsPerWindow = 1)
        assertTrue(m.present)
        assertEquals(sqrt(100.0 * 100.0 / 5.0), m.value!!, 1e-6)
    }
}

private fun roundHalfAway(x: Double): Double {
    val sign = if (x < 0.0) -1.0 else 1.0
    return sign * floor(kotlin.math.abs(x) + 0.5)
}

private fun <T> dartShuffle(list: MutableList<T>, random: DartRandom) {
    var length = list.size
    while (length > 1) {
        val pos = random.nextInt(length)
        length -= 1
        val tmp = list[length]
        list[length] = list[pos]
        list[pos] = tmp
    }
}
