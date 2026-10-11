package app.circlet.ui.journal

import app.circlet.core.CorrelationMetric
import app.circlet.core.JournalTag
import app.circlet.core.TagCorrelation
import app.circlet.ui.design.decimal
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/** Each side of a correlation needs at least this many logged days. Same rule the core uses for an effect. */
const val MIN_DAYS_PER_SIDE = 2

/** Longest name for a custom tag. */
const val MAX_TAG_NAME = 24

/** Longest note for one day. */
const val MAX_NOTE = 500

/** The tag ids for a day after one tag is switched on or off. The input set is not changed. */
fun toggleTag(tagIds: Set<String>, tagId: String): Set<String> =
    if (tagId in tagIds) tagIds - tagId else tagIds + tagId

/** Built-in tags stay in the list. Only custom tags can be removed. */
fun JournalTag.canRemove(): Boolean = !builtIn

/** A correlation counts as having data only when its effect is measured and both sides have enough days. */
fun TagCorrelation.hasEnoughData(): Boolean =
    effect.value != null && daysWith >= MIN_DAYS_PER_SIDE && daysWithout >= MIN_DAYS_PER_SIDE

/** A correlation with a real effect to draw. */
data class MeasuredEffect(val correlation: TagCorrelation, val effect: Double)

/** One metric's rows, split into effects to draw and tags that are still too thin to show one. */
data class InsightSplit(val measured: List<MeasuredEffect>, val notEnough: List<TagCorrelation>)

/** Splits the rows for [metric], in the order the tags are listed. */
fun splitCorrelations(correlations: List<TagCorrelation>, metric: CorrelationMetric, tags: List<JournalTag>): InsightSplit {
    val order = tags.withIndex().associate { (index, tag) -> tag.id to index }
    val rows = correlations
        .filter { it.metric == metric }
        .sortedBy { order[it.tagId] ?: Int.MAX_VALUE }
    val measured = rows.mapNotNull { row ->
        if (row.hasEnoughData()) row.effect.value?.let { MeasuredEffect(row, it) } else null
    }
    val notEnough = rows.filterNot { it.hasEnoughData() }
    return InsightSplit(measured, notEnough)
}

/**
 * The bar for [effect], from -1 to 1 relative to the largest effect in [measured].
 * Null when nothing is measured, so the bar is drawn empty.
 */
fun barFraction(effect: Double, measured: List<MeasuredEffect>): Float? {
    val largest = measured.maxOfOrNull { abs(it.effect) } ?: return null
    if (largest == 0.0) return 0f
    return (effect / largest).toFloat()
}

/** Why a new custom tag name cannot be saved, or null when it can. */
fun tagNameProblem(name: String, existing: List<JournalTag>): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Give the tag a name."
        trimmed.length > MAX_TAG_NAME -> "Keep the name to $MAX_TAG_NAME characters."
        existing.any { it.name.equals(trimmed, ignoreCase = true) } -> "A tag called $trimmed already exists."
        else -> null
    }
}

fun newCustomTagId(): String = "custom-${UUID.randomUUID()}"

/** A date moved by [days], never after [today]. */
fun shiftDay(date: LocalDate, days: Long, today: LocalDate): LocalDate = clampToToday(date.plusDays(days), today)

fun clampToToday(date: LocalDate, today: LocalDate): LocalDate = if (date > today) today else date

fun CorrelationMetric.label(): String = when (this) {
    CorrelationMetric.RECOVERY -> "RECOVERY"
    CorrelationMetric.HRV -> "HRV"
    CorrelationMetric.SLEEP_PERFORMANCE -> "SLEEP"
}

fun CorrelationMetric.unit(): String = when (this) {
    CorrelationMetric.RECOVERY -> "PTS"
    CorrelationMetric.HRV -> "MS"
    CorrelationMetric.SLEEP_PERFORMANCE -> "%"
}

/** An effect with its sign, one decimal place. */
fun signedEffect(value: Double): String = (if (value > 0) "+" else "") + value.decimal(1)
