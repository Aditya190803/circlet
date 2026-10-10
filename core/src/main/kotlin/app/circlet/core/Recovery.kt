package app.circlet.core

/**
 * Whether the personal baseline behind recovery is ready.
 * [CALIBRATING] means there are not enough nights yet. The score's own
 * [AbsentReason] says why today's number is missing.
 */
enum class CalibrationStatus {
    CALIBRATING,
    READY,
}

/** One input that moved the recovery score. */
enum class RecoveryDriverKind {
    HRV,
    RESTING_HEART_RATE,
    SLEEP,
    RESPIRATORY_RATE,
}

/**
 * How one input moved recovery.
 * [contribution] is score points. Positive means the input raised readiness.
 * Null when that input was not scored, in which case [absentReason] is set.
 */
data class RecoveryDriver(
    val kind: RecoveryDriverKind,
    val contribution: Double?,
    val absentReason: AbsentReason?,
) {
    init {
        require((contribution == null) == (absentReason != null)) {
            "A driver contribution and an absent reason are mutually exclusive"
        }
    }
}

/**
 * Readiness for a local day.
 * [score] is 0–100. [drivers] lists HRV, resting heart rate, sleep, and respiratory rate.
 * [calibration] is the baseline status, including while the score is absent.
 */
data class Recovery(
    val score: Metric<Int>,
    val calibration: CalibrationStatus,
    val drivers: List<RecoveryDriver>,
)
