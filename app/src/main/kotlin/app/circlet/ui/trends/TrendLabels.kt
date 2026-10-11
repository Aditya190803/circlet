package app.circlet.ui.trends

import app.circlet.core.MetricKind
import androidx.compose.ui.graphics.Color
import app.circlet.core.RecordKind
import app.circlet.ui.design.Palette
import app.circlet.ui.design.decimal
import app.circlet.ui.design.grouped
import app.circlet.ui.design.hoursMinutes
import kotlin.math.roundToInt

fun MetricKind.label(): String = when (this) {
    MetricKind.SLEEP_PERFORMANCE -> "Sleep performance"
    MetricKind.SLEEP_DURATION -> "Sleep duration"
    MetricKind.SLEEP_EFFICIENCY -> "Sleep efficiency"
    MetricKind.SLEEP_NEED -> "Sleep need"
    MetricKind.SLEEP_DEBT -> "Sleep debt"
    MetricKind.SLEEP_CONSISTENCY -> "Sleep consistency"
    MetricKind.RECOVERY -> "Recovery"
    MetricKind.STRAIN -> "Strain"
    MetricKind.STRESS -> "Stress"
    MetricKind.RESTING_HEART_RATE -> "Resting heart rate"
    MetricKind.HRV -> "HRV"
    MetricKind.RESPIRATORY_RATE -> "Respiratory rate"
    MetricKind.STEPS -> "Steps"
    MetricKind.CALORIES -> "Calories"
    MetricKind.VO2_MAX -> "VO2 max"
    MetricKind.SKIN_TEMPERATURE -> "Skin temperature"
}

/** Unit shown beside a value. Empty when the number is already unitless (strain, stress, index). */
fun MetricKind.unit(): String = when (this) {
    MetricKind.SLEEP_PERFORMANCE, MetricKind.SLEEP_EFFICIENCY, MetricKind.SLEEP_CONSISTENCY, MetricKind.RECOVERY -> "%"
    MetricKind.SLEEP_DURATION, MetricKind.SLEEP_NEED, MetricKind.SLEEP_DEBT -> "min"
    MetricKind.STRAIN, MetricKind.STRESS -> ""
    MetricKind.RESTING_HEART_RATE -> "bpm"
    MetricKind.HRV -> "ms"
    MetricKind.RESPIRATORY_RATE -> "br/min"
    MetricKind.STEPS -> "steps"
    MetricKind.CALORIES -> "kcal"
    MetricKind.VO2_MAX -> "ml/kg/min"
    MetricKind.SKIN_TEMPERATURE -> "SD"
}

/** Durations are minutes, shown as hours and minutes. True for kinds whose value is a span of time. */
fun MetricKind.isDuration(): Boolean = when (this) {
    MetricKind.SLEEP_DURATION, MetricKind.SLEEP_NEED, MetricKind.SLEEP_DEBT -> true
    else -> false
}

/** Strain and VO2 max keep one decimal. Durations read as hours and minutes. Everything else is shown whole. */
fun MetricKind.format(value: Double): String = when (this) {
    MetricKind.STRAIN, MetricKind.VO2_MAX, MetricKind.RESPIRATORY_RATE, MetricKind.HRV, MetricKind.SKIN_TEMPERATURE -> value.decimal(1)
    MetricKind.SLEEP_DURATION, MetricKind.SLEEP_NEED, MetricKind.SLEEP_DEBT -> hoursMinutes(value.roundToInt())
    else -> value.roundToInt().grouped()
}

/** The accent for this metric's sparkline. Recovery and sleep, strain, then the body measures. */
fun MetricKind.sparkColor(): Color = when (this) {
    MetricKind.RECOVERY -> Palette.RecoveryHigh
    MetricKind.SLEEP_PERFORMANCE, MetricKind.SLEEP_DURATION, MetricKind.SLEEP_EFFICIENCY,
    MetricKind.SLEEP_NEED, MetricKind.SLEEP_DEBT, MetricKind.SLEEP_CONSISTENCY -> Palette.Sleep
    MetricKind.STRAIN, MetricKind.STEPS, MetricKind.CALORIES -> Palette.Strain
    MetricKind.STRESS -> Palette.Dim
    MetricKind.RESTING_HEART_RATE, MetricKind.HRV, MetricKind.RESPIRATORY_RATE, MetricKind.VO2_MAX,
    MetricKind.SKIN_TEMPERATURE -> Palette.Teal
}

fun RecordKind.label(): String = when (this) {
    RecordKind.HIGHEST_STRAIN -> "Highest strain"
    RecordKind.LONGEST_SLEEP -> "Longest sleep"
    RecordKind.HIGHEST_RECOVERY -> "Highest recovery"
    RecordKind.HIGHEST_HRV -> "Highest HRV"
    RecordKind.LOWEST_RESTING_HEART_RATE -> "Lowest resting heart rate"
    RecordKind.MOST_STEPS -> "Most steps"
    RecordKind.HIGHEST_HEART_RATE -> "Highest heart rate"
    RecordKind.LONGEST_ACTIVITY -> "Longest activity"
}

fun RecordKind.unit(): String = when (this) {
    RecordKind.HIGHEST_STRAIN -> ""
    RecordKind.LONGEST_SLEEP, RecordKind.LONGEST_ACTIVITY -> "min"
    RecordKind.HIGHEST_RECOVERY -> "%"
    RecordKind.HIGHEST_HRV -> "ms"
    RecordKind.LOWEST_RESTING_HEART_RATE, RecordKind.HIGHEST_HEART_RATE -> "bpm"
    RecordKind.MOST_STEPS -> "steps"
}

fun RecordKind.isDuration(): Boolean = when (this) {
    RecordKind.LONGEST_SLEEP, RecordKind.LONGEST_ACTIVITY -> true
    else -> false
}

fun RecordKind.format(value: Double): String = when (this) {
    RecordKind.HIGHEST_STRAIN -> value.decimal(1)
    RecordKind.LONGEST_SLEEP, RecordKind.LONGEST_ACTIVITY -> hoursMinutes(value.roundToInt())
    else -> value.roundToInt().grouped()
}
