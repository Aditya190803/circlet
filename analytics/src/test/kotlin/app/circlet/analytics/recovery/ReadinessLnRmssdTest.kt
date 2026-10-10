package app.circlet.analytics.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessLnRmssdTest {
    @Test
    fun suppressedBandWhenTonightDropsBelowMeanMinusSwc() {
        val hist = listOf(4.0, 4.05, 3.95, 4.0, 4.1, 3.9, 3.2)
        val m = readinessLnRmssd(hist)
        assertTrue(m.present)
        assertEquals("suppressed", m.value!!.band)
        assertTrue(m.value!!.z!! < 0.0)
    }

    @Test
    fun absentUnderMinNights() {
        assertFalse(readinessLnRmssd(listOf(4.0, 4.1)).present)
    }

    @Test
    fun rollingMeanIsPriorNightsOnly() {
        val hist = listOf(4.0, 4.0, 4.0, 2.0)
        val m = readinessLnRmssd(hist)
        assertTrue(m.present)
        assertEquals(4.0, m.value!!.rolling7Mean, 0.0)
    }

    @Test
    fun undefinedBaselineSdAbstainsInsteadOfCvZero() {
        val m = readinessLnRmssd(listOf(4.0, 2.0), minNights = 2)
        assertFalse(m.present)
        assertNull(m.value)
        assertEquals(0.0, m.confidence, 0.0)
        assertTrue(m.note!!.contains("dispersion undefined"))
        val flat = readinessLnRmssd(listOf(4.0, 4.0, 4.0, 2.0))
        assertTrue(flat.present)
        assertEquals(0.0, flat.value!!.cvPct, 0.0)
        assertNull(flat.value!!.z)
        assertEquals("suppressed", flat.value!!.band)
    }

    @Test
    fun withDatesBaselineIsLastSevenCalendarDaysNotRows() {
        val dates = (1..7).map { "2026-08-0$it" } + listOf("2026-08-29", "2026-08-30")
        val hist = listOf(4.0, 4.05, 3.95, 4.0, 4.1, 3.9, 4.0, 3.5, 3.2)
        assertTrue(readinessLnRmssd(hist).present)
        val m = readinessLnRmssd(hist, dates = dates)
        assertFalse(m.present)
        assertTrue(m.note!!.contains("need_baseline"))
    }

    @Test
    fun oneUnparseableDateFallsBackToRows() {
        val hist = listOf(4.0, 4.05, 3.95, 4.0, 4.1, 3.9, 4.0, 3.5)
        val dates = listOf("n/a") + (2..8).map { "2026-08-0$it" }
        val rows = readinessLnRmssd(hist, minNights = hist.size)
        val m = readinessLnRmssd(hist, dates = dates, minNights = hist.size)
        assertTrue(m.note, m.present)
        assertEquals(rows.value!!.z, m.value!!.z)
    }

    @Test
    fun oneNightAbsentWithNeedNote() {
        val one = readinessLnRmssd(listOf(3.5))
        assertFalse(one.present)
        assertEquals(0.0, one.confidence, 0.0)
        assertEquals(
            "need_baseline:have=1,need=$readinessLnRmssdMinNights",
            one.note,
        )
        val enough = readinessLnRmssd(
            List(readinessLnRmssdMinNights) { i -> 3.5 + i * 0.01 },
        )
        assertTrue(enough.present)
    }
}
