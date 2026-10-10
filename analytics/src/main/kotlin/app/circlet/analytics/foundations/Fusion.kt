// Ported from OpenStrap analytics/lib/src/onehz/foundations/fusion.dart.
// Inverse-variance fusion (Aitken 1935). An untrusted channel is dropped, not down-weighted.
// A non-finite value or variance is dropped too: NaN would otherwise poison the weight sum.

package app.circlet.analytics.foundations

import kotlin.math.sqrt

data class FusionInput(
    val value: Double,
    /** σ². Non-positive or non-finite variance is dropped. */
    val variance: Double,
    val trusted: Boolean = true,
    val label: String = "",
)

data class FusionResult(
    val value: Double?,
    val variance: Double?,
    val stdUncertainty: Double?,
    val used: List<String>,
    val dropped: List<String>,
)

/** Precision-weighted fusion. Absent when nothing trusted and finite survives. */
fun inverseVarianceFuse(inputs: List<FusionInput>): FusionResult {
    val used = ArrayList<String>()
    val dropped = ArrayList<String>()
    var wsum = 0.0
    var vwsum = 0.0
    for (inp in inputs) {
        if (!inp.trusted || !inp.variance.isFinite() || inp.variance <= 0.0 || !inp.value.isFinite()) {
            dropped.add(inp.label)
            continue
        }
        val w = 1.0 / inp.variance
        wsum += w
        vwsum += inp.value * w
        used.add(inp.label)
    }
    if (wsum == 0.0) {
        return FusionResult(
            value = null,
            variance = null,
            stdUncertainty = null,
            used = emptyList(),
            dropped = dropped,
        )
    }
    val fusedVar = 1.0 / wsum
    val fused = vwsum / wsum
    return FusionResult(
        value = fused,
        variance = fusedVar,
        stdUncertainty = sqrt(fusedVar),
        used = used,
        dropped = dropped,
    )
}
