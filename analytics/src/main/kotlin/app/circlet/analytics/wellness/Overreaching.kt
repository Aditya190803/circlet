package app.circlet.analytics.wellness

import app.circlet.analytics.strain.LoadState
import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.dispersionBelowQuantum
import app.circlet.analytics.support.needBaselineNote
import app.circlet.analytics.support.robustBaseline

// Ported from OpenStrap lib/src/onehz/human/overreaching_conjunction.dart.
// Two facts side by side: acute load at least 1.5× chronic load, and resting
// heart rate above the smallest worthwhile change on enough recent nights.
// It is a coincidence, not a diagnosis of overreaching.

internal data class OverreachingConjunction(
    val loadRatio: Double,
    val nightsElevated: Int,
    val nightsConsidered: Int,
    val bothPointSameWay: Boolean,
)

internal fun overreachingConjunction(
    load: Scored<LoadState>?,
    rhrRecent: List<Double?>,
    rhrBaselineWindow: List<Double>,
    loadRatioThreshold: Double = 1.5,
    minNightsElevated: Int = 4,
    minNightsObserved: Int = 4,
    minBaseline: Int = 14,
): Scored<OverreachingConjunction> {
    val note = "TWO FACTS, no score. NOT a diagnosis of overreaching."
    val state = load?.value
    if (state == null || state.ctl <= 0) return Scored.absent("no load history to compare against. $note")
    val observed = rhrRecent.filterNotNull()
    if (observed.size < minNightsObserved) {
        return Scored.absent(needBaselineNote(observed.size, minNightsObserved))
    }
    val base = robustBaseline(rhrBaselineWindow, minBaseline)
    val centre = base.center
    val scale = base.scale
    val belowQuantum = dispersionBelowQuantum(rhrBaselineWindow, 1.0)
    val gate = if (!belowQuantum && scale != null && scale > 0) 0.5 * scale else null
    if (centre == null || gate == null || !base.sufficient) {
        return Scored.absent(needBaselineNote(base.nValid, minBaseline))
    }
    var elevated = 0
    for (v in observed) if (v - centre >= gate) elevated++
    val ratio = state.atl / state.ctl
    return Scored.of(
        OverreachingConjunction(ratio, elevated, observed.size, ratio >= loadRatioThreshold && elevated >= minNightsElevated),
        0.4,
        note,
    )
}
