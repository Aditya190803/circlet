package app.circlet.core

/**
 * One night's imported temperature, or an explicit unsupported state.
 *
 * WHOOP 4.0 has no verified temperature channel through this connection.
 * [deviation], [baselineNights] and [settledMeanCounts] remain for reading older
 * backups; those derived counts must not be displayed or used as a baseline.
 * [importedCelsius] is the night's skin temperature in degrees as WHOOP scored it, present
 * only on a day brought in from a WHOOP export. Circlet never computes it.
 */
data class SkinTemperature(
    val deviation: Metric<Double>,
    val baselineNights: Int,
    val flag: SkinTempFlag,
    val settledMeanCounts: Double? = null,
    val importedCelsius: Double? = null,
) {
    companion object {
        fun unavailable() = SkinTemperature(
            deviation = Metric.absent(AbsentReason.UNSUPPORTED_SENSOR),
            baselineNights = 0,
            flag = SkinTempFlag.UNAVAILABLE,
        )
    }
}

/** Quarantine legacy offset-68 estimates without changing the underlying recordings. */
fun DaySummary.withVerifiedSkinTemperature(): DaySummary {
    val imported = skinTemperature?.importedCelsius?.takeIf { it.isFinite() && it in 20.0..45.0 }
    val verified = SkinTemperature.unavailable().copy(importedCelsius = imported)
    return copy(
        skinTemperature = verified,
        references = references.filterNot { it.kind == MetricKind.SKIN_TEMPERATURE },
    )
}

/** Whether a night's relative skin temperature is ordinary, high, or not yet comparable. */
enum class SkinTempFlag {
    NORMAL,
    ELEVATED,
    NOT_ENOUGH_BASELINE,
    UNAVAILABLE,
}
