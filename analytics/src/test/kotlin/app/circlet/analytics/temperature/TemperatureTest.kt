package app.circlet.analytics.temperature

import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.SkinTempFlag
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureTest {
    private fun night(coldSec: Int, totalSec: Int = 28800): List<Int?> =
        List(totalSec) { i -> if (i in 6000 until 6000 + coldSec) 700 else 805 }

    private fun nightlies(vararg means: Double?, start: LocalDate = LocalDate.of(2026, 1, 1)): List<NightlyTemp> =
        means.mapIndexed { i, mean -> NightlyTemp(start.plusDays(i.toLong()), mean) }

    @Test
    fun settledNightKeepsItsSamplesAndMean() {
        val nightly = nightlySkinTemp(night(coldSec = 0))
        assertTrue(nightly.present)
        assertEquals(805.0, nightly.value!!.meanCounts, 1e-9)
        assertEquals(1.0, nightly.value!!.settledFraction, 1e-9)
        assertEquals(Confidence.HIGH, nightly.confidence)
    }

    @Test
    fun coldSegmentIsTrimmedOutOfTheMean() {
        val nightly = nightlySkinTemp(night(coldSec = 1440))
        assertTrue(nightly.present)
        assertEquals(0.95, nightly.value!!.settledFraction, 1e-9)
        assertEquals(805.0, nightly.value!!.meanCounts, 1e-9)
    }

    @Test
    fun nightBelowSkinTemperatureRefuses() {
        val nightly = nightlySkinTemp(night(coldSec = 7200))
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, nightly.absentReason)
    }

    @Test
    fun feverIsNotTrimmedBecauseTheBandIsOneSided() {
        val hot = List(28800) { i -> if (i in 6000 until 13200) 905 else 805 }
        val nightly = nightlySkinTemp(hot)
        assertTrue(nightly.present)
        assertEquals(1.0, nightly.value!!.settledFraction, 1e-9)
        assertEquals(830.0, nightly.value!!.meanCounts, 1e-9)
    }

    @Test
    fun underTheSampleFloorTheNightIsAbsent() {
        val nightly = nightlySkinTemp(List(30) { 805 })
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, nightly.absentReason)
    }

    @Test
    fun sustainedElevationFlagsAfterPersistence() {
        val means = ArrayList<Double?>()
        for (i in 0 until 14) means += 2000.0 + (if (i % 2 == 0) 3 else -3)
        means += listOf(2060.0, 2065.0, 2070.0)
        val out = tempIllnessFlag(nightlies(*means.toTypedArray()))
        assertEquals(TempElevation.NORMAL, out[14].elevation)
        assertEquals(TempElevation.ELEVATED, out[15].elevation)
        assertEquals(TempElevation.ELEVATED, out.last().elevation)
        assertNotNull(out.last().z)
        assertEquals(Confidence.HIGH, out.last().confidence)
    }

    @Test
    fun degenerateBaselineStaysNormalWithNullZ() {
        val means = ArrayList<Double?>()
        for (i in 0 until 12) means += 2000.0
        means += 2050.0
        val out = tempIllnessFlag(nightlies(*means.toTypedArray()))
        assertEquals(TempElevation.NORMAL, out.last().elevation)
        assertNull(out.last().z)
        assertEquals(Confidence.LOW, out.last().confidence)
    }

    @Test
    fun shortBaselineScoresAsNotEnoughBaseline() {
        val scored = scoreSkinTemp(nightlies(2000.0, 2002.0, 1998.0, 2010.0))
        assertEquals(SkinTempFlag.NOT_ENOUGH_BASELINE, scored.flag)
        assertEquals(3, scored.baselineNights)
        assertEquals(AbsentReason.CALIBRATING, scored.deviation.absentReason)
    }

    @Test
    fun missingTonightScoresAsNotEnoughBaseline() {
        val means = ArrayList<Double?>()
        for (i in 0 until 10) means += 2000.0 + (if (i % 2 == 0) 5 else -5)
        means += null
        val scored = scoreSkinTemp(nightlies(*means.toTypedArray()))
        assertEquals(SkinTempFlag.NOT_ENOUGH_BASELINE, scored.flag)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, scored.deviation.absentReason)
    }

    @Test
    fun gapResetsThePersistenceRun() {
        val start = LocalDate.of(2026, 1, 1)
        val nights = ArrayList<NightlyTemp>()
        for (i in 0 until 10) nights += NightlyTemp(start.plusDays(i.toLong()), 2000.0 + (if (i % 2 == 0) 3 else -3))
        nights += NightlyTemp(start.plusDays(10), 2060.0)
        nights += NightlyTemp(start.plusDays(12), 2065.0)
        val out = tempIllnessFlag(nights)
        assertTrue(out.none { it.elevation == TempElevation.ELEVATED })
    }

    @Test
    fun lutealElevationIsSuppressed() {
        val means = ArrayList<Double?>()
        for (i in 0 until 14) means += 2000.0 + (if (i % 2 == 0) 3 else -3)
        means += listOf(2060.0, 2065.0, 2070.0)
        val luteal = List(14) { false } + listOf(true, true, true)
        val out = tempIllnessFlag(nightlies(*means.toTypedArray()), luteal = luteal)
        assertEquals(TempElevation.NORMAL, out.last().elevation)
        assertEquals(Confidence.LOW, out.last().confidence)
    }

    @Test
    fun scoredNightCarriesDeviationInStandardDeviations() {
        val means = ArrayList<Double?>()
        for (i in 0 until 14) means += 2000.0 + (if (i % 2 == 0) 3 else -3)
        means += listOf(2060.0, 2065.0, 2070.0)
        val scored = scoreSkinTemp(nightlies(*means.toTypedArray()))
        assertEquals(SkinTempFlag.ELEVATED, scored.flag)
        assertEquals(16, scored.baselineNights)
        assertTrue(scored.deviation.value!! > 2.0)
    }

    @Test
    fun minimumSevenBaselineNightsBeforeADeviationIsShown() {
        // Three-valued jitter: with an odd run of ±3 the median absolute
        // deviation collapses to zero, so the baseline alternates through zero.
        val means = ArrayList<Double?>()
        for (i in 0 until 7) means += 2000.0 + (i % 3 - 1) * 3
        means += 2060.0
        val scored = scoreSkinTemp(nightlies(*means.toTypedArray()))
        assertEquals(7, scored.baselineNights)
        assertNotNull(scored.deviation.value)
    }
}
