// Sample alignment for the OpenStrap 1 Hz sleep and heart ports.
// Heart rate 0 is off-wrist. RR intervals are stamped at the sample's whole second,
// which is the edge behaviour when the strap does not send a sub-second beat time.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.Sample
import java.time.Instant
import java.time.ZoneId

internal data class AlignedSeries(
    val accel: List<AccelSample>,
    val hr: List<Double>,
    val rrMs: List<Double>,
    val rrTsMs: List<Double>,
    /** True when any sample has a measured heart rate or a non-zero gravity vector. */
    val worn: Boolean,
)

internal fun alignSamples(samples: List<Sample>, now: Instant): AlignedSeries {
    val nowSec = now.epochSecond
    val filtered = samples.filter { it.epochSecond <= nowSec }
    // Day data arrives chronological; sorting it again is pure waste. The check
    // is one pass, and a chronological input sorts to itself, so this is exact.
    val ordered = if (filtered.isEpochOrdered()) filtered else filtered.sortedBy { it.epochSecond }
    val accel = ArrayList<AccelSample>(ordered.size)
    val hr = ArrayList<Double>(ordered.size)
    val rrMs = ArrayList<Double>()
    val rrTsMs = ArrayList<Double>()
    var worn = false
    for (sample in ordered) {
        val g = sample.gravity
        val zero = g == null || (g.x == 0f && g.y == 0f && g.z == 0f)
        val valid = g != null && !zero
        if (valid) worn = true
        accel.add(
            AccelSample(
                tsMs = sample.epochSecond * 1000.0,
                x = g?.x?.toDouble() ?: 0.0,
                y = g?.y?.toDouble() ?: 0.0,
                z = g?.z?.toDouble() ?: 0.0,
                valid = valid,
            ),
        )
        val bpm = sample.heartRateBpm
        if (bpm != null && bpm > 0) worn = true
        hr.add(if (bpm != null && bpm > 0) bpm.toDouble() else 0.0)
        val stamp = sample.epochSecond * 1000.0
        for (rr in sample.rrIntervalsMs) {
            if (rr > 0) {
                rrMs.add(rr.toDouble())
                rrTsMs.add(stamp)
            }
        }
    }
    return AlignedSeries(accel, hr, rrMs, rrTsMs, worn)
}

internal fun zoneOffsets(zone: ZoneId): (Int) -> Int = { epochSec ->
    zone.rules.getOffset(Instant.ofEpochSecond(epochSec.toLong())).totalSeconds
}

/** True when no record precedes an earlier one; such an input sorts to itself. */
private fun List<Sample>.isEpochOrdered(): Boolean {
    for (i in 1 until size) {
        if (this[i].epochSecond < this[i - 1].epochSecond) return false
    }
    return true
}

internal fun coreConfidence(openStrap: Double): Confidence = when {
    openStrap >= 0.75 -> Confidence.HIGH
    openStrap >= 0.45 -> Confidence.MEDIUM
    else -> Confidence.LOW
}

internal fun <T> coreValue(value: T, openStrapConfidence: Double): Metric<T> =
    Metric.of(value, coreConfidence(openStrapConfidence))

internal fun reasonFromNote(note: String?): AbsentReason {
    val text = note ?: return AbsentReason.NOT_ENOUGH_DATA
    return when {
        text.contains("need_baseline:") -> AbsentReason.CALIBRATING
        text.contains("rr_overcount") || text.contains("rmssd_refused") -> AbsentReason.SIGNAL_TOO_NOISY
        text.contains("implausible") -> AbsentReason.IMPLAUSIBLE
        else -> AbsentReason.NOT_ENOUGH_DATA
    }
}
