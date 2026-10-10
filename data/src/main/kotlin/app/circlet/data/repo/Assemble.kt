package app.circlet.data.repo

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.DaySummary
import app.circlet.core.emptyDay
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.codec.DayCodec
import app.circlet.data.db.ActivityEntity
import app.circlet.data.db.DaySummaryEntity
import java.time.LocalDate

/** The activity table wins over the copy stored inside the day payload, so an edit shows up immediately. */
internal fun assemble(date: LocalDate, row: DaySummaryEntity?, activities: List<ActivityEntity>): DaySummary {
    val base = if (row == null) {
        emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
    } else {
        try {
            DayCodec.decode(row.payload).copy(date = date)
        } catch (error: Exception) {
            emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
        }
    }
    return base.copy(activities = activities.mapNotNull { it.visibleActivity() })
}

internal fun ActivityEntity.visibleActivity(): Activity? {
    if (suppressed) return null
    return try {
        ActivityCodec.decode(payload)
    } catch (error: Exception) {
        null
    }
}
