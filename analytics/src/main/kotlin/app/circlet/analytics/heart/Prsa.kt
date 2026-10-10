// Ported from OpenStrap analytics/lib/src/onehz/clinical/prsa.dart.
// CLINICAL TIER-1 — Phase-Rectified Signal Averaging (Bauer 2006).

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6
import kotlin.math.abs

data class PrsaResult(
    val capacity: Double,
    val profile: List<Double>,
    val anchors: Int,
    val kind: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "capacity_ms" to round6(capacity),
        "anchors" to anchors,
        "kind" to kind,
    )
}

/** PRSA deceleration capacity. [anchorRatioCap] 0.05 is a calibration knob, not Bauer's. */
fun decelerationCapacity(
    nnMs: List<Double>,
    l: Int = 2,
    anchorRatioCap: Double = 0.05,
): OsMetric<PrsaResult> = prsa(nnMs, l, deceleration = true, anchorRatioCap = anchorRatioCap)

/** PRSA acceleration capacity. */
fun accelerationCapacity(
    nnMs: List<Double>,
    l: Int = 2,
    anchorRatioCap: Double = 0.05,
): OsMetric<PrsaResult> = prsa(nnMs, l, deceleration = false, anchorRatioCap = anchorRatioCap)

private fun prsa(
    nnMs: List<Double>,
    l: Int,
    deceleration: Boolean,
    anchorRatioCap: Double,
): OsMetric<PrsaResult> {
    val kind = if (deceleration) "DC" else "AC"
    val inputs = listOf("rr_cleaned")
    val n = nnMs.size
    if (l < 2) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "PRSA ($kind) needs l≥2 for the s=2 Haar contrast",
        )
    }
    if (n < 2 * l + 4) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few beats for PRSA ($kind)",
        )
    }
    val anchors = ArrayList<Int>()
    for (i in l until n - l) {
        val prev = nnMs[i - 1]
        if (prev <= 0.0) continue
        val delta = nnMs[i] - prev
        val isDecel = delta > 0.0
        if (isDecel != deceleration) continue
        val ratio = abs(nnMs[i] / prev - 1.0)
        if (ratio > anchorRatioCap) continue
        anchors.add(i)
    }
    if (anchors.isEmpty()) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no valid PRSA anchors ($kind)",
        )
    }
    val profile = DoubleArray(2 * l)
    for (a in anchors) {
        for (k in -l until l) {
            profile[k + l] += nnMs[a + k]
        }
    }
    val nAnchors = anchors.size.toDouble()
    for (k in profile.indices) profile[k] /= nAnchors
    val x0 = profile[l]
    val x1 = profile[l + 1]
    val xm1 = profile[l - 1]
    val xm2 = profile[l - 2]
    val capacity = (x0 + x1 - xm1 - xm2) / 4.0
    val conf = (anchors.size / 1000.0).coerceIn(0.3, 0.95)
    return OsMetric(
        value = PrsaResult(
            capacity = capacity,
            profile = profile.toList(),
            anchors = anchors.size,
            kind = kind,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "Bauer 2006 PRSA $kind; needs a long overnight RR record for " +
            "mortality-grade stability",
    )
}
