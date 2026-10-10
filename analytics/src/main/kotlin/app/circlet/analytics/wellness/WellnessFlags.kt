package app.circlet.analytics.wellness

import app.circlet.analytics.strain.ctlAtlTsb
import app.circlet.analytics.support.confidenceTier
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.WellnessFlag
import app.circlet.core.WellnessKind

// Day flags from OpenStrap lib/src/onehz/wellness/anomaly.dart (Mahalanobis
// 1936, temperature omitted) and human/overreaching_conjunction.dart.
// Illness is RHR up and HRV down together. Overreaching is load and RHR
// moving the same way. illness_cusum.dart stays available for the RHR-only
// series; it is not this flag. A flag is a state, not a diagnosis.

fun wellnessFlags(
    days: List<DaySummary>,
    dailyTrimp: List<Double>? = null,
): List<WellnessFlag> {
    if (days.isEmpty()) return emptyList()
    val flags = ArrayList<WellnessFlag>()
    illnessFlag(days)?.let { flags.add(it) }
    if (dailyTrimp != null && dailyTrimp.size == days.size) {
        overreachingFlag(days, dailyTrimp)?.let { flags.add(it) }
    }
    return flags
}

private fun illnessFlag(days: List<DaySummary>): WellnessFlag? {
    val dates = days.map { it.date.toString() }
    val feats = days.map { day ->
        AnomalyFeatures(
            rhr = day.heart.resting.bpm.value?.toDouble(),
            hrv = day.heart.hrv.rmssdMs.value,
            temp = null,
            resp = day.heart.respiratoryRate.breathsPerMinute.value,
        )
    }
    val anomaly = multivariateAnomaly(dates, feats)
    val last = anomaly.lastOrNull() ?: return null
    if (!last.flagged) return null
    val rhrUp = last.drivers.any { it.label == "RHR" && it.contribution > 0 }
    val hrvDown = last.drivers.any { it.label == "HRV(↓)" && it.contribution > 0 }
    if (!rhrUp || !hrvDown) return null
    return WellnessFlag(
        WellnessKind.ILLNESS,
        Confidence.LOW,
        "Resting heart rate is up and HRV is down together.",
    )
}

private fun overreachingFlag(days: List<DaySummary>, dailyTrimp: List<Double>): WellnessFlag? {
    val load = ctlAtlTsb(dailyTrimp)
    val rhr = days.map { it.heart.resting.bpm.value?.toDouble() }
    if (rhr.size < 18) return null
    val recent = rhr.takeLast(5)
    val baseline = rhr.dropLast(5).takeLast(28).filterNotNull()
    val conjunction = overreachingConjunction(load, recent, baseline)
    val value = conjunction.value ?: return null
    if (!value.bothPointSameWay) return null
    return WellnessFlag(
        WellnessKind.OVERREACHING,
        confidenceTier(conjunction.confidence),
        "Recent load is above your usual and resting heart rate has been up.",
    )
}
