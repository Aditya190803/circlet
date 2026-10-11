package app.circlet.core

/**
 * One night of relative skin temperature.
 *
 * The strap has no calibrated degrees. [deviation] is how far that night's settled
 * count sits from the user's own nights, in standard deviations of those nights.
 * [baselineNights] is how many prior nights were in the window. [flag] stays
 * [SkinTempFlag.NOT_ENOUGH_BASELINE] until that window is long enough to score.
 */
data class SkinTemperature(
    val deviation: Metric<Double>,
    val baselineNights: Int,
    val flag: SkinTempFlag,
)

/** Whether a night's relative skin temperature is ordinary, high, or not yet comparable. */
enum class SkinTempFlag {
    NORMAL,
    ELEVATED,
    NOT_ENOUGH_BASELINE,
}
