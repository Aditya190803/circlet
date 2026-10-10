package app.circlet.analytics.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandOffsetTest {
    private data class Night(val ts: List<Int>, val band: List<Int>, val end: Int)

    private fun night(runs: List<Pair<Int?, Int>>): Night {
        val ts = ArrayList<Int>()
        val band = ArrayList<Int>()
        var t = T0
        for ((s, n) in runs) {
            for (i in 0 until n) {
                if (s != null) {
                    ts.add(t)
                    band.add(s)
                }
                t++
            }
        }
        return Night(ts, band, t)
    }

    private fun trim(n: Night): Int? = bandTrimmedOffsetSec(
        startSec = T0,
        endSec = n.end,
        tsSec = n.ts,
        bandState = n.band,
    )

    private fun dupTail(tailSec: Int, present: (Int) -> Boolean): Night {
        val ts = ArrayList<Int>()
        val band = ArrayList<Int>()
        for (i in 0 until 7 * 3600) {
            ts.add(T0 + i)
            band.add(2)
        }
        for (i in 0 until tailSec) {
            if (!present(i)) continue
            repeat(2) {
                ts.add(T0 + 7 * 3600 + i)
                band.add(3)
            }
        }
        return Night(ts, band, T0 + 7 * 3600 + tailSec)
    }

    @Test
    fun lieInAfterWakingMovesEndToLastSleepSecondPlusOne() {
        val n = night(listOf(2 to 8 * 3600, 3 to 70 * 60))
        assertEquals(T0 + 8 * 3600, trim(n))
    }

    @Test
    fun upThenWakeTailAlsoTrims() {
        val n = night(listOf(2 to 8 * 3600, 3 to 30 * 60, 0 to 20 * 60))
        assertEquals(T0 + 8 * 3600, trim(n))
    }

    @Test
    fun midNightUpThenSleepAgainTrimsOnlyTheTrailingUp() {
        val n = night(listOf(2 to 4 * 3600, 3 to 19 * 60, 2 to 3 * 3600, 3 to 48 * 60))
        assertEquals(T0 + 4 * 3600 + 19 * 60 + 3 * 3600, trim(n))
    }

    @Test
    fun midNightUpThenSleepToTheEndDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 4 * 3600, 3 to 28 * 60, 2 to 3 * 3600))))
    }

    @Test
    fun tailShorterThanTenMinDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 7 * 3600, 3 to 9 * 60))))
    }

    @Test
    fun bandNeverSleepDoesNotTrim() {
        assertNull(trim(night(listOf(0 to 2 * 3600, 1 to 3600, 3 to 5 * 3600))))
    }

    @Test
    fun trailingGapOverFiveMinDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 7 * 3600, 3 to 114 * 60, null to 6 * 60))))
    }

    @Test
    fun leadingGapOverFiveMinRightAfterLastSleepDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 7 * 3600, null to 6 * 60, 3 to 114 * 60))))
    }

    @Test
    fun internalGapOverFiveMinDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 7 * 3600, 3 to 60 * 60, -1 to 6 * 60, 3 to 54 * 60))))
    }

    @Test
    fun sameTwoHourTailWithoutTheHoleTrims() {
        assertEquals(T0 + 7 * 3600, trim(night(listOf(2 to 7 * 3600, 3 to 120 * 60))))
    }

    @Test
    fun shortGapsAtHighTailCoverageStillTrim() {
        val n = night(listOf(2 to 7 * 3600, 3 to 30 * 60, null to 2 * 60, 3 to 38 * 60))
        assertEquals(T0 + 7 * 3600, trim(n))
    }

    @Test
    fun stillInTheTailOverSixtySecondsDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 7 * 3600, 3 to 30 * 60, 1 to 10 * 60, 0 to 5 * 60))))
    }

    @Test
    fun wholeWindowCoverageBelowEightyPercentDoesNotTrim() {
        assertNull(trim(night(listOf(-1 to 6 * 3600, 2 to 2 * 3600, 3 to 60 * 60))))
    }

    @Test
    fun trimThatWouldDropBelowThreeHoursDoesNotTrim() {
        assertNull(trim(night(listOf(2 to 2 * 3600 + 50 * 60, 3 to 60 * 60))))
    }

    @Test
    fun allAbsentDoesNotTrim() {
        assertNull(trim(night(listOf(-1 to 9 * 3600))))
    }

    @Test
    fun lengthMismatchOrEmptyWindowDoesNotTrim() {
        assertNull(
            bandTrimmedOffsetSec(
                startSec = T0,
                endSec = T0 + 10,
                tsSec = listOf(1, 2),
                bandState = listOf(2),
            ),
        )
        assertNull(
            bandTrimmedOffsetSec(
                startSec = T0,
                endSec = T0,
                tsSec = emptyList(),
                bandState = emptyList(),
            ),
        )
    }

    @Test
    fun duplicateRowsCannotFakeTailCoverage() {
        val n = dupTail(120 * 60) { i -> i % 2 == 0 }
        assertNull(trim(n))
    }

    @Test
    fun duplicateRowsInACompleteTailStillTrim() {
        val n = dupTail(60 * 60) { true }
        assertEquals(T0 + 7 * 3600, trim(n))
    }

    @Test
    fun stagerAndRuleShareOneSleepCode() {
        assertEquals(kBandStateSleep, AdvancedSleepStager.bandStateAsleep)
    }

    companion object {
        private const val T0 = 1700000000
    }
}
