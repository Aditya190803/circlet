package app.circlet.data.repo

import app.circlet.core.SleepEdit
import app.circlet.data.db.SleepEditEntity
import java.time.Instant

internal fun SleepEditEntity.toEdit(): SleepEdit = SleepEdit(
    start = startEpoch?.let(Instant::ofEpochSecond),
    end = endEpoch?.let(Instant::ofEpochSecond),
    deleted = deleted,
    rejectedNaps = rejectedNaps.split(',').mapNotNull { it.toLongOrNull() }.map(Instant::ofEpochSecond).toSet(),
)

internal fun SleepEdit.toEntity(date: String): SleepEditEntity {
    val window = window
    return SleepEditEntity(
        date = date,
        startEpoch = window?.first?.epochSecond,
        endEpoch = window?.second?.epochSecond,
        deleted = deleted,
        rejectedNaps = rejectedNaps.map { it.epochSecond }.sorted().joinToString(","),
    )
}
