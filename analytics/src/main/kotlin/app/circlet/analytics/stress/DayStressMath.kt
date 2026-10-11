package app.circlet.analytics.stress

import app.circlet.analytics.support.confidenceTier
import app.circlet.analytics.support.median
import app.circlet.core.AbsentReason
import app.circlet.core.DayStress
import app.circlet.core.Metric
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import app.circlet.core.StressBand
import app.circlet.core.StressBandMinutes
import app.circlet.core.StressPoint
import app.circlet.core.stressBandOf
import java.time.Duration
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
    val nowSec = now.epochSecond
    val range = dayRange(date, zone)
    val clipped = ArrayList<Sample>()
    for (sample in samples) {
        if (sample.epochSecond <= nowSec && sample.epochSecond in range) clipped.add(sample)
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
    return DayStress(Metric.of(score, confidenceTier(conf)), timeline, stressBandMinutes(timeline))
}

/**
 * Whole minutes per stress band from a timeline. Each point counts until the
 * next, capped at [maxGap] so a long pause (the strap off the wrist) does not
 * count as time in a band. The cut-offs are the core [stressBandOf] bands, so
 * this matches what the stress screen derived as a stand-in.
 */
internal fun stressBandMinutes(
    timeline: List<StressPoint>,
    maxGap: Duration = Duration.ofMinutes(10),
): StressBandMinutes {
    val seconds = LongArray(StressBand.values().size)
    for (i in 0 until timeline.size - 1) {
        val current = timeline[i]
        val gap = Duration.between(current.at, timeline[i + 1].at)
        if (!gap.isNegative && !gap.isZero) {
            val counted = if (gap > maxGap) maxGap else gap
            seconds[stressBandOf(current.score).ordinal] += counted.seconds
        }
    }
    fun minutes(band: StressBand): Int = (seconds[band.ordinal] / 60).toInt()
    return StressBandMinutes(
        lowMinutes = minutes(StressBand.LOW),
        mediumMinutes = minutes(StressBand.MEDIUM),
        highMinutes = minutes(StressBand.HIGH),
    )
}

/** Display map. SI 150 (top of Baevsky's normal band) lands at 50. */
internal fun siToScore(si: Double): Double {
    if (!si.isFinite() || si <= 0) return 0.0
    return (100.0 * si / (si + NORMAL_UPPER_SI)).coerceIn(0.0, 100.0)
}

private fun beatsOf(samples: List<Sample>): Pair<List<Double>, List<Double>>? {
    val sorted = if (samples.isEpochOrdered()) samples else samples.sortedBy { it.epochSecond }
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

/** True when no record precedes an earlier one; such an input sorts to itself. */
private fun List<Sample>.isEpochOrdered(): Boolean {
    for (i in 1 until size) {
        if (this[i].epochSecond < this[i - 1].epochSecond) return false
    }
    return true
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
