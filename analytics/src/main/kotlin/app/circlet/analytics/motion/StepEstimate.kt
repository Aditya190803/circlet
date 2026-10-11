package app.circlet.analytics.motion

import app.circlet.analytics.strain.perMinuteBpm
import app.circlet.analytics.strain.resolveMaxHr
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Circlet's own step estimate; not ported, and not a published method.
//
// A stride (1.4 to 2.5 Hz) cannot be counted from one motion sample a second, so
// this does not count steps. It finds minutes that look like walking or running,
// wrist movement above the strap's resting noise together with a heart rate raised
// above rest, in bouts of at least three minutes, and gives each a typical cadence
// for that effort. Requiring the raised heart rate is what keeps seated hand
// movement out; OpenStrap dropped a movement-only conversion for over-counting.
// The heart-rate bar is set against the day's own quiet waking level, not the
// sleeping resting rate: sitting and standing awake already run well above that,
// and a bar measured from sleep counted most of a desk day as walking.
// The result is always LOW confidence and the app labels it an estimate.

/** Gen 4 resting accelerometer noise sits near 0.03 g (p90 0.058 g, OpenStrap device.dart). */
private const val AWAKE_DYN_G = 0.03
private const val MOVING_DYN_G = 0.12
private const val MOVING_RESERVE = 0.28
private const val ABOVE_QUIET_RESERVE = 0.10
private const val RUNNING_RESERVE = 0.55
private const val MIN_BOUT_MINUTES = 3

/** A minute flagged as walking is seldom walked end to end. */
private const val WALKING_DUTY = 0.7

/** Steps credited to one minute at this share of heart-rate reserve, above the [floor] that marks walking. */
private fun stepsInMinute(reserve: Double, floor: Double): Double = if (reserve < RUNNING_RESERVE) {
    val effort = ((reserve - floor) / (RUNNING_RESERVE - floor).coerceAtLeast(0.05)).coerceIn(0.0, 1.0)
    (90.0 + 35.0 * effort) * WALKING_DUTY
} else {
    150.0 + 25.0 * ((reserve - RUNNING_RESERVE) / 0.35).coerceIn(0.0, 1.0)
}

private fun quartile(values: List<Double>): Double? =
    if (values.isEmpty()) null else values.sorted()[values.size / 4]

fun estimateSteps(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    profile: Profile,
    restingHeartRateBpm: Double?,
): Metric<Int> {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val accel = ArrayList<AccelSample>()
    for (sample in samples) {
        val epoch = sample.epochSecond
        if (epoch < start || epoch >= end || epoch > now.epochSecond) continue
        val g = sample.gravity ?: continue
        if (!SignalBounds.plausibleGravity(g)) continue
        accel.add(AccelSample(epoch * 1000.0, g.x.toDouble(), g.y.toDouble(), g.z.toDouble()))
    }
    if (accel.isEmpty()) return Metric.absent(AbsentReason.NOT_WORN)
    val resting = restingHeartRateBpm ?: return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    val maxHr = resolveMaxHr(profile, date) ?: return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    if (maxHr - resting < 30) return Metric.absent(AbsentReason.IMPLAUSIBLE)

    val bpm = perMinuteBpm(samples.filter { it.epochSecond <= now.epochSecond }, zone, date)
    val moving = BooleanArray(1440)
    val reserve = DoubleArray(1440)
    val dyn = DoubleArray(1440)
    for (minute in enmoSeries(accel, expectedMinutes = 1440).minutes) {
        if (minute.nSamples < 30) continue
        val index = ((minute.tsMinStartMs / 1000.0 - start) / 60.0).toInt()
        if (index !in 0 until 1440 || index >= bpm.size) continue
        val heart = bpm[index]
        if (heart <= 0) continue
        reserve[index] = (heart - resting) / (maxHr - resting)
        dyn[index] = minute.dynAmp
    }
    val quiet = quartile((0 until 1440).filter { dyn[it] > AWAKE_DYN_G }.map { reserve[it] }) ?: 0.0
    val floor = maxOf(MOVING_RESERVE, quiet + ABOVE_QUIET_RESERVE)
    for (index in 0 until 1440) {
        moving[index] = dyn[index] > MOVING_DYN_G && reserve[index] >= floor
    }
    var steps = 0.0
    var i = 0
    while (i < 1440) {
        if (!moving[i]) {
            i++
            continue
        }
        var j = i
        while (j + 1 < 1440 && moving[j + 1]) j++
        if (j - i + 1 >= MIN_BOUT_MINUTES) {
            for (k in i..j) steps += stepsInMinute(reserve[k], floor)
        }
        i = j + 1
    }
    return Metric.of(steps.roundToInt(), Confidence.LOW)
}
