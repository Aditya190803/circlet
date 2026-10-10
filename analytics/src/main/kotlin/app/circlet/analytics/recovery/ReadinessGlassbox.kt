// Ported from OpenStrap analytics/lib/src/onehz/human/readiness_glassbox.dart.
// Deprecated percentile-of-you breakdown and deterministic narrative. readinessComposite is canonical.

package app.circlet.analytics.recovery

import app.circlet.analytics.foundations.Driver
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.dispersionBelowQuantum
import app.circlet.analytics.foundations.needBaselineNote
import app.circlet.analytics.foundations.robustBaseline
import app.circlet.analytics.foundations.round6
import kotlin.math.abs

/** Catalog-priority default weights (HRV > RHR > RR > temp). Not the composite's weights. */
const val wHrv = 0.40
const val wRhr = 0.30
const val wResp = 0.18
const val wTemp = 0.12

/** One readiness input. [history] excludes tonight. [quantum] 0 means continuous. */
data class GlassBoxInput(
    val label: String,
    val value: Double,
    val history: List<Double>,
    val weight: Double,
    val lowerIsBetter: Boolean = false,
    val quantum: Double = 0.0,
)

data class ReadinessBreakdownItem(
    val label: String,
    /** 0..100, higher = better-for-you. Null when history is too short to rank. */
    val percentileOfYou: Double?,
    val weight: Double,
    val weightedContribution: Double,
    val beyondUsualSpread: Boolean,
    val used: Boolean,
    val note: String? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "label" to label,
            "percentile_of_you" to if (percentileOfYou == null) null else round6(percentileOfYou),
            "weight" to round6(weight),
            "weighted_contribution" to round6(weightedContribution),
            "past_mdc" to beyondUsualSpread,
            "used" to used,
        )
        if (note != null) m["note"] = note
        return m
    }
}

data class GlassBoxReadiness(
    val score: Double,
    val breakdown: List<ReadinessBreakdownItem>,
    val drivers: List<Driver>,
    val narrative: String,
    val inputsUsed: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "score" to round6(score),
        "breakdown" to breakdown.map { it.toJson() },
        "drivers" to drivers.map { it.toJson() },
        "narrative" to narrative,
        "inputs_used" to inputsUsed,
    )
}

private val glassBoxInputsUsed = listOf("readiness_inputs")

/**
 * Percentile-of-you breakdown. Not the headline recovery — [readinessComposite] is.
 * The narrative names a driver only when the raw change clears 0.5 × robust SD,
 * and it describes that driver. It does not prescribe a session.
 */
@Deprecated(
    "DUPLICATE readiness — use readinessComposite as the canonical/headline recovery. " +
        "glassBoxReadiness is retained ONLY for its percentile-of-you breakdown + " +
        "deterministic narrative; do NOT surface it as the headline score.",
)
fun glassBoxReadiness(
    inputs: List<GlassBoxInput>,
    minHistory: Int = 7,
): OsMetric<GlassBoxReadiness> {
    val items = ArrayList<ReadinessBreakdownItem>()
    val raw = ArrayList<RawItem>()
    var wsum = 0.0
    var wpsum = 0.0
    var nUsable = 0
    for (inp in inputs) {
        if (inp.history.size < minHistory) {
            items.add(
                ReadinessBreakdownItem(
                    label = inp.label,
                    percentileOfYou = null,
                    weight = inp.weight,
                    weightedContribution = 0.0,
                    beyondUsualSpread = false,
                    used = false,
                    note = needBaselineNote(inp.history.size, minHistory),
                ),
            )
            continue
        }
        var below = 0
        var equal = 0
        for (h in inp.history) {
            if (h < inp.value) {
                below++
            } else if (h == inp.value) {
                equal++
            }
        }
        val pct = 100.0 * (below + 0.5 * equal) / inp.history.size.toDouble()
        val oriented = if (inp.lowerIsBetter) 100.0 - pct else pct
        val base = robustBaseline(inp.history, minValid = minHistory)
        val scale = base.scale
        val delta = if (base.center == null) 0.0 else inp.value - base.center
        val quantized = dispersionBelowQuantum(inp.history, inp.quantum)
        val beyond = !quantized && scale != null && scale > 0.0 && abs(delta) >= 0.5 * scale
        val contribution = inp.weight * (oriented - 50.0)
        items.add(
            ReadinessBreakdownItem(
                label = inp.label,
                percentileOfYou = oriented,
                weight = inp.weight,
                weightedContribution = contribution,
                beyondUsualSpread = beyond,
                used = true,
            ),
        )
        raw.add(RawItem(inp.label, contribution, beyond, delta))
        wsum += inp.weight
        wpsum += inp.weight * oriented
        nUsable++
    }
    if (nUsable == 0 || wsum == 0.0) {
        var have = 0
        for (inp in inputs) {
            if (inp.history.size > have) have = inp.history.size
        }
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = glassBoxInputsUsed,
            note = needBaselineNote(have, minHistory),
        )
    }
    val score = (wpsum / wsum).coerceIn(0.0, 100.0)
    val ranked = raw.sortedWith { a, b -> abs(b.c).compareTo(abs(a.c)) }
    val drivers = ArrayList<Driver>()
    var topDelta: Double? = null
    for (r in ranked) {
        if (!r.beyondUsualSpread) continue
        if (topDelta == null) topDelta = r.delta
        drivers.add(
            Driver(
                r.label,
                r.c,
                detail = if (r.c >= 0.0) "lifting your score" else "dragging your score down",
            ),
        )
    }
    val narrative = buildNarrative(score, drivers, topDelta ?: 0.0)
    val conf = (nUsable / inputs.size.toDouble()).coerceIn(0.3, 0.9)
    return OsMetric(
        value = GlassBoxReadiness(score, items, drivers, narrative, nUsable),
        confidence = conf,
        tier = Tier.ESTIMATE,
        inputsUsed = glassBoxInputsUsed,
        drivers = drivers,
        note = "glass-box: weighted personal-percentile inputs (HRV>RHR>RR>temp); " +
            "drivers are definitional within the formula, not inferred causes",
    )
}

private data class RawItem(
    val label: String,
    val c: Double,
    val beyondUsualSpread: Boolean,
    val delta: Double,
)

private fun buildNarrative(score: Double, drivers: List<Driver>, topDelta: Double): String {
    val band = when {
        score >= 70.0 -> "You're ready"
        score >= 40.0 -> "A moderate day"
        else -> "A low-readiness day"
    }
    if (drivers.isEmpty()) {
        return "$band — nothing moved beyond your normal day-to-day noise."
    }
    val top = drivers.first()
    val dir = if (topDelta >= 0.0) "up" else "down"
    return "$band — mainly because your ${humanLabel(top.label)} is $dir vs your usual."
}

private fun humanLabel(label: String): String = when (label) {
    "hrv" -> "HRV"
    "rhr" -> "resting heart rate"
    "resp" -> "breathing rate"
    "temp" -> "skin temperature"
    else -> label
}
