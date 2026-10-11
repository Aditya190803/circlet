package app.circlet.ui.trends

import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import java.time.Duration

/**
 * The value of [kind] on this day, in the unit documented on [MetricKind].
 *
 * Stopgap: `:core` keeps the same mapping private inside `FakeCirclet`, so the app cannot call it.
 * Replace with a public accessor in `:core` when one exists.
 */
fun DaySummary.metricValue(kind: MetricKind): Metric<Double> = when (kind) {
    MetricKind.SLEEP_PERFORMANCE -> sleepPerformance.percent.mapValue { it.toDouble() }
    MetricKind.SLEEP_DURATION -> sleep.value?.timeAsleep?.let { Metric.of(it.minutes(), sleep.confidence) }
        ?: absentFrom(sleep.absentReason, sleep.confidence)
    MetricKind.SLEEP_EFFICIENCY -> sleep.value?.let { it.efficiency.mapValue { e -> e.toDouble() } }
        ?: absentFrom(sleep.absentReason, sleep.confidence)
    MetricKind.SLEEP_NEED -> sleepNeed.duration.mapValue { it.minutes() }
    MetricKind.SLEEP_DEBT -> sleepDebt.shortfall.mapValue { it.minutes() }
    MetricKind.SLEEP_CONSISTENCY -> sleepConsistency.index.mapValue { it }
    MetricKind.RECOVERY -> recovery.score.mapValue { it.toDouble() }
    MetricKind.STRAIN -> strain.score.mapValue { it }
    MetricKind.STRESS -> stress.score.mapValue { it }
    MetricKind.RESTING_HEART_RATE -> heart.resting.bpm.mapValue { it.toDouble() }
    MetricKind.HRV -> heart.hrv.rmssdMs.mapValue { it }
    MetricKind.RESPIRATORY_RATE -> heart.respiratoryRate.breathsPerMinute.mapValue { it }
    MetricKind.STEPS -> steps.count.mapValue { it.toDouble() }
    MetricKind.CALORIES -> calories.mapValue { it.toDouble() }
    MetricKind.VO2_MAX -> vo2Max.mlPerKgMin.mapValue { it }
}

private fun <T> Metric<T>.mapValue(transform: (T) -> Double): Metric<Double> {
    val present = value
    return if (present != null) {
        Metric.of(transform(present), confidence)
    } else {
        Metric.absent(checkNotNull(absentReason), confidence)
    }
}

private fun absentFrom(reason: AbsentReason?, confidence: Confidence): Metric<Double> =
    Metric.absent(reason ?: AbsentReason.NO_SLEEP, confidence)

private fun Duration.minutes(): Double = toMinutes().toDouble()
