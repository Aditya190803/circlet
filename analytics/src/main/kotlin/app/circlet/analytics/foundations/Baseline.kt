// Ported from OpenStrap analytics/lib/src/onehz/foundations/baseline.dart.
// Robust personal baseline: median + MAD (Leys 2013; Iglewicz–Hoaglin modified z,
// flag |M| > 3.5) and a minimal detectable change (Hopkins 2000).
// Insufficient values yield a null centre. This is a minimum-count gate, not a coverage fraction.

package app.circlet.analytics.foundations

import kotlin.math.abs
import kotlin.math.sqrt

data class RobustBaseline(
    val center: Double?,
    val scale: Double?,
    val nValid: Int,
    /** Days the window spanned. [robustBaseline] can only set this equal to [nValid]. */
    val nWindow: Int,
    /** True when [nValid] is at least the caller's minimum count. */
    val sufficient: Boolean,
) {
    /** Iglewicz–Hoaglin modified z. Null when the scale is absent or zero. */
    fun modZ(x: Double): Double? {
        val c = center
        val s = scale
        if (c == null || s == null || s == 0.0) return null
        return (x - c) / s
    }

    /** |modZ| > 3.5. Null when undecidable. */
    fun isOutlier(x: Double): Boolean? {
        val m = modZ(x) ?: return null
        return abs(m) > 3.5
    }
}

/**
 * Median and MAD of [window]. [minValid] counts values the caller already decided were valid.
 * Pass only valid samples; this function does not drop any.
 */
fun robustBaseline(window: List<Double>, minValid: Int = 3): RobustBaseline {
    val n = window.size
    if (n == 0) {
        return RobustBaseline(
            center = null,
            scale = null,
            nValid = 0,
            nWindow = 0,
            sufficient = false,
        )
    }
    return RobustBaseline(
        center = median(window),
        scale = mad(window),
        nValid = n,
        nWindow = n,
        sufficient = n >= minValid,
    )
}

/**
 * True when [base] is too coarse to standardize: it sits on a [quantum] grid and its
 * sample SD is under one quantum (or undefined). A mostly continuous baseline is not quantized.
 * A mixed history stays quantized while at least half of it sits on the grid.
 * [quantum] of 0 means the input is not quantized and the guard never fires.
 */
fun dispersionBelowQuantum(base: List<Double>, quantum: Double): Boolean {
    if (quantum <= 0.0) return false
    var onGrid = 0
    for (v in base) {
        val q = v / quantum
        if (abs(q - roundHalfAwayFromZero(q)) <= 1e-9) onGrid++
    }
    if (onGrid * 2 < base.size) return false
    val sd = stddev(base)
    return sd == null || sd < quantum
}

/**
 * Minimal detectable change: 1.96 × √2 × typical error.
 * The typical error is [baseline]'s scale unless [typicalError] is supplied.
 * A zero or unknown scale means no honest noise estimate, so the change is not claimed.
 */
fun mdc(baseline: RobustBaseline, typicalError: Double? = null): Double? {
    val te = typicalError ?: baseline.scale
    if (te == null || te <= 0.0) return null
    return 1.96 * sqrt(2.0) * te
}
