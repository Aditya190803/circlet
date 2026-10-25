package app.circlet.core

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/**
 * Steps the band counted on one local day from its live motion stream, and how many seconds
 * of that stream arrived. Steps outside streamed time are not counted, so [steps] is a floor
 * for the day, never a full-day total.
 */
data class BandStepDay(val steps: Int, val streamedSeconds: Int)

/**
 * Experimental step counting from the band's own accelerometer.
 * Off unless the user turns it on. While on, the band streams motion only when the app is on
 * screen and connected, because the stream costs strap battery; [days] holds what was counted.
 */
interface BandSteps {
    val enabled: StateFlow<Boolean>
    val days: StateFlow<Map<LocalDate, BandStepDay>>

    fun setEnabled(enabled: Boolean)

    /** Whether the app is on screen, the only time the motion stream runs. */
    fun setForeground(foreground: Boolean)
}
