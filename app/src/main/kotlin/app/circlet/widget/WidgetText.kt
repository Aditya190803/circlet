package app.circlet.widget

import app.circlet.core.DaySummary
import app.circlet.core.LinkState
import app.circlet.core.StrapState
import java.util.Locale
import kotlin.math.roundToInt

private const val ABSENT = "—"

/** What the Today widget shows. A null field is not scored and draws as a dash. */
internal data class TodayReading(val recovery: Int?, val sleep: Int?, val strain: Double?) {
    val recoveryText: String get() = recovery?.let { "$it%" } ?: ABSENT
    val sleepText: String get() = sleep?.let { "$it%" } ?: ABSENT
    val strainText: String get() = strain?.let { String.format(Locale.US, "%.1f", it) } ?: ABSENT

    companion object {
        fun of(day: DaySummary) = TodayReading(
            recovery = day.recovery.score.value,
            sleep = day.sleepPerformance.percent.value,
            strain = day.strain.score.value,
        )
    }
}

/** What the Strap battery widget shows. */
internal data class BatteryReading(val percent: Int?, val charging: Boolean?, val link: LinkState) {
    val percentText: String get() = percent?.let { "$it%" } ?: ABSENT

    val statusText: String
        get() = when {
            link == LinkState.UNPAIRED -> "NO STRAP"
            charging == true -> "CHARGING"
            link == LinkState.CONNECTED -> "CONNECTED"
            else -> "NOT CONNECTED"
        }

    companion object {
        fun of(strap: StrapState) = BatteryReading(strap.batteryPercent?.roundToInt(), strap.charging, strap.link)
    }
}
