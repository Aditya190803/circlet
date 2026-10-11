package app.circlet.core

/**
 * One night of relative skin temperature.
 *
 * The strap has no calibrated degrees. [deviation] is how far that night's settled
 * count sits from the user's own nights, in standard deviations of those nights.
 * [baselineNights] is how many prior nights were in the window. [flag] stays
 * [SkinTempFlag.NOT_ENOUGH_BASELINE] until that window is long enough to score.
 * [settledMeanCounts] is this night's own settled mean in raw strap counts, kept so
 * later nights can use it as baseline; it is null when the night had no usable reading
 * and is never a temperature.
 * [importedCelsius] is the night's skin temperature in degrees as WHOOP scored it, present
 * only on a day brought in from a WHOOP export. Circlet never computes it.
 */
data class SkinTemperature(
    val deviation: Metric<Double>,
    val baselineNights: Int,
    val flag: SkinTempFlag,
    val settledMeanCounts: Double? = null,
    val importedCelsius: Double? = null,
)

/** Whether a night's relative skin temperature is ordinary, high, or not yet comparable. */
enum class SkinTempFlag {
    NORMAL,
    ELEVATED,
    NOT_ENOUGH_BASELINE,
}
