package app.circlet.analytics.stress

import app.circlet.analytics.support.confidenceTier
import app.circlet.analytics.support.median
import app.circlet.core.AbsentReason
import app.circlet.core.DayStress
import app.circlet.core.Metric
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import app.circlet.core.StressPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Ported from OpenStrap lib/src/onehz/clinical/stress_si.dart (Baevsky &
// Berseneva 2008). Each surviving ~5-minute window is one timeline point.
// The day score is the saturating map of the median window, half-scale at
// Baevsky's normal/elevated boundary of 150. Windows whose RR range is under
// 20 ms are quantization and are dropped, so a flat series stays absent.

private const val NORMAL_UPPER_SI = 150.0

fun dayStress(samples: List<Sample>, zone: ZoneId, date: LocalDate, now: Instant): DayStress {
    val clipped = samples.filter { sample ->
        sample.epochSecond <= now.epochSecond &&
            Instant.ofEpochSecond(sample.epochSecond).atZone(zone).toLocalDate() == date
    }
    val beats = beatsOf(clipped)
    if (beats == null) {
        return DayStress(Metric.absent(AbsentReason.NOT_ENOUGH_DATA), emptyList())
    }
    val (nn, times) = beats
    val windows = stressWindows(nn, times) ?: return DayStress(
        Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        emptyList(),
    )
    if (windows.isEmpty()) {
        return DayStress(Metric.absent(AbsentReason.SIGNAL_TOO_NOISY), emptyList())
    }
    val timeline = windows.map { StressPoint(Instant.ofEpochMilli(it.endTimeMs.toLong()), siToScore(it.si)) }
    val score = siToScore(median(windows.map { it.si })!!)
    val conf = (0.4 + windows.size / 60.0).coerceIn(0.4, 0.7)
    return DayStress(Metric.of(score, confidenceTier(conf)), timeline)
}

/** Display map. SI 150 (top of Baevsky's normal band) lands at 50. */
internal fun siToScore(si: Double): Double {
    if (!si.isFinite() || si <= 0) return 0.0
    return (100.0 * si / (si + NORMAL_UPPER_SI)).coerceIn(0.0, 100.0)
}

private fun beatsOf(samples: List<Sample>): Pair<List<Double>, List<Double>>? {
    val sorted = samples.sortedBy { it.epochSecond }
    if (sorted.isEmpty()) return null
    val nn = ArrayList<Double>()
    val times = ArrayList<Double>()
    var cursor = sorted.first().epochSecond * 1000.0
    for (sample in sorted) {
        val start = sample.epochSecond * 1000.0
        if (start > cursor) cursor = start
        for (rr in sample.rrIntervalsMs) {
            if (!SignalBounds.plausibleRr(rr)) continue
            cursor += rr
            nn.add(rr.toDouble())
            times.add(cursor)
        }
    }
    return if (nn.isEmpty()) null else nn to times
}
