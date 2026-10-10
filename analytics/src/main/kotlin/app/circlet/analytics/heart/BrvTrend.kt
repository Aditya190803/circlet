// Ported from OpenStrap analytics/lib/src/onehz/respiration/brv_trend.dart.
// RESPIRATION TIER-MED — Breathing-Rate Variability (BRV) trend.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.stddev
import app.circlet.analytics.foundations.theilSen

data class BrvResult(
    val meanBrpm: Double,
    val sdBrpm: Double,
    val cv: Double,
    val trendSlope: Double?,
    val nWindows: Int,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "mean_brpm" to round6(meanBrpm),
            "sd_brpm" to round6(sdBrpm),
            "cv" to round6(cv),
        )
        if (trendSlope != null) m["trend_slope"] = round6(trendSlope)
        m["n_windows"] = nWindows
        return m
    }
}

/**
 * Within-series breathing-rate variability: SD and CV of [brpm], plus a
 * Theil–Sen slope. A trend descriptor only, meaningful within one wearer.
 */
fun breathingRateVariability(brpm: List<Double>): OsMetric<BrvResult> {
    val inputs = listOf("resp_rate_series")
    if (brpm.size < 3) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "need ≥3 respiratory-rate windows for a BRV estimate",
        )
    }
    val m = mean(brpm)!!
    val sd = stddev(brpm)!!
    val cv = if (m == 0.0) 0.0 else sd / m
    val slope = theilSen(brpm)
    val conf = (0.3 + 0.05 * brpm.size).coerceIn(0.3, 0.8)
    return OsMetric(
        value = BrvResult(
            meanBrpm = m,
            sdBrpm = sd,
            cv = cv,
            trendSlope = slope,
            nWindows = brpm.size,
        ),
        confidence = conf,
        tier = Tier.ESTIMATE,
        inputsUsed = inputs,
        note = "Breathing-rate variability TREND (within-user only); " +
            "surface a change only beyond the metric MDC",
    )
}
