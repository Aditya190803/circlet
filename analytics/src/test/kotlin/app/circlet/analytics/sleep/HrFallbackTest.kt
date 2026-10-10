package app.circlet.analytics.sleep

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrFallbackTest {
    private data class Day(val hr: List<Double>, val ts: List<Int>)

    private fun day(
        dayH: Int,
        nightH: Int,
        tailH: Int,
        nightBpm: Double = 50.0,
        arouseAt: List<Int> = emptyList(),
    ): Day {
        val hr = ArrayList<Double>()
        val ts = ArrayList<Int>()
        var t = T0
        fun seg(secs: Int, bpm: Double, noise: Boolean = false) {
            for (i in 0 until secs) {
                hr.add(bpm + if (noise) 6.0 * ((i % 7) - 3) else 0.0)
                ts.add(t++)
            }
        }
        seg(dayH * 3600, 72.0, noise = true)
        val nightStart = hr.size
        seg(nightH * 3600, nightBpm)
        for (a in arouseAt) {
            val idx = nightStart + a
            if (idx < hr.size) hr[idx] = 80.0
        }
        seg(tailH * 3600, 72.0, noise = true)
        return Day(hr, ts)
    }

    @Test
    fun sustainedNightDipFindsAWindowOfAboutTheDipLength() {
        val d = day(2, 7, 1)
        val w = hrLedSleepWindow(d.hr, d.ts, hrBaseline = List(120) { 72.0 })
        assertNotNull(w)
        assertTrue(w!!.durationSec > 6 * 3600)
        assertTrue(w.durationSec < 8 * 3600)
        assertTrue(w.thresholdBpm < 72.0)
    }

    @Test
    fun briefArousalsDoNotFragmentTheDip() {
        val d = day(2, 7, 1, arouseAt = listOf(2 * 3600, 4 * 3600, 5 * 3600 + 1800))
        val w = hrLedSleepWindow(d.hr, d.ts, hrBaseline = List(120) { 72.0 })
        assertNotNull(w)
        assertTrue(w!!.durationSec > 6 * 3600)
    }

    @Test
    fun noDipReturnsNull() {
        val hr = ArrayList<Double>()
        val ts = ArrayList<Int>()
        for (i in 0 until 6 * 3600) {
            hr.add(74.0 + (i % 5))
            ts.add(T0 + i)
        }
        val w = hrLedSleepWindow(hr, ts, hrBaseline = List(60) { 75.0 })
        assertNull(w)
    }

    @Test
    fun dipTooShortReturnsNull() {
        val d = day(2, 1, 1)
        val w = hrLedSleepWindow(d.hr, d.ts, hrBaseline = List(120) { 72.0 })
        assertNull(w)
    }

    @Test
    fun awakeStretchEndingAtARecordingHoleIsNotBridged() {
        val hr = ArrayList<Double>()
        val ts = ArrayList<Int>()
        var t = T0
        fun seg(secs: Int, bpm: Double) {
            for (i in 0 until secs) {
                hr.add(bpm)
                ts.add(t++)
            }
        }
        seg(3 * 3600, 50.0)
        seg(20 * 60, 80.0)
        t += 6 * 60
        seg(3600, 50.0)
        val w = hrLedSleepWindow(hr, ts, hrBaseline = List(120) { 75.0 })
        assertNotNull(w)
        assertTrue(w!!.offsetSec <= T0 + 3 * 3600 + 300)
    }

    companion object {
        private const val T0 = 1700000000
    }
}
