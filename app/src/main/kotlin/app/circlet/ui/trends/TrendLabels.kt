package app.circlet.ui.trends

import app.circlet.core.MetricKind
import app.circlet.core.RecordKind
import app.circlet.ui.design.decimal
import app.circlet.ui.design.grouped
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
}

/** Strain and VO2 max keep one decimal. Everything else is shown whole. */
fun MetricKind.format(value: Double): String = when (this) {
    MetricKind.STRAIN, MetricKind.VO2_MAX, MetricKind.RESPIRATORY_RATE, MetricKind.HRV -> value.decimal(1)
    else -> value.roundToInt().grouped()
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

fun RecordKind.format(value: Double): String = when (this) {
    RecordKind.HIGHEST_STRAIN -> value.decimal(1)
    else -> value.roundToInt().grouped()
}
