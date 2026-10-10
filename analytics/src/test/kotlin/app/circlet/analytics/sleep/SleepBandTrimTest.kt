package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.DartRandom
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepBandTrimTest {
    private data class LieIn(val accel: List<AccelSample>, val hr: List<Double>, val band: List<Int>)

    private fun lieInNight(): LieIn {
        val accel = ArrayList<AccelSample>()
        val hr = ArrayList<Double>()
        val band = ArrayList<Int>()
        val rnd = DartRandom(7)
        var t = MIDNIGHT - 2 * 3600
        while (t < MIDNIGHT + 11 * 3600) {
            val asleep = t >= MIDNIGHT - 3600 && t < WAKE
            val walk = t >= WAKE && t < WAKE + 5 * 60
            val lieIn = t >= WAKE + 5 * 60 && t < MIDNIGHT + 8 * 3600 + 30 * 60
            val moving = !asleep && !lieIn
            accel.add(
                AccelSample(
                    t * 1000.0,
                    if (moving) 0.4 * cos(t / 2.0) else 0.01 * rnd.nextDouble(),
                    0.0,
                    if (moving) 1.0 + 0.3 * sin(t / 3.0) else 1.0,
                ),
            )
            hr.add(if (asleep) 55.0 else if (lieIn) 57.0 else if (walk) 80.0 else 85.0)
            band.add(if (asleep) 2 else if (lieIn || walk) 3 else 0)
            t++
        }
        return LieIn(accel, hr, band)
    }

    @Test
    fun withoutBandTheNightRunsPastWake() {
        val n = lieInNight()
        val base = segmentSleep(n.accel, n.hr, tzOffsetSec = 0)
        assertTrue(base.present)
        assertTrue((base.window!!.offsetMs!! / 1000.0).toInt() > WAKE + 10 * 60)
        assertNull(base.bandOffsetTrimSec)
    }

    @Test
    fun bandLastSleepEndsTheNightAtWake() {
        val n = lieInNight()
        val base = segmentSleep(n.accel, n.hr, tzOffsetSec = 0)
        val s = segmentSleep(n.accel, n.hr, tzOffsetSec = 0, bandSleepState = n.band)
        assertTrue(s.present)
        assertEquals(WAKE * 1000.0, s.window!!.offsetMs!!, 0.0)
        val untrimmedEnd = (base.window!!.offsetMs!! / 1000.0).toInt()
        assertEquals(untrimmedEnd - WAKE, s.bandOffsetTrimSec)
        assertEquals(untrimmedEnd, (s.window!!.offsetMs!! / 1000.0).toInt() + s.bandOffsetTrimSec!!)
        assertTrue(s.inBedSec!! < base.inBedSec!!)
        assertTrue(s.tstSec!! < base.tstSec!!)
        assertEquals(s.bandOffsetTrimSec, s.toJson()["band_offset_trim_sec"])
    }

    @Test
    fun allAbsentBandMatchesNoBand() {
        val n = lieInNight()
        val absent = segmentSleep(
            n.accel,
            n.hr,
            tzOffsetSec = 0,
            bandSleepState = List(n.band.size) { -1 },
        )
        val none = segmentSleep(n.accel, n.hr, tzOffsetSec = 0)
        assertEquals(none.toJson(), absent.toJson())
    }

    @Test
    fun forcedWindowIgnoresTheBand() {
        val n = lieInNight()
        val w = ForcedWindow(MIDNIGHT - 3600, MIDNIGHT + 8 * 3600 + 30 * 60)
        val withBand = segmentSleep(n.accel, n.hr, tzOffsetSec = 0, forcedWindow = w, bandSleepState = n.band)
        val plain = segmentSleep(n.accel, n.hr, tzOffsetSec = 0, forcedWindow = w)
        assertEquals(plain.toJson(), withBand.toJson())
    }

    @Test
    fun bandShorterThanAccelIsIgnored() {
        val n = lieInNight()
        val short = segmentSleep(n.accel, n.hr, tzOffsetSec = 0, bandSleepState = n.band.subList(0, 100))
        val none = segmentSleep(n.accel, n.hr, tzOffsetSec = 0)
        assertEquals(none.toJson(), short.toJson())
    }

    companion object {
        private const val T0 = 1700000000
        private val MIDNIGHT = T0 - (T0 % 86400)
        private val WAKE = MIDNIGHT + 7 * 3600 + 10 * 60
    }
}
