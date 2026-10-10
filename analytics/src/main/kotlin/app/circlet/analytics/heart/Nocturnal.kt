// Ported from OpenStrap analytics/lib/src/onehz/clinical/nocturnal.dart.
// CLINICAL TIER-1 — nocturnal RHR and HR dip %.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.percentile
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.sampleCadenceSeconds
import java.time.Duration
import kotlin.math.ceil

data class NocturnalRhr(
    val low30Mean: Double,
    val p1: Double,
    val validSamples: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "low30_mean_bpm" to round6(low30Mean),
        "p1_bpm" to round6(p1),
        "valid_samples" to validSamples,
    )
}

/**
 * Lowest rolling mean of on-skin HR over [window] of wall-clock time
 * (Avram 2019; Dial 2025), plus the 1st percentile. HR = 0 is off-skin.
 * Without [tsSec] the series is 1 Hz by contract. A window that is mostly a
 * hole is ineligible; the whole-night mean is never relabelled as the trough.
 */
fun nocturnalRhr(
    hr: List<Double>,
    tsSec: List<Double>? = null,
    window: Duration = Duration.ofMinutes(30),
    minCoverage: Double = 0.9,
): OsMetric<NocturnalRhr> {
    val inputs = listOf("hr_1hz")
    val valid = ArrayList<Double>()
    for (h in hr) if (h > 0.0) valid.add(h)
    if (tsSec != null && tsSec.size != hr.size) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "HR and timestamps disagree in length",
        )
    }
    val ts = tsSec ?: List(hr.size) { it.toDouble() }
    // Null timestamps are 1 Hz by contract, not by measurement.
    val cadence = if (tsSec == null) 1.0 else sampleCadenceSeconds(ts)
    val winSec = window.seconds.toDouble()
    if (cadence == null) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no measurable sampling cadence — a 30-min trough cannot be " +
                "located without knowing how much time one sample covers",
        )
    }
    val perWindow = winSec / cadence
    if (perWindow < 1.0 || hr.size < perWindow) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "insufficient valid (on-skin) HR for nocturnal RHR",
        )
    }
    val needValid = ceil(minCoverage * perWindow).toInt()
    val firstFullEnd = ts.first() + winSec - cadence
    var sum = 0.0
    var count = 0
    var lo = 0
    var best: Double? = null
    for (hi in hr.indices) {
        if (hr[hi] > 0.0) {
            sum += hr[hi]
            count++
        }
        while (ts[hi] - ts[lo] >= winSec) {
            if (hr[lo] > 0.0) {
                sum -= hr[lo]
                count--
            }
            lo++
        }
        // count == 0 is its own guard: minCoverage 0 makes needValid 0, and
        // 0/0 would latch NaN as the trough for the rest of the night.
        if (ts[hi] < firstFullEnd || count < needValid || count == 0) continue
        val m = sum / count
        if (best == null || m < best) best = m
    }
    if (best == null) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no contiguous on-skin window long enough for a nocturnal RHR " +
                "trough (off-skin gaps are never compacted away)",
        )
    }
    val p1 = percentile(valid, 1.0)!!
    val conf = (valid.size * cadence / 7200.0).coerceIn(0.4, 0.95)
    return OsMetric(
        value = NocturnalRhr(best, p1, valid.size),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "lowest-${window.toMinutes()}-min mean + 1st-percentile; HR=0 excluded " +
            "as off-skin",
    )
}

data class HrDip(
    val dipPct: Double,
    val dayMean: Double,
    val nightMean: Double,
    val band: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "dip_pct" to round6(dipPct),
        "day_mean_bpm" to round6(dayMean),
        "night_mean_bpm" to round6(nightMean),
        "band" to band,
    )
}

/** Minimum valid on-skin samples required on EACH side before a dip % is reported. */
const val hrDipMinSamples = 300

/**
 * Nocturnal HR dip %. Bands follow the BP-dip convention applied to HR:
 * ≥10% dipper, 0–10% non-dipper, <0 riser. HR = 0 is excluded.
 */
fun hrDip(
    dayHr: List<Double>,
    nightHr: List<Double>,
    minSamples: Int = hrDipMinSamples,
): OsMetric<HrDip> {
    val inputs = listOf("hr_1hz_day", "hr_1hz_night")
    val dv = ArrayList<Double>()
    val nv = ArrayList<Double>()
    for (h in dayHr) if (h > 0.0) dv.add(h)
    for (h in nightHr) if (h > 0.0) nv.add(h)
    if (dv.size < minSamples || nv.size < minSamples) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "HR dip needs ≥$minSamples valid samples on each of day and night " +
                "(have day=${dv.size}, night=${nv.size})",
        )
    }
    val dm = mean(dv)
    val nm = mean(nv)
    if (dm == null || nm == null || dm <= 0.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "need both day and night valid HR",
        )
    }
    val dip = (dm - nm) / dm * 100.0
    val band = if (dip >= 10.0) "dipper" else if (dip >= 0.0) "non_dipper" else "riser"
    val conf = ((dv.size + nv.size) / 14400.0).coerceIn(0.4, 0.9)
    return OsMetric(
        value = HrDip(dip, dm, nm, band),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "night-day HR ratio; CV-risk + acute-strain signal",
    )
}
