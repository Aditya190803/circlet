package app.circlet.core

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A journal tag, either built in or added by the user.
 * [name] is the label shown on the day.
 */
data class JournalTag(
    val id: String,
    val name: String,
    val builtIn: Boolean,
)

/**
 * What the user logged for one local date.
 * [tagIds] refer to [JournalTag.id]. [note] may be empty.
 */
data class JournalEntry(
    val date: LocalDate,
    val tagIds: Set<String>,
    val note: String,
)

/** The number a tag correlation is measured against. */
enum class CorrelationMetric {
    /** Recovery score points. */
    RECOVERY,

    /** RMSSD, milliseconds. */
    HRV,

    /** Sleep performance, percent. */
    SLEEP_PERFORMANCE,
}

/**
 * How a tag lines up with a metric.
 * [effect] is the mean on days with the tag minus the mean on days without it,
 * in the unit of [metric]. It is absent when either side has fewer than two days.
 */
data class TagCorrelation(
    val tagId: String,
    val metric: CorrelationMetric,
    val effect: Metric<Double>,
    val daysWith: Int,
    val daysWithout: Int,
)

/**
 * Tags, the entry for a day, and correlations against recovery, HRV, and sleep.
 * Saving a built-in tag may rename it. Deleting a built-in tag is rejected.
 */
interface JournalRepository {
    val tags: StateFlow<List<JournalTag>>

    fun observeEntry(date: LocalDate): Flow<JournalEntry?>

    suspend fun saveTag(tag: JournalTag): Outcome<JournalTag>

    suspend fun deleteTag(id: String): Outcome<Unit>

    suspend fun saveEntry(entry: JournalEntry): Outcome<Unit>

    suspend fun correlations(): Outcome<List<TagCorrelation>>
}
