// Ported from OpenStrap analytics/lib/src/onehz/sleep/cpc.dart.
// Cardiopulmonary coupling (Thomas 2005) is withdrawn: no respiration channel independent of the tachogram.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6

class CpcResult(
    val hfc: Double,
    val lfc: Double,
    val vlfc: Double,
    val cpcRatio: Double,
    val dominantHz: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "hfc" to round6(hfc),
        "lfc" to round6(lfc),
        "vlfc" to round6(vlfc),
        "cpc_ratio" to round6(cpcRatio),
        "dominant_hz" to round6(dominantHz),
    )
}

/**
 * CPC from a cleaned NN series and its beat times.
 *
 * Always absent. There is no respiration channel in this signature that is
 * independent of [nnMs], so there is no coupling to measure.
 */
@Deprecated(
    "WITHDRAWN — this never measured cardiopulmonary coupling. Its respiration " +
        "surrogate was the NN series itself, so cpc_ratio equalled the RR " +
        "periodogram HF/LF ratio (measured agreement 1.0000085). Delete the cpc_ratio " +
        "surface; reinstate only with a real respiration channel in the signature.",
)
@Suppress("UNUSED_PARAMETER")
fun cardiopulmonaryCoupling(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
): OsMetric<CpcResult> = OsMetric.absent(
    tier = Tier.HIGH,
    inputsUsed = listOf("rr_corrected"),
    note = "cardiopulmonary coupling needs a respiration channel independent of " +
        "the beat times (Thomas 2005 uses an ECG-derived respiration); we have " +
        "only the tachogram, so no coupling is measured",
)
