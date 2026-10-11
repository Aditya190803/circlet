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
// above rest, in bouts of at least two minutes, and gives each a typical cadence
// for that effort. Requiring the raised heart rate is what keeps seated hand
// movement out; OpenStrap dropped a movement-only conversion for over-counting.
// The result is always LOW confidence and the app labels it an estimate.

/** Gen 4 resting accelerometer noise sits near 0.03 g (p90 0.058 g, OpenStrap device.dart). */
private const val MOVING_DYN_G = 0.06
private const val MOVING_RESERVE = 0.18
private const val RUNNING_RESERVE = 0.50
private const val MIN_BOUT_MINUTES = 2

private fun cadence(reserve: Double): Double = if (reserve < RUNNING_RESERVE) {
    90.0 + 35.0 * ((reserve - MOVING_RESERVE) / (RUNNING_RESERVE - MOVING_RESERVE))
} else {
    150.0 + 25.0 * ((reserve - RUNNING_RESERVE) / 0.4).coerceIn(0.0, 1.0)
}

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
    for (minute in enmoSeries(accel, expectedMinutes = 1440).minutes) {
        if (minute.nSamples < 30) continue
        val index = ((minute.tsMinStartMs / 1000.0 - start) / 60.0).toInt()
        if (index !in 0 until 1440 || index >= bpm.size) continue
        val heart = bpm[index]
        if (heart <= 0) continue
        reserve[index] = (heart - resting) / (maxHr - resting)
        moving[index] = minute.dynAmp > MOVING_DYN_G && reserve[index] >= MOVING_RESERVE
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
            for (k in i..j) steps += cadence(reserve[k])
        }
        i = j + 1
    }
    return Metric.of(steps.roundToInt(), Confidence.LOW)
}
