package app.circlet.analytics.foundations

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationsTest {
    private val cfg = Baselines.hrvCfg

    @Test
    fun dartRandomMatchesSeedEleven() {
        val rnd = DartRandom(11)
        assertClose(0.53821782158029874, rnd.nextDouble(), 1e-15)
        assertClose(0.38413705347145655, rnd.nextDouble(), 1e-15)
        assertClose(0.58710260072647780, rnd.nextDouble(), 1e-15)
        assertClose(0.21224228342606477, rnd.nextDouble(), 1e-15)
        assertClose(0.14250347477218395, rnd.nextDouble(), 1e-15)
    }

    @Test
    fun firstValidNightSeedsAtTheValue() {
        val s = Baselines.update(null, 60.0, cfg)!!
        assertEquals(60.0, s.baseline, 0.0)
        assertEquals(cfg.floorSpread, s.spread, 0.0)
        assertEquals(1, s.nValid)
        assertEquals(BaselineStatus.CALIBRATING, s.status)
    }

    @Test
    fun outOfRangeFirstNightYieldsNoBaseline() {
        assertNull(Baselines.update(null, 999.0, cfg))
        assertNull(Baselines.update(null, null, cfg))
    }

    @Test
    fun foldHistoryOverNoUsableNightIsAbsent() {
        assertNull(Baselines.foldHistory(emptyList(), cfg))
        assertNull(Baselines.foldHistory(listOf(null, null), cfg))
        val s = Baselines.foldHistory(listOf(null, 52.0), cfg)!!
        assertEquals(52.0, s.baseline, 0.0)
        assertEquals(1, s.nValid)
    }

    @Test
    fun deviationAbstainsOnAZeroNightState() {
        val empty = BaselineState(
            baseline = 127.5,
            spread = 5.0,
            nValid = 0,
            nightsSinceUpdate = 0,
            status = BaselineStatus.CALIBRATING,
        )
        assertNull(Baselines.deviation(45.0, empty))
    }

    @Test
    fun hardOutlierPastEarlyLifeIsSeenButNotFolded() {
        val settled = Baselines.foldHistory(List<Double?>(10) { 60.0 }, cfg)!!
        assertEquals(10, settled.nValid)
        assertClose(60.0, settled.baseline, 1e-9)
        val after = Baselines.update(settled, 60.0 + 6 * cfg.floorSpread, cfg)!!
        assertEquals(settled.baseline, after.baseline, 0.0)
        assertEquals(settled.nValid, after.nValid)
        assertEquals(settled.nightsSinceUpdate + 1, after.nightsSinceUpdate)
        val outOfRange = Baselines.update(settled, 999.0, cfg)!!
        assertEquals(after.nightsSinceUpdate, outOfRange.nightsSinceUpdate)
    }

    @Test
    fun spreadDeviationIsMeasuredAgainstTheOldBaseline() {
        val settled = Baselines.foldHistory(List<Double?>(20) { 60.0 }, cfg)!!
        val after = Baselines.update(settled, 60.0 + 10.0, cfg)!!
        val ls = Baselines.lambda(cfg.halfLifeS)
        val want = max(cfg.floorSpread, ls * 10.0 + (1.0 - ls) * settled.spread)
        assertClose(want, after.spread, 1e-9)
        val lb = Baselines.lambda(cfg.halfLifeB)
        val shrunk = max(
            cfg.floorSpread,
            ls * ((1 - lb) * 10.0) + (1.0 - ls) * settled.spread,
        )
        assertTrue(after.spread > shrunk)
        assertClose(0.9517, 1 - lb, 1e-4)
    }

    @Test
    fun earlyLifeFastAdaptPullsAHighSeedDown() {
        var s = Baselines.update(null, 90.0, cfg)
        repeat(4) { s = Baselines.update(s, 55.0, cfg) }
        assertTrue(s!!.baseline < 80.0)
        assertTrue(s.baseline > 55.0)
    }

    @Test
    fun deviationZUsesScaledSpread() {
        val s = BaselineState(
            baseline = 60.0,
            spread = 5.0,
            nValid = 14,
            nightsSinceUpdate = 0,
            status = BaselineStatus.TRUSTED,
        )
        val d = Baselines.deviation(60.0 + 1.253 * 5.0, s)!!
        assertClose(1.0, d.z, 1e-9)
        assertClose(1.253 * 5.0, d.delta, 1e-9)
        assertTrue(Baselines.deviation(62.0, s)!!.inNormalRange)
        val d2 = Baselines.deviation(60.0 + 2 * 1.253 * 5.0, s)!!
        assertTrue(!d2.inNormalRange)
    }

    @Test
    fun statusThresholds() {
        assertEquals(BaselineStatus.CALIBRATING, Baselines.computeStatus(3, 0))
        assertEquals(BaselineStatus.PROVISIONAL, Baselines.computeStatus(4, 0))
        assertEquals(BaselineStatus.TRUSTED, Baselines.computeStatus(14, 0))
        assertEquals(BaselineStatus.STALE, Baselines.computeStatus(14, 15))
    }

    @Test
    fun skipAndHoldOnANullNight() {
        val seeded = Baselines.update(null, 60.0, cfg)!!
        val held = Baselines.update(seeded, null, cfg)!!
        assertEquals(seeded.baseline, held.baseline, 0.0)
        assertEquals(seeded.nValid, held.nValid)
        assertEquals(1, held.nightsSinceUpdate)
    }

    @Test
    fun cleanPhysiologicalSeriesClassifiesAllNormal() {
        val rr = List(60) { i -> 1000.0 + if (i % 2 == 0) 15.0 else -15.0 }
        val r = correctRr(rr)
        assertClose(1.0, r.cleanFraction, 1e-9)
        assertEquals(0, r.droppedCount)
        assertEquals(0, r.correctedCount)
        assertEquals(60, r.nn.size)
        for (i in rr.indices) assertClose(rr[i], r.nn[i], 1e-12)
    }

    @Test
    fun cleanRsaRecordIsNotFlagged() {
        val rr = cleanRsa()
        var maxAbsDrr = 0.0
        for (i in 1 until rr.size) maxAbsDrr = max(maxAbsDrr, abs(rr[i] - rr[i - 1]))
        assertTrue("max |dRR| was $maxAbsDrr", maxAbsDrr < 100.0)
        val r = correctRr(rr)
        assertEquals(1.0, r.cleanFraction, 0.0)
        assertEquals(0, r.correctedCount)
        assertEquals(0, r.droppedCount)
        assertEquals(400, r.nn.size)
        assertClose(rmssd(rr), rmssd(r.nn), 1e-9)
        assertClose(sdnn(rr), sdnn(r.nn), 1e-9)
    }

    @Test
    fun aGenuineGrossOutlierIsStillCaught() {
        val rr = cleanRsa().toMutableList()
        rr[200] = 350.0
        val r = correctRr(rr)
        assertTrue(r.classes[200] != BeatClass.NORMAL)
        assertTrue(r.cleanFraction < 1.0)
    }

    @Test
    fun flagsExactlyOneInjectedIsolatedEctopic() {
        val rr = MutableList(60) { 1000.0 }
        rr[30] = 500.0
        val r = correctRr(rr)
        val flagged = r.classes.indices.filter { r.classes[it] != BeatClass.NORMAL }
        assertEquals(listOf(30), flagged)
        assertEquals(1, r.correctedCount)
        assertEquals(0, r.droppedCount)
        assertEquals(60, r.nn.size)
        assertTrue(r.nn[30] > 800.0)
    }

    @Test
    fun multiBeatRunIsDropped() {
        val rr = MutableList(60) { 1000.0 }
        rr[30] = 400.0
        rr[31] = 420.0
        val r = correctRr(rr)
        assertEquals(0, r.correctedCount)
        assertTrue(r.droppedCount >= 2)
        assertTrue(r.nn.size < 60)
    }

    @Test
    fun robustBaselineFlagsAnOutlierAndGuardsZeroMad() {
        val b = robustBaseline(listOf(10.0, 11.0, 9.0, 10.0, 12.0, 8.0, 10.0))
        assertEquals(10.0, b.center!!, 0.0)
        assertTrue(b.sufficient)
        assertEquals(true, b.isOutlier(100.0))
        assertEquals(false, b.isOutlier(10.0))

        val flat = robustBaseline(listOf(5.0, 5.0, 5.0, 5.0, 5.0))
        assertEquals(0.0, flat.scale!!, 0.0)
        assertNull(flat.modZ(9.0))
        assertNull(flat.isOutlier(9.0))
    }

    @Test
    fun robustBaselineMinimumCount() {
        assertTrue(!robustBaseline(listOf(1.0, 2.0), minValid = 3).sufficient)
        assertTrue(robustBaseline(listOf(1.0, 2.0, 3.0), minValid = 3).sufficient)
    }

    @Test
    fun mdcIsAbsentWithoutDispersion() {
        val b = robustBaseline(listOf(10.0, 11.0, 9.0, 10.0, 12.0, 8.0, 10.0, 11.0, 9.0, 10.0))
        val change = mdc(b)!!
        assertTrue(change > 0.1)
        assertTrue(change < 50.0)
        assertNull(mdc(robustBaseline(listOf(5.0, 5.0, 5.0))))
    }

    @Test
    fun dispersionBelowQuantum() {
        assertTrue(dispersionBelowQuantum(listOf(58.0, 58.0, 59.0), 1.0))
        assertTrue(!dispersionBelowQuantum(listOf(58.0, 59.0, 58.0, 60.0, 57.0), 1.0))
        assertTrue(!dispersionBelowQuantum(listOf(0.0, 1.0, 2.0), 1.0))
        assertTrue(!dispersionBelowQuantum(listOf(58.0, 58.0, 59.0), 0.0))
        assertTrue(!dispersionBelowQuantum(emptyList(), 0.0))
        assertTrue(dispersionBelowQuantum(emptyList(), 1.0))
        assertTrue(
            !dispersionBelowQuantum(
                listOf(52.1, 52.4, 51.8, 52.9, 52.3, 51.6, 52.0),
                1.0,
            ),
        )
        val mixed = listOf(
            58.0, 58.0, 58.0, 58.0, 58.0, 58.0, 58.0, 58.0, 59.0, 59.0, 58.0, 58.0, 58.0, 58.3,
        )
        assertTrue(dispersionBelowQuantum(mixed, 1.0))
    }

    @Test
    fun inverseVarianceFusion() {
        val fused = inverseVarianceFuse(
            listOf(
                FusionInput(100.0, 4.0, label = "a"),
                FusionInput(110.0, 4.0, label = "b"),
            ),
        )
        assertClose(105.0, fused.value!!, 1e-9)
        assertClose(2.0, fused.variance!!, 1e-9)
        assertEquals(listOf("a", "b"), fused.used)

        val gated = inverseVarianceFuse(
            listOf(
                FusionInput(100.0, 1.0, label = "good"),
                FusionInput(50.0, 1.0, trusted = false, label = "artifact"),
            ),
        )
        assertEquals(100.0, gated.value!!, 0.0)
        assertEquals(listOf("artifact"), gated.dropped)

        val absent = inverseVarianceFuse(
            listOf(FusionInput(1.0, 1.0, trusted = false, label = "x")),
        )
        assertNull(absent.value)
    }

    @Test
    fun aDroppedRunStillAdvancesTheClock() {
        val rr = MutableList(300) { 1000.0 }
        for (k in 100 until 105) rr[k] = 250.0
        val real = rr.sum()
        val c = correctRr(rr)
        assertTrue(c.droppedCount > 1)
        val span = c.nnTimesMs.last() - c.nnTimesMs.first()
        assertClose(real - rr.first(), span, 1e-6)
    }

    @Test
    fun aSplineCorrectedBeatAdvancesByTheRealInterval() {
        val rr = List(3600) { i -> if (i % 60 == 30) 2000.0 else 1000.0 }
        val real = rr.sum()
        val c = correctRr(rr)
        assertTrue(c.correctedCount > 50)
        val span = c.nnTimesMs.last() - c.nnTimesMs.first()
        assertClose(real - rr.first(), span, 1e-6)
        assertTrue(c.nn.max() < 1500.0)
    }

    @Test
    fun aSensorDropoutIsNotSplicedOutWhenTimestampsArePassed() {
        val rr = 860.0
        val nBeats = 4000
        val holeSec = 600
        val holeAt = 2000
        val rrMs = ArrayList<Double>(nBeats)
        val rrTsMs = ArrayList<Double>(nBeats)
        var wallMs = 1700000000000.0
        for (i in 0 until nBeats) {
            wallMs += rr
            if (i == holeAt) wallMs += holeSec * 1000.0
            rrMs.add(rr)
            rrTsMs.add(floorToDouble(wallMs / 1000.0) * 1000.0)
        }
        val spliced = correctRr(rrMs)
        val anchored = correctRr(rrMs, rrTsMs)
        assertEquals(spliced.nn.size, anchored.nn.size)
        val splicedSpan = spliced.nnTimesMs.last() - spliced.nnTimesMs.first()
        val anchoredSpan = anchored.nnTimesMs.last() - anchored.nnTimesMs.first()
        assertClose((nBeats - 1) * rr, splicedSpan, 1e-6)
        assertClose(holeSec * 1000.0, anchoredSpan - splicedSpan, 1000.0)
        var jumps = 0
        for (i in 1 until anchored.nnTimesMs.size) {
            val step = anchored.nnTimesMs[i] - anchored.nnTimesMs[i - 1]
            if (abs(step - rr) > 1e-6) jumps++
        }
        assertEquals(1, jumps)
    }

    private fun cleanRsa(): List<Double> {
        val rnd = DartRandom(11)
        return List(400) { i ->
            1050.0 +
                95.0 * sin(2.0 * PI * i / 13.0) +
                25.0 * sin(2.0 * PI * i / 61.0) +
                (rnd.nextDouble() - 0.5) * 10.0
        }
    }

    /** sqrt of the mean squared successive difference, the RMSSD hrvTime publishes on a clean series. */
    private fun rmssd(rr: List<Double>): Double {
        var ssd = 0.0
        for (i in 1 until rr.size) {
            val d = rr[i] - rr[i - 1]
            ssd += d * d
        }
        return sqrt(ssd / (rr.size - 1))
    }

    private fun sdnn(rr: List<Double>): Double = stddev(rr)!!

    private fun floorToDouble(x: Double): Double = kotlin.math.floor(x)
}
