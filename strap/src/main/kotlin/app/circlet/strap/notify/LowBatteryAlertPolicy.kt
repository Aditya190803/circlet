package app.circlet.strap.notify

import app.circlet.core.StrapState

internal const val LOW_BATTERY_REARM_MARGIN = 5

/** [armed] is true when the next drop to the threshold may alert. [fire] is true for exactly one update per drop. */
internal data class LowBatteryDecision(val armed: Boolean, val fire: Boolean)

internal object LowBatteryAlertPolicy {
    /**
     * A quiet or disabled update never disarms, so the alert still comes once it is allowed
     * and the battery is still low.
     */
    fun evaluate(
        armed: Boolean,
        state: StrapState,
        threshold: Int,
        enabled: Boolean,
        quiet: Boolean,
    ): LowBatteryDecision {
        val percent = state.batteryPercent ?: return LowBatteryDecision(armed, fire = false)
        return when {
            state.charging == true -> LowBatteryDecision(armed = true, fire = false)
            percent >= threshold + LOW_BATTERY_REARM_MARGIN -> LowBatteryDecision(armed = true, fire = false)
            percent <= threshold && armed && enabled && !quiet -> LowBatteryDecision(armed = false, fire = true)
            else -> LowBatteryDecision(armed, fire = false)
        }
    }

    /**
     * True when [minuteOfDay] falls in the window from [start] up to but not including [end].
     * A window that ends before it starts wraps past midnight. A missing bound or an empty window is never quiet.
     */
    fun inQuietHours(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
    }
}
