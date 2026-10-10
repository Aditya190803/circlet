// Ported from OpenStrap analytics/lib/src/onehz/sleep/sri.dart.
// Phillips et al. 2017 epoch-by-epoch SRI: 200·(agreement/cases)−100, not SD of midsleep.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6

/** One adjacent-day comparison. [dayIndex] is the later day of the pair. */
data class SriPair(
    val dayIndex: Int,
    val agreement: Int,
    val cases: Int,
) {
    /** This pair's SRI on the same 200·p−100 scale as the whole record. */
    val sri: Double get() = 200.0 * agreement / cases - 100.0

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "day_index" to dayIndex,
        "sri" to round6(sri),
        "agreement" to agreement,
        "cases" to cases,
    )
}

data class SriResult(
    /** −100..100. */
    val sri: Double,
    /** Adjacent-day comparisons + 1. */
    val days: Int,
    /** Epoch comparisons made, thin pairs included. */
    val cases: Int,
    /**
     * Per-pair breakdown of the same sum, in day order.
     * Empty when no pair cleared the coverage floor. Omitting a thin pair
     * from this list does not remove it from [sri].
     */
    val pairs: List<SriPair> = emptyList(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "sri" to round6(sri),
        "days" to days,
        "cases" to cases,
        "pairs" to pairs.map { it.toJson() },
    )
}

private val sriInputs = listOf("sleep_wake_epochs")

/**
 * True Phillips SRI from a clock-aligned binary sleep/wake vector.
 *
 * [sleepWake] is one bool per epoch (true = asleep), consecutive days of
 * exactly [epochsPerDay] epochs, so index `d * epochsPerDay + e` is epoch `e`
 * of day `d`. [valid] drops epochs from the agreement count; it does not
 * change which epochs enter the published total. A pair is listed in
 * [SriResult.pairs] only when both days contribute at least [minPairCases]
 * epochs (default: half the clock day). `minPairCases == 0` still drops a
 * zero-case pair so [SriPair.sri] cannot be NaN.
 */
fun phillipsSri(
    sleepWake: List<Boolean>,
    epochsPerDay: Int,
    valid: List<Boolean>? = null,
    minPairCases: Int? = null,
): OsMetric<SriResult> {
    val n = sleepWake.size
    if (epochsPerDay <= 0 || n < 2 * epochsPerDay) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = sriInputs,
            note = "need ≥2 full days of clock-aligned sleep/wake epochs for SRI",
        )
    }
    val days = n / epochsPerDay
    if (days < 2) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = sriInputs,
            note = "need ≥2 full days for SRI",
        )
    }

    val pairFloor = minPairCases ?: (epochsPerDay / 2)
    var agreement = 0
    var cases = 0
    val pairs = ArrayList<SriPair>()
    for (d in 1 until days) {
        var pairAgreement = 0
        var pairCases = 0
        for (e in 0 until epochsPerDay) {
            val iPrev = (d - 1) * epochsPerDay + e
            val iCur = d * epochsPerDay + e
            if (iCur >= n) break
            if (valid != null && (!valid[iPrev] || !valid[iCur])) continue
            pairCases++
            if (sleepWake[iPrev] == sleepWake[iCur]) pairAgreement++
        }
        agreement += pairAgreement
        cases += pairCases
        if (pairCases > 0 && pairCases >= pairFloor) {
            pairs.add(SriPair(d, pairAgreement, pairCases))
        }
    }
    if (cases == 0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = sriInputs,
            note = "no valid epoch pairs for SRI",
        )
    }

    val sri = 200.0 * agreement / cases - 100.0
    val conf = ((days - 1) / 7.0).coerceIn(0.3, 0.95)
    return OsMetric(
        value = SriResult(sri, days, cases, pairs),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = sriInputs,
        note = "epoch-by-epoch 24-h concordance (Phillips 2017), " +
            "SRI=200·agreement/cases−100; NOT SD-of-midsleep",
    )
}
