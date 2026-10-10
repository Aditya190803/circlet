package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepUserProfileTest {
    @Test
    fun coldStartHasZeroPersonalWeight() {
        assertEquals(0.0, SleepUserProfile().personalWeight, 0.0)
        assertEquals(0.0, SleepUserProfile(nights = 0).personalWeight, 0.0)
    }

    @Test
    fun personalWeightGrowsAndCapsAtHalf() {
        assertEquals(0.25, SleepUserProfile(nights = 7).personalWeight, 1e-9)
        assertEquals(0.5, SleepUserProfile(nights = 14).personalWeight, 0.0)
        assertEquals(0.5, SleepUserProfile(nights = 40).personalWeight, 0.0)
    }

    @Test
    fun firstFoldTakesTheObservationWhole() {
        val o = SleepNightObservation(
            epochs = 700,
            hrFloorP5 = 50.0,
            hrSleepMedian = 58.0,
            rmssdMed = 40.0,
        )
        val p = SleepUserProfile().fold(o)
        assertEquals(1, p.nights)
        assertEquals(50.0, p.hrFloorP5!!, 0.0)
        assertEquals(58.0, p.hrSleepMedian!!, 0.0)
        assertEquals(40.0, p.rmssdMed!!, 0.0)
    }

    @Test
    fun subsequentFoldsEwmaAndLeaveAnAbsentAxisUntouched() {
        var p = SleepUserProfile().fold(
            SleepNightObservation(epochs = 700, hrSleepMedian = 60.0, rmssdMed = 40.0),
        )
        p = p.fold(SleepNightObservation(epochs = 700, hrSleepMedian = 70.0))
        assertEquals(65.0, p.hrSleepMedian!!, 1e-9)
        assertEquals(40.0, p.rmssdMed!!, 0.0)
        assertEquals(2, p.nights)
    }

    @Test
    fun ewmaAlphaFloorsAtTwoOverHorizonPlusOne() {
        var p = SleepUserProfile()
        repeat(30) {
            p = p.fold(SleepNightObservation(epochs = 700, hrSleepMedian = 60.0))
        }
        val q = p.fold(SleepNightObservation(epochs = 700, hrSleepMedian = 90.0))
        val a = 2.0 / (14 + 1)
        assertEquals(60.0 * (1 - a) + 90.0 * a, q.hrSleepMedian!!, 1e-6)
    }

    @Test
    fun jsonRoundTripsAndOmitsNullAxes() {
        val p = SleepUserProfile().fold(
            SleepNightObservation(
                epochs = 700,
                hrFloorP5 = 50.0,
                hrFloorP25 = 55.0,
                hrSleepMedian = 60.0,
                hrArousal = 72.0,
                enmoStillCut = 0.01,
                enmoMoveCut = 0.05,
                lfhfMed = 1.2,
                rkMed = 3.4,
            ),
        )
        val j = p.toJson()
        assertFalse(j.containsKey("rmssd_med"))
        val back = SleepUserProfile.fromJson(j)
        assertEquals(p.nights, back.nights)
        assertEquals(50.0, back.hrFloorP5!!, 0.0)
        assertEquals(72.0, back.hrArousal!!, 0.0)
        assertEquals(1.2, back.lfhfMed!!, 0.0)
        assertNull(back.rmssdMed)
    }

    @Test
    fun aConsistentProfileDoesNotDestabilizeStaging() {
        val n = 4 * 3600
        val hr = List(n) { 55.0 }
        val accel = List(n) { i -> AccelSample(i * 1000.0, 0.0, 0.0, 1.0) }
        val noProfile = cardioStager(hr, accel).base
        val benign = SleepUserProfile(
            nights = 40,
            hrSleepMedian = 55.0,
            hrArousal = 75.0,
            hrFloorP25 = 50.0,
            hrFloorP5 = 48.0,
        )
        val withProfile = cardioStager(hr, accel, userProfile = benign).base
        assertTrue(noProfile.wakePct < 20.0)
        assertTrue(withProfile.wakePct < 20.0)
        assertTrue(kotlin.math.abs(withProfile.wakePct - noProfile.wakePct) < 5.0)
    }
}
