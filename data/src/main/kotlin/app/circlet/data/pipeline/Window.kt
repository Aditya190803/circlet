package app.circlet.data.pipeline

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val SLEEP_EDGE = LocalTime.of(18, 0)

/**
 * Local dates a sample can change.
 * A second belongs to its own calendar day, and also to the next day when it falls at or after
 * 18:00, because that day's inputs start at 18:00 the evening before.
 */
internal fun affectedDates(epochSeconds: Iterable<Long>, zone: ZoneId): Set<LocalDate> {
    val dates = LinkedHashSet<LocalDate>()
    for (epoch in epochSeconds) {
        val zoned = Instant.ofEpochSecond(epoch).atZone(zone)
        val date = zoned.toLocalDate()
        dates += date
        if (!zoned.toLocalTime().isBefore(SLEEP_EDGE)) dates += date.plusDays(1)
    }
    return dates
}

/** Inclusive epoch-second bounds: 18:00 on the previous local day through the last second of [date]. */
internal fun sampleWindow(date: LocalDate, zone: ZoneId): LongRange {
    val start = date.minusDays(1).atTime(SLEEP_EDGE).atZone(zone).toEpochSecond()
    val endExclusive = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return start until endExclusive
}
