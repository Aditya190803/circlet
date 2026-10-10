package app.circlet.analytics.insight

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.mdc
import app.circlet.analytics.support.robustBaseline

// Ported from OpenStrap lib/src/onehz/human/percentile_of_you.dart.
// A value's rank is against this user's own history. A record clears the
// prior extreme by more than the minimal detectable change.

internal enum class Better { HIGHER, LOWER, NEITHER }

internal data class PercentileOfYou(val value: Double, val percentile: Double, val n: Int, val label: String)

internal data class RecordCheck(
    val isRecord: Boolean,
    val kind: String,
    val value: Double,
    val priorExtreme: Double?,
    val margin: Double?,
    val mdc: Double?,
)

internal fun percentileOfYou(
    value: Double,
    history: List<Double>,
    better: Better,
    minN: Int = 14,
): Scored<PercentileOfYou> {
    if (history.size < minN) return Scored.absent("need more of your own history for a percentile-of-you")
    var below = 0
    var equal = 0
    for (h in history) {
        if (h < value) below++ else if (h == value) equal++
    }
    val pct = 100.0 * (below + 0.5 * equal) / history.size
    val conf = (history.size / 60.0).coerceIn(0.3, 0.95)
    return Scored.of(PercentileOfYou(value, pct, history.size, bandLabel(pct, better)), conf, "within-user percentile")
}

internal fun personalRecordCheck(
    value: Double,
    history: List<Double>,
    better: Better,
    typicalError: Double? = null,
    minN: Int = 14,
): Scored<RecordCheck> {
    if (history.size < minN || better == Better.NEITHER) {
        return Scored.absent("records need history and a defined better direction")
    }
    val baseline = robustBaseline(history, minN)
    val gate = mdc(baseline, typicalError) ?: return Scored.absent("no MDC (degenerate scale) — refusing to claim a record")
    val prior = if (better == Better.HIGHER) history.max() else history.min()
    val beats = if (better == Better.HIGHER) value - prior else prior - value
    val isRecord = beats > gate
    val kind = if (!isRecord) "none" else if (better == Better.HIGHER) "high" else "low"
    return Scored.of(
        RecordCheck(isRecord, kind, value, prior, beats, gate),
        (history.size / 60.0).coerceIn(0.3, 0.9),
        "record only if it beats your prior extreme by > MDC",
    )
}

private fun bandLabel(pct: Double, better: Better): String {
    if (better == Better.NEITHER) {
        return when {
            pct >= 90 -> "among your highest"
            pct >= 70 -> "higher than usual"
            pct > 30 -> "typical for you"
            pct > 10 -> "lower than usual"
            else -> "among your lowest"
        }
    }
    val good = if (better == Better.HIGHER) pct else 100.0 - pct
    return when {
        good >= 90 -> "among your best"
        good >= 70 -> "better than usual"
        good > 30 -> "typical for you"
        good > 10 -> "worse than usual"
        else -> "among your worst"
    }
}
