package app.circlet.analytics.motion

import app.circlet.core.Confidence
import app.circlet.core.GravityVector
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepEstimateTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 10, 1)
    private val profile = Profile(null, 35, Sex.MALE, 180.0, 80.0, 190, emptyList())
    private val dayStart = date.atStartOfDay(zone).toInstant()

    @Test
    fun sustainedWalkingMotionProducesConservativeLowConfidenceEstimate() {
        val data = fixture(activityMinutes = 30, heartRate = 120, amplitude = 0.6)

        val estimate = estimate(data, data.last().epochSecond + 1)

        assertTrue("steps=${estimate.value}", estimate.value in 1_500..2_500)
        assertEquals(Confidence.LOW, estimate.confidence)
    }

    @Test
    fun runningBoutDoesNotUseWalkingCadence() {
        val data = fixture(activityMinutes = 30, heartRate = 160, amplitude = 0.6)

        val estimate = estimate(data, data.last().epochSecond + 1)

        assertTrue("steps=${estimate.value}", estimate.value in 3_500..5_000)
    }

    @Test
    fun lowAmplitudeWristMotionWithElevatedHeartRateIsNotCountedAsGait() {
        val data = fixture(activityMinutes = 30, heartRate = 120, amplitude = 0.12)

        val estimate = estimate(data, data.last().epochSecond + 1)

        assertEquals(0, estimate.value)
    }

    @Test
    fun shortMovementAndIncompleteLastMinuteAreNotExtrapolatedToFullMinutes() {
        val shortBout = fixture(activityMinutes = 4, heartRate = 120, amplitude = 0.6)
        assertEquals(0, estimate(shortBout, shortBout.last().epochSecond + 1).value)

        val sixMinutes = fixture(activityMinutes = 6, heartRate = 120, amplitude = 0.6)
        val activityStart = dayStart.plusSeconds(8 * 60 * 60L + 65 * 60L)
        val now = activityStart.plusSeconds(4 * 60L + 30L)
        val partial = estimate(sixMinutes, now.epochSecond)
        assertNotNull(partial.value)
        assertTrue("steps=${partial.value}", partial.value!! in 250..360)
    }

    @Test
    fun samplesOutsideTheLocalDayDoNotCreateAStepEstimate() {
        val previousDay = sample(dayStart.minusSeconds(60), 160, 0.6)
        val nextDay = sample(dayStart.plusSeconds(24 * 60 * 60L), 160, 0.6)

        val estimate = estimate(listOf(previousDay, nextDay), nextDay.epochSecond + 1)

        assertEquals(null, estimate.value)
    }

    private fun fixture(activityMinutes: Int, heartRate: Int, amplitude: Double): List<Sample> {
        val samples = ArrayList<Sample>()
        val start = dayStart.plusSeconds(8 * 60 * 60L)
        append(samples, start, 65, 75, 0.08)
        append(samples, start.plusSeconds(65 * 60L), activityMinutes, heartRate, amplitude)
        return samples
    }

    private fun append(
        into: MutableList<Sample>,
        start: Instant,
        minutes: Int,
        heartRate: Int,
        amplitude: Double,
    ) {
        val count = minutes * 60
        for (second in 0 until count) {
            into += sample(start.plusSeconds(second.toLong()), heartRate, amplitude)
        }
    }

    private fun sample(at: Instant, heartRate: Int, amplitude: Double): Sample {
        val second = at.epochSecond
        val x = amplitude * sin(2.0 * PI * (second % 4L) / 4.0)
        val z = sqrt(1.0 - x * x)
        return Sample(
            epochSecond = second,
            heartRateBpm = heartRate,
            rrIntervalsMs = emptyList(),
            gravity = GravityVector(x.toFloat(), 0f, z.toFloat()),
            optical = null,
            rawRecord = null,
        )
    }

    private fun estimate(samples: List<Sample>, nowEpochSecond: Long) = estimateSteps(
        samples = samples,
        zone = zone,
        date = date,
        now = Instant.ofEpochSecond(nowEpochSecond),
        profile = profile,
        restingHeartRateBpm = 60.0,
    )
}
