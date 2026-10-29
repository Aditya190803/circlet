package app.circlet.analytics.motion

import app.circlet.analytics.support.confidenceTier
import app.circlet.analytics.support.reasonFor
import app.circlet.core.AbsentReason
import app.circlet.core.Metric
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import app.circlet.core.Steps
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Ported from OpenStrap lib/src/onehz/motion/steps.dart and motion/motion.dart.
// Gait (1.4–2.5 Hz) is above the Nyquist limit of one sample a second, and the
// removed minutes-to-steps conversion over-reported by about 6× on a real day.
// [steps] therefore stays absent. [movementMinutes] is the quantity the source
// publishes: minutes of sustained wrist movement, an estimate, not a step count.

data class MotionDay(
    val steps: Steps,
    val activeMinutes: Metric<Int>,
    val dynFloorG: Double?,
)

fun movementMinutes(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    personalDynFloorG: Double?,
    historyDaysAvailable: Int = 0,
): MotionDay {
    val nowSec = now.epochSecond
    val range = dayRange(date, zone)
    val accel = ArrayList<AccelSample>()
    for (sample in samples) {
        val epoch = sample.epochSecond
        if (epoch > nowSec || epoch !in range) continue
        val g = sample.gravity ?: continue
        if (!SignalBounds.plausibleGravity(g, sample.generation)) continue
        accel.add(AccelSample(epoch * 1000.0, g.x.toDouble(), g.y.toDouble(), g.z.toDouble()))
    }
    if (accel.isEmpty()) {
        val absent = Metric.absent<Int>(AbsentReason.NOT_WORN)
        return MotionDay(Steps(absent), absent, personalDynFloorG)
    }
    val series = enmoSeries(accel, expectedMinutes = 1440)
    val movement = dailyActiveMinutes(
        series.minutes,
        personalDynFloorG,
        historyDaysAvailable = historyDaysAvailable,
        expectedMinutes = 1440,
    )
    val minutes = movement.value?.let { estimate ->
        Metric.of(estimate.activeMinutes, confidenceTier(movement.confidence))
    } ?: Metric.absent(reasonFor(movement.note))
    return MotionDay(
        steps = Steps(Metric.absent(AbsentReason.NOT_ENOUGH_DATA)),
        activeMinutes = minutes,
        dynFloorG = personalDynFloorG,
    )
}

/**
 * Epoch seconds of one local day. Exactly the instants whose local date is
 * [date], daylight-saving transitions included, so filtering by range is the
 * same as comparing local dates one sample at a time.
 */
private fun dayRange(date: LocalDate, zone: ZoneId): LongRange {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return start until end
}
