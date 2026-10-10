package app.circlet.core

import kotlinx.coroutines.flow.StateFlow

/** Mass unit used when the UI shows body weight. Storage stays in kilograms. */
enum class MassUnit {
    KILOGRAM,
    POUND,
}

/** How clock times are shown. [MATCH_SYSTEM] follows the device. */
enum class ClockStyle {
    MATCH_SYSTEM,
    H12,
    H24,
}

/** What a double tap on the strap does while this phone is connected. */
enum class DoubleTapAction {
    STOP_ALARM,
    MARK_MOMENT,
}

/**
 * Preferences that do not change the physiology.
 * [heartRateBroadcast] is the standard heart-rate profile toggle for gym equipment.
 */
data class UserSettings(
    val mass: MassUnit,
    val clock: ClockStyle,
    val doubleTap: DoubleTapAction,
    val heartRateBroadcast: Boolean,
)

/** Units, clock, double tap, and heart-rate broadcast. */
interface SettingsRepository {
    val settings: StateFlow<UserSettings>

    suspend fun update(settings: UserSettings): Outcome<UserSettings>
}
