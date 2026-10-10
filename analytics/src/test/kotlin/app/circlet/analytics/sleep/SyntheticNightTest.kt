package app.circlet.analytics.sleep

import app.circlet.analytics.heart.computeHeart
import app.circlet.analytics.recovery.computeRecovery
import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.GravityVector
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.Sex
import app.circlet.core.SleepKind
import app.circlet.core.SleepStage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One still night of 7.5 h at a low heart rate, then a 25 min afternoon nap.
 * RR is emitted only on the second a beat ends, so coverage stays near 1.
 * Duplicating every beat is the OpenStrap over-count refusal.
 */
class SyntheticNightTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val date: LocalDate = LocalDate.of(2026, 3, 11)
    private val origin: Long = date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
    private val now: Instant = date.plusDays(1).atStartOfDay(zone).toInstant()
    private val sleepStart: Long = date.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond()
    private val sleepEnd: Long = date.atTime(6, 30).atZone(zone).toEpochSecond()
    private val napStart: Long = date.atTime(14, 0).atZone(zone).toEpochSecond()
    private val napEnd: Long = date.atTime(14, 25).atZone(zone).toEpochSecond()

    @Test
    fun stillNightAndAfternoonNap() {
        val samples = day(duplicateBeats = false)
        val profile = profile()
        val sleep = computeSleep(samples, zone, date, now, profile, emptyList())
        val main = sleep.main.value
        assertNotNull("main sleep ${sleep.main.absentReason}", main)
        main!!
        assertEquals(SleepKind.MAIN, main.kind)
        assertEquals("main-$date", main.id)
        assertTrue("onset ${main.start}", main.start.epochSecond in (sleepStart - 45 * 60)..(sleepStart + 45 * 60))
        assertTrue("offset ${main.end}", main.end.epochSecond in (sleepEnd - 45 * 60)..(sleepEnd + 45 * 60))
        val asleep = main.timeAsleep.seconds
        assertTrue("time asleep $asleep", asleep in 6 * 3600..8 * 3600)
        val staged = main.epochs.filter { it.stage != SleepStage.AWAKE }.sumOf { it.duration.seconds }
        assertEquals(asleep, staged)
        assertTrue(main.timeInBed.seconds >= asleep)
        assertTrue(main.latency.seconds >= 0)
        val efficiency = main.efficiency.value
        if (efficiency != null) assertTrue(efficiency in 0..100)

        assertEquals(1, sleep.naps.size)
        val nap = sleep.naps.single()
        assertEquals(SleepKind.NAP, nap.kind)
        assertEquals("nap-$date-0", nap.id)
        assertTrue("nap start ${nap.start}", nap.start.epochSecond in napStart..(napStart + 5 * 60))
        val napAsleep = nap.timeAsleep.seconds
        assertTrue("nap asleep $napAsleep", napAsleep in 20 * 60..26 * 60)
        assertEquals(napAsleep, nap.epochs.filter { it.stage != SleepStage.AWAKE }.sumOf { it.duration.seconds })
        assertTrue(nap.epochs.none { it.stage == SleepStage.DEEP || it.stage == SleepStage.REM })

        assertEquals(AbsentReason.NOT_ENOUGH_DATA, sleep.consistency.index.absentReason)
        assertNull(sleep.need.recommendedBedtime)

        val heart = computeHeart(
            samples,
            zone,
            date,
            now,
            profile,
            sleepStart = main.start,
            sleepEnd = main.end,
        )
        val resting = heart.resting.bpm.value
        assertNotNull("rhr ${heart.resting.bpm.absentReason}", resting)
        assertTrue("rhr $resting", resting!! in 40..60)
        val rmssd = heart.hrv.rmssdMs.value
        assertNotNull("rmssd ${heart.hrv.rmssdMs.absentReason}", rmssd)
        assertTrue("rmssd $rmssd", rmssd!! in 5.0..120.0)
        val sdnn = heart.hrv.sdnnMs.value
        assertNotNull(sdnn)
        assertTrue(sdnn!! > 0.0)
        val average = heart.averageBpm.value
        assertNotNull(average)
        assertTrue(average!! in 50..80)

        val recovery = computeRecovery(heart, sleep.performance, emptyList())
        assertEquals(AbsentReason.CALIBRATING, recovery.score.absentReason)
        assertEquals(CalibrationStatus.CALIBRATING, recovery.calibration)
        assertEquals(4, recovery.drivers.size)
    }

    @Test
    fun duplicatedBeatsRefuseRmssdFrequencyAndRespiration() {
        val samples = day(duplicateBeats = true)
        val profile = profile()
        val sleep = computeSleep(samples, zone, date, now, profile, emptyList())
        val main = sleep.main.value
        assertNotNull(main)
        val heart = computeHeart(
            samples,
            zone,
            date,
            now,
            profile,
            sleepStart = main!!.start,
            sleepEnd = main.end,
        )
        assertEquals(AbsentReason.SIGNAL_TOO_NOISY, heart.hrv.rmssdMs.absentReason)
        assertNull(heart.hrv.frequency)
        assertEquals(AbsentReason.SIGNAL_TOO_NOISY, heart.respiratoryRate.breathsPerMinute.absentReason)
        assertNotNull("sdnn ${heart.hrv.sdnnMs.absentReason}", heart.hrv.sdnnMs.value)
        val resting = heart.resting.bpm.value
        assertNotNull(resting)
        assertTrue(resting!! in 40..60)
    }

    private fun day(duplicateBeats: Boolean): List<Sample> {
        val beats = beatEnds()
        val seconds = (now.epochSecond - origin).toInt()
        val samples = ArrayList<Sample>(seconds)
        for (i in 0 until seconds) {
            val t = origin + i
            val asleep = t >= sleepStart && t < sleepEnd
            val napping = t >= napStart && t < napEnd
            val still = asleep || napping
            // Wake flips arm elevation every second. A 5 s angle median still
            // moves by well over the 5° van Hees gate, so the day is not one nap.
            val x = if (still) 0.0 else 0.35
            val z = if (still) 1.0 else if (t % 2L == 0L) 0.9 else -0.9
            val hr = when {
                asleep -> 50 + (2.0 * sin(2.0 * PI * (t - sleepStart) / 1800.0)).roundToInt()
                napping -> 55
                else -> 70
            }
            val rr = beats[t]
            val intervals = when {
                rr == null -> emptyList()
                duplicateBeats -> listOf(rr, rr)
                else -> listOf(rr)
            }
            samples.add(
                Sample(
                    epochSecond = t,
                    heartRateBpm = hr,
                    rrIntervalsMs = intervals,
                    gravity = GravityVector(x.toFloat(), 0f, z.toFloat()),
                    optical = null,
                    rawRecord = null,
                ),
            )
        }
        return samples
    }

    /** One beat end per interval, so the sum of RR matches the elapsed night. */
    private fun beatEnds(): Map<Long, Int> {
        val beats = HashMap<Long, Int>()
        var tMs = sleepStart * 1000.0
        val endMs = sleepEnd * 1000.0
        var i = 0
        while (tMs < endMs) {
            val rr = (1250.0 + 30.0 * sin(2.0 * PI * i / 4.0)).roundToInt()
            tMs += rr
            val sec = (tMs / 1000.0).toLong()
            if (sec < sleepEnd) beats[sec] = rr
            i++
        }
        return beats
    }

    private fun profile(): Profile = Profile(
        birthDate = LocalDate.of(1990, 1, 1),
        ageYears = null,
        sex = Sex.UNSPECIFIED,
        heightCm = null,
        weightKg = null,
        maxHeartRateBpm = null,
        customZones = emptyList(),
    )
}
