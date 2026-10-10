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
    val accel = samples.mapNotNull { sample ->
        if (sample.epochSecond > now.epochSecond) return@mapNotNull null
        val g = sample.gravity ?: return@mapNotNull null
        val local = Instant.ofEpochSecond(sample.epochSecond).atZone(zone)
        if (local.toLocalDate() != date) return@mapNotNull null
        if (!SignalBounds.plausibleGravity(g)) return@mapNotNull null
        AccelSample(sample.epochSecond * 1000.0, g.x.toDouble(), g.y.toDouble(), g.z.toDouble())
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
