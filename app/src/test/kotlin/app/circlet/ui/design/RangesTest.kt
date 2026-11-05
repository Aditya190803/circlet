package app.circlet.ui.design

import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.ReferenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangesTest {

    @Test
    fun rangeAxisIsUnitWhenNothingToShow() {
        assertEquals(RangeAxis(0.0, 1.0), rangeAxis(null))
        assertEquals(RangeAxis(0.0, 1.0), rangeAxis(null, null, null))
    }

    @Test
    fun rangeAxisPadsThreeTenthsOfSpan() {
        val axis = rangeAxis(50.0, ReferenceRange(40.0, 60.0))
        assertEquals(34.0, axis.low, 1e-9)
        assertEquals(66.0, axis.high, 1e-9)
    }

    @Test
    fun rangeAxisIncludesValueOutsideBands() {
        val axis = rangeAxis(100.0, ReferenceRange(40.0, 60.0), null)
        assertEquals(22.0, axis.low, 1e-9)
        assertEquals(118.0, axis.high, 1e-9)
    }

    @Test
    fun rangeAxisPadsOneUnitForZeroSpan() {
        val axis = rangeAxis(5.0, ReferenceRange(5.0, 5.0))
        assertEquals(4.0, axis.low, 1e-9)
        assertEquals(6.0, axis.high, 1e-9)
    }

    @Test
    fun fractionClampsToBar() {
        val axis = RangeAxis(0.0, 10.0)
        assertEquals(0f, axis.fraction(-5.0), 1e-6f)
        assertEquals(0.5f, axis.fraction(5.0), 1e-6f)
        assertEquals(1f, axis.fraction(50.0), 1e-6f)
    }

    @Test
    fun rangeAxisNeverPadsANonNegativeMetricBelowZero() {
        val axis = rangeAxis(8.0, ReferenceRange(0.0, 5.0), ReferenceRange(7.7, 11.0))
        assertEquals(0.0, axis.low, 1e-9)
    }

    @Test
    fun rangeScorePeaksInTheMiddleOfTheGoodBand() {
        val good = ReferenceRange(40.0, 60.0)
        assertEquals(1f, rangeScore(50.0, good, 30.0), 1e-6f)
        assertEquals(0.5f, rangeScore(40.0, good, 30.0), 1e-6f)
        assertEquals(0.5f, rangeScore(60.0, good, 30.0), 1e-6f)
    }

    @Test
    fun rangeScoreFallsBelowHalfOutsideAndBottomsOutAThirdOfTheSpanAway() {
        val good = ReferenceRange(40.0, 60.0)
        assertTrue(rangeScore(39.9, good, 30.0) < 0.5f)
        assertEquals(0.25f, rangeScore(65.0, good, 30.0), 1e-6f)
        assertEquals(0f, rangeScore(30.0, good, 30.0), 1e-6f)
        assertEquals(0f, rangeScore(0.0, good, 30.0), 1e-6f)
    }

    @Test
    fun rangeScoreTowardHighRewardsTheTopOfTheBandAndBeyond() {
        val good = ReferenceRange(40.0, 60.0)
        assertEquals(0.5f, rangeScore(40.0, good, 30.0, Toward.HIGH), 1e-6f)
        assertEquals(1f, rangeScore(60.0, good, 30.0, Toward.HIGH), 1e-6f)
        assertEquals(1f, rangeScore(90.0, good, 30.0, Toward.HIGH), 1e-6f)
        assertTrue(rangeScore(35.0, good, 30.0, Toward.HIGH) < 0.5f)
    }

    @Test
    fun rangeScoreTowardLowRewardsTheBottomOfTheBandAndBelow() {
        val good = ReferenceRange(40.0, 60.0)
        assertEquals(1f, rangeScore(30.0, good, 30.0, Toward.LOW), 1e-6f)
        assertEquals(0.5f, rangeScore(60.0, good, 30.0, Toward.LOW), 1e-6f)
        assertTrue(rangeScore(65.0, good, 30.0, Toward.LOW) < 0.5f)
    }

    @Test
    fun rangeScoreIsNeutralWithoutABand() {
        assertEquals(0.5f, rangeScore(10.0, null, 30.0), 1e-6f)
    }

    @Test
    fun positionTagIsNullWithoutBothSides() {
        assertNull(positionTag(null, ReferenceRange(1.0, 2.0)))
        assertNull(positionTag(1.5, null))
    }

    @Test
    fun positionTagBelowInAndAbove() {
        val band = ReferenceRange(40.0, 60.0)
        assertEquals("BELOW RANGE", positionTag(39.9, band))
        assertEquals("IN RANGE", positionTag(40.0, band))
        assertEquals("IN RANGE", positionTag(50.0, band))
        assertEquals("IN RANGE", positionTag(60.0, band))
        assertEquals("ABOVE RANGE", positionTag(60.1, band))
    }

    @Test
    fun forKindFindsMatchingReference() {
        val hrv = reference(MetricKind.HRV)
        val rhr = reference(MetricKind.RESTING_HEART_RATE)
        val refs = listOf(hrv, rhr)
        assertEquals(hrv, refs.forKind(MetricKind.HRV))
        assertEquals(rhr, refs.forKind(MetricKind.RESTING_HEART_RATE))
        assertNull(refs.forKind(MetricKind.STRAIN))
        assertNull(emptyList<MetricReference>().forKind(MetricKind.HRV))
    }

    @Test
    fun signedDeviationUsesSignAndNoUnit() {
        assertEquals("+1.2", 1.23.signedDeviation())
        assertEquals("−0.5", (-0.5).signedDeviation())
        assertEquals("0", 0.0.signedDeviation(0))
        assertEquals("0.0", (-0.04).signedDeviation())
        assertEquals("0.0", (-0.04).decimal())
    }

    private fun reference(kind: MetricKind) = MetricReference(
        kind = kind,
        personal = ReferenceRange(40.0, 60.0),
        personalDays = 14,
        ideal = null,
        idealLabel = null,
        idealSource = null,
        higherIsBetter = null,
    )
}
