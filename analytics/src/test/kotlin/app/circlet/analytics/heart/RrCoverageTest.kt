package app.circlet.analytics.heart

import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RrCoverageTest {
    private fun floorSec(ms: Double): Int = floor(ms / 1000.0).toInt()
    private fun ceilSec(ms: Double): Int = ceil(ms / 1000.0).toInt()

    @Test
    fun oneBeatPerSecondReadsOneDuplicatedReadsTwo() {
        val rr = ArrayList<Double>(1200)
        val ts = ArrayList<Double>(1200)
        for (i in 0 until 1200) {
            rr.add(1000.0)
            ts.add(1e12 + (i + 1) * 1000.0)
        }
        val c = rrCoverage(rr, ts)!!
        assertEquals(1.0, c.coverage, 1e-9)
        assertFalse(c.overCounted)
        assertEquals(1200, c.beats)
        assertEquals(0, c.duplicateBeats)
        val d = rrCoverage(duplicateEach(rr), duplicateEach(ts))!!
        assertEquals(2.0, d.coverage, 1e-9)
        assertTrue(d.overCounted)
        assertEquals(1200, d.duplicateBeats)
        assertEquals(2.0, d.toJson()["rr_coverage"] as Double, 1e-9)
    }

    @Test
    fun underTenMinMismatchedOrTooFewIsNull() {
        val rr = ArrayList<Double>(500)
        val ts = ArrayList<Double>(500)
        for (i in 0 until 500) {
            rr.add(1000.0)
            ts.add((i + 1) * 1000.0)
        }
        assertNull(rrCoverage(rr, ts))
        assertNull(rrCoverage(listOf(1000.0), listOf(1000.0)))
        assertNull(rrCoverage(rr, ts.subList(1, ts.size)))
    }

    @Test
    fun implausibleIntervalIsCountedNeverSummed() {
        val rr = ArrayList<Double>(1200)
        for (i in 0 until 1200) rr.add(1000.0)
        rr[600] = 65535.0
        val ts = ArrayList<Double>(1200)
        for (i in 0 until 1200) ts.add((i + 1) * 1000.0)
        val c = rrCoverage(rr, ts)!!
        assertEquals(1, c.implausibleBeats)
        assertEquals(1199.0 / 1200.0, c.coverage, 1e-9)
        assertFalse(c.overCounted)
    }

    @Test
    fun implausibleFirstIntervalCannotWidenSpan() {
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
    fun gappyNightReadsUnderOne() {
        val rr = ArrayList<Double>(1200)
        val ts = ArrayList<Double>(1200)
        for (i in 0 until 1200) {
            rr.add(1000.0)
            ts.add((i + 1) * 1000.0 + if (i >= 600) 1.2e6 else 0.0)
        }
        assertEquals(0.5, rrCoverage(rr, ts)!!.coverage, 1e-9)
        val nn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 3600)
        val t0 = beatEnds(nn.subList(0, 1800), t0Ms = 1e12)
        val t1 = beatEnds(nn.subList(1800, nn.size), t0Ms = t0.last() + 1800e3)
        val t = ArrayList<Double>(t0.size + t1.size)
        t.addAll(t0)
        t.addAll(t1)
        val c = rrCoverage(nn, t)!!
        assertTrue(c.coverage < 0.7)
        val h = hrvTime(nn, nnTimesMs = t, coverage = c)
        assertNotNull(h.note, h.value!!.rmssd)
        val n = nocturnalRmssd(nn, t, coverage = c)
        assertTrue(n.note, n.present)
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
    fun headlineRefusesOverCountedSession() {
        val rr = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 25200)
        val ts = beatEnds(rr, t0Ms = 1e12)
        val startSec = floorSec(ts.first())
        val endSec = ceilSec(ts.last()) + 1
        val ok = sleepSessionRmssdDetail(rr, ts, startSec = startSec, endSec = endSec)
        assertTrue(ok.note, ok.present)
        assertEquals(
            sleepSessionWindowedRmssd(rr, ts, startSec = startSec, endSec = endSec).value!!,
            ok.value!!.rmssd,
            0.0,
        )
        assertEquals(1.0, ok.value!!.rrCoverage!!, 0.01)
        assertTrue(ok.value!!.windows > 80)
        assertEquals(1.0, ok.value!!.toJson()["rr_coverage"] as Double, 0.01)
        val rr2 = duplicateEach(rr)
        val ts2 = duplicateEach(ts)
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
        val rnd = HeartDartRandom(0)
        val nn = ArrayList<Double>()
        val t = ArrayList<Double>()
        var ms = 0.0
        var e0 = 0.0
        while (ms < 6 * 3600e3) {
            val base = 60000.0 / (50.0 + 8.0 * kotlin.math.sin(2.0 * kotlin.math.PI * ms / 10800e3))
            val e1 = (rnd.nextDouble() - 0.5) * 20.0
            val v = base + 10.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 0.3 * ms / 1000.0) + e1 - e0
            e0 = e1
            ms += v
            nn.add(v)
            t.add(ms)
        }
        val rescued = hrvTime(nn, nnTimesMs = t)
        assertNotNull(rescued.value!!.rmssd)
        assertTrue(rescued.note!!.contains("steady in Hz"))
        val c = rrCoverage(duplicateEach(nn), duplicateEach(t))!!
        assertTrue(c.overCounted)
        val h = hrvTime(nn, nnTimesMs = t, coverage = c)
        assertNull(h.value!!.rmssd)
        assertNotNull(h.value!!.sdnn)
        assertEquals(0.3, h.confidence, 0.0)
        assertTrue(h.note!!.startsWith("rr_overcount:coverage="))
    }

    @Test
    fun duplicatedStretchCaughtPerWindow() {
        val clean = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 14400)
        val ts = beatEnds(clean, t0Ms = 1e12)
        val dupNn = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 7200)
        val dupTs = beatEnds(dupNn, t0Ms = ts.last() + 2 * 3600e3)
        val rr = ArrayList<Double>()
        rr.addAll(clean)
        rr.addAll(duplicateEach(dupNn))
        val t = ArrayList<Double>()
        t.addAll(ts)
        t.addAll(duplicateEach(dupTs))
        val startSec = floorSec(t.first())
        val endSec = ceilSec(t.last()) + 1
        assertFalse(rrCoverage(rr, t)!!.overCounted)
        val honest = sleepSessionRmssdDetail(clean, ts, startSec = startSec, endSec = endSec)
        val d = sleepSessionRmssdDetail(rr, t, startSec = startSec, endSec = endSec)
        assertTrue(d.note, d.present)
        assertTrue(d.value!!.overCountedWindows >= 23)
        assertEquals(d.value!!.overCountedWindows, d.value!!.toJson()["overcounted_windows"])
        val delta = d.value!!.windows - honest.value!!.windows
        assertTrue(delta in 0..1)
        assertEquals(honest.value!!.rmssd, d.value!!.rmssd, 0.02 * honest.value!!.rmssd)
        val only = sleepSessionRmssdDetail(
            duplicateEach(dupNn),
            duplicateEach(dupTs),
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
        rr.addAll(duplicateEach(tailNn))
        val t = ArrayList<Double>()
        t.addAll(ts)
        t.addAll(duplicateEach(tailTs))
        val endSec = ceilSec(t.last()) + 1
        assertTrue(endSec - (startSec + 72 * 300) < 150)
        assertFalse(rrCoverage(rr, t)!!.overCounted)
        val honest = sleepSessionRmssdDetail(clean, ts, startSec = startSec, endSec = endSec)
        val d = sleepSessionRmssdDetail(rr, t, startSec = startSec, endSec = endSec)
        assertTrue(d.note, d.present)
        assertEquals(1, d.value!!.overCountedWindows)
        assertEquals(honest.value!!.windows, d.value!!.windows)
        assertEquals(honest.value!!.rmssd, d.value!!.rmssd, 0.0)
        val onceRr = ArrayList<Double>()
        onceRr.addAll(clean)
        onceRr.addAll(tailNn)
        val onceTs = ArrayList<Double>()
        onceTs.addAll(ts)
        onceTs.addAll(tailTs)
        val once = sleepSessionRmssdDetail(onceRr, onceTs, startSec = startSec, endSec = endSec)
        assertEquals(0, once.value!!.overCountedWindows)
        assertEquals(honest.value!!.windows + 1, once.value!!.windows)
    }

    @Test
    fun coverageIsOrderIndependent() {
        val rr = rsaNn(hrBpm = 60.0, respBrpm = 12.0, ampMs = 30.0, beats = 1200)
        val ts = beatEnds(rr, t0Ms = 1e12)
        val sorted = rrCoverage(rr, ts)!!
        val idx = (0 until rr.size).toMutableList()
        idx.dartShuffle(HeartDartRandom(1))
        val shRr = ArrayList<Double>(idx.size)
        val shTs = ArrayList<Double>(idx.size)
        for (i in idx) {
            shRr.add(rr[i])
            shTs.add(ts[i])
        }
        val shuffled = rrCoverage(shRr, shTs)!!
        assertEquals(sorted.coverage, shuffled.coverage, 1e-12)
        assertEquals(sorted.spanSec, shuffled.spanSec, 1e-9)
        val dupRr = duplicateEach(rr)
        val dupTs = duplicateEach(ts)
        val dIdx = (0 until dupRr.size).toMutableList()
        dIdx.dartShuffle(HeartDartRandom(2))
        val dRr = ArrayList<Double>(dIdx.size)
        val dTs = ArrayList<Double>(dIdx.size)
        for (i in dIdx) {
            dRr.add(dupRr[i])
            dTs.add(dupTs[i])
        }
        val dupShuffled = rrCoverage(dRr, dTs)!!
        assertEquals(1200, rrCoverage(dupRr, dupTs)!!.duplicateBeats)
        assertEquals(1200, dupShuffled.duplicateBeats)
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
}
