package app.circlet.analytics.support

import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric

// OpenStrap's Metric carries a 0–1 confidence and a note. Circlet's Metric
// carries a tier and an AbsentReason. This wrapper keeps the note so tests
// can assert the same gates, and maps it onto the core type at the boundary.

internal data class Scored<T>(
    val value: T?,
    val confidence: Double,
    val note: String,
) {
    val present: Boolean get() = value != null

    fun toCore(reason: AbsentReason = reasonFor(note)): Metric<T> =
        if (value != null) Metric.of(value, confidenceTier(confidence))
        else Metric.absent(reason)

    companion object {
        fun <T> absent(note: String): Scored<T> = Scored(null, 0.0, note)

        fun <T> of(value: T, confidence: Double, note: String): Scored<T> =
            Scored(value, confidence, note)
    }
}

internal fun confidenceTier(score: Double): Confidence = when {
    score >= 0.7 -> Confidence.HIGH
    score >= 0.45 -> Confidence.MEDIUM
    else -> Confidence.LOW
}

internal fun needBaselineNote(have: Int, need: Int): String =
    app.circlet.analytics.foundations.needBaselineNote(have, need)

internal fun unknownFamilyNote(id: String?): String =
    "unknown_device_family:id=${if (id.isNullOrEmpty()) "none" else id}"

internal fun <T> calibrationFor(byFamily: Map<String, T>, family: String?): T? =
    if (family.isNullOrEmpty()) null else byFamily[family]

/** Maps an OpenStrap absence note onto the core empty-state reason. */
internal fun reasonFor(note: String): AbsentReason {
    val n = note.lowercase()
    return when {
        n.startsWith("need_baseline") || n.contains("calibrat") -> AbsentReason.CALIBRATING
        n.contains("off-skin") || n.contains("off_skin") || n.contains("not worn") ||
            n.contains("no hr") && n.contains("worn") -> AbsentReason.NOT_WORN
        n.contains("implausible") || n.contains("unreliable") || n.contains("outside") ||
            n.contains("absurd") -> AbsentReason.IMPLAUSIBLE
        n.contains("noisy") || n.contains("degenerate") || n.contains("quantum") ->
            AbsentReason.SIGNAL_TOO_NOISY
        else -> AbsentReason.NOT_ENOUGH_DATA
    }
}
