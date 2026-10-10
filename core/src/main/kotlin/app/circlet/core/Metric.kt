package app.circlet.core

/**
 * A computed value, or an explicit reason it is missing.
 *
 * [value] and [absentReason] are mutually exclusive. Confidence is always set;
 * an absent value uses [Confidence.LOW] unless the caller passes another tier.
 * The UI draws an empty state when [value] is null.
 */
data class Metric<T>(
    val value: T?,
    val confidence: Confidence,
    val absentReason: AbsentReason?,
) {
    init {
        require((value == null) == (absentReason != null)) {
            "A metric value and an absent reason are mutually exclusive"
        }
    }

    val present: Boolean get() = value != null

    companion object {
        fun <T> of(value: T, confidence: Confidence): Metric<T> = Metric(value, confidence, null)

        fun <T> absent(
            reason: AbsentReason,
            confidence: Confidence = Confidence.LOW,
        ): Metric<T> = Metric(null, confidence, reason)
    }
}

/** How much the inputs support the number. */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** Why a metric has no value. The UI uses this to pick an empty state. */
enum class AbsentReason {
    /** The strap was off the wrist for the window. */
    NOT_WORN,

    /** Too few usable samples to compute the metric. */
    NOT_ENOUGH_DATA,

    /** The personal baseline does not exist yet. */
    CALIBRATING,

    /** History has not been pulled from the strap. */
    STRAP_NOT_SYNCED,

    /** The optical or motion signal failed the plausibility gate. */
    SIGNAL_TOO_NOISY,

    /** A sample or aggregate fell outside a physiological bound. */
    IMPLAUSIBLE,

    /** Worn data exists, and no sleep was found in it. */
    NO_SLEEP,
}
