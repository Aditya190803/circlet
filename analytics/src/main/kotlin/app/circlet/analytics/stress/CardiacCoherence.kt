package app.circlet.analytics.stress

import app.circlet.analytics.foundations.freqGrid
import app.circlet.analytics.foundations.lombScargle
import app.circlet.analytics.support.Scored
import kotlin.math.abs

// Ported from OpenStrap lib/src/onehz/clinical/cardiac_coherence.dart.
// McCraty & Zayas 2014: peak-band power around the dominant 0.04–0.26 Hz
// peak, divided by the remaining spectral power. The 0–100 score is the
// saturating map score = 100·ratio/(ratio+1), not a published HeartMath scale.

internal data class CardiacCoherenceValue(
    val ratio: Double,
    val score: Double,
    val peakHz: Double,
)

internal fun cardiacCoherence(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    pacedHz: Double? = null,
    gridPoints: Int = 400,
): Scored<CardiacCoherenceValue> {
    if (nnMs.size < 20 || nnTimesMs.size != nnMs.size) {
        return Scored.absent("too few beats for a coherence estimate")
    }
    val tSec = nnTimesMs.map { it / 1000.0 }
    val spanSec = tSec.last() - tSec.first()
    if (spanSec < 30) return Scored.absent("session too short (< 30s) for a spectral estimate")
    val searchLo = 0.04
    val searchHi = 0.26
    val totalHi = 0.4
    val totalLo = (1.0 / spanSec).coerceIn(0.0033, searchLo)
    val ls = lombScargle(tSec, nnMs, freqGrid(totalLo, totalHi, gridPoints))
        ?: return Scored.absent("spectrum undefined")
    val peakHz = ls.peakFreq(searchLo, searchHi)
        ?: return Scored.absent("no resolvable peak in the 0.04-0.26 Hz coherence band")
    val peakPower = ls.bandPower(
        (peakHz - 0.015).coerceIn(totalLo, totalHi),
        (peakHz + 0.015).coerceIn(totalLo, totalHi),
    )
    val totalPower = ls.bandPower(totalLo, totalHi)
    val remainder = totalPower - peakPower
    if (remainder <= 0) return Scored.absent("degenerate spectrum (all power in the peak window)")
    val ratio = peakPower / remainder
    val score = 100.0 * ratio / (ratio + 1.0)
    val onPace = pacedHz != null && abs(peakHz - pacedHz) < 0.02
    val conf = ((spanSec / 180.0).coerceIn(0.3, 1.0) * (if (onPace) 1.0 else 0.85)).coerceIn(0.2, 0.9)
    val note = if (onPace) {
        "peak matches guided pace — breathing-entrained RSA"
    } else {
        "peak off guided pace by > 0.02 Hz — may be natural RSA, not entrainment"
    }
    return Scored.of(CardiacCoherenceValue(ratio, score, peakHz), conf, note)
}
