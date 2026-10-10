package app.circlet.core

/**
 * Step count for a local day.
 * The count is an estimate from 1 Hz motion, so a present value stays at [Confidence.LOW].
 */
data class Steps(
    val count: Metric<Int>,
)

/**
 * Estimated maximal oxygen uptake, in millilitres per kilogram per minute.
 */
data class Vo2Max(
    val mlPerKgMin: Metric<Double>,
)

/** A baseline deviation worth showing on the day. */
enum class WellnessKind {
    /** Resting heart rate is up and HRV is down together. */
    ILLNESS,

    /** Hard strain landed on a low recovery. */
    OVERREACHING,
}

/**
 * One active wellness flag. An empty list means nothing is flagged.
 * [detail] is a short line the UI can show.
 */
data class WellnessFlag(
    val kind: WellnessKind,
    val confidence: Confidence,
    val detail: String,
)
