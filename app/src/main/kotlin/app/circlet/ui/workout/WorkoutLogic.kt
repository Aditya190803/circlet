package app.circlet.ui.workout

import app.circlet.core.Activity
import app.circlet.core.Sport
import app.circlet.ui.design.TimeFormat
import app.circlet.ui.design.label
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Two selected activities can merge when the gap between them is at most this long. */
internal val MERGE_MAX_GAP: Duration = Duration.ofMinutes(10)

internal fun newestFirst(activities: List<Activity>): List<Activity> =
    activities.sortedByDescending { it.start }

internal fun localDay(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).toLocalDate()

/**
 * True when the selected activities form one run that can be merged: at least two, all on the same
 * local day, each within [MERGE_MAX_GAP] of the next, and no unselected activity of [day] starts
 * inside the run.
 */
internal fun canMerge(day: List<Activity>, selectedIds: Set<String>, zone: ZoneId): Boolean {
    val picked = day.filter { it.id in selectedIds }.sortedBy { it.start }
    if (picked.size < 2 || picked.size != selectedIds.size) return false
    if (picked.map { localDay(it.start, zone) }.toSet().size != 1) return false
    val adjacent = picked.zipWithNext().all { (a, b) ->
        Duration.between(a.end, b.start).abs() <= MERGE_MAX_GAP
    }
    if (!adjacent) return false
    val first = picked.first().start
    val last = picked.last().end
    return day.none { it.id !in selectedIds && it.start >= first && it.start < last }
}

/** "42 MIN" under an hour, "1 H 05 MIN" from an hour up. A reversed span reads as zero. */
internal fun durationText(start: Instant, end: Instant): String {
    val minutes = Duration.between(start, end).toMinutes().coerceAtLeast(0L)
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours == 0L) "$rest MIN" else "$hours H ${rest.toString().padStart(2, '0')} MIN"
}

/** "05:12" under an hour, "1:02:05" from an hour up. A negative elapsed time reads as zero. */
internal fun elapsedText(elapsed: Duration): String {
    val total = elapsed.seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours == 0L) "%02d:%02d".format(minutes, seconds) else "%d:%02d:%02d".format(hours, minutes, seconds)
}

internal fun clockText(instant: Instant, format: TimeFormat): String {
    val pattern = if (format.twentyFourHour) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.US).format(instant.atZone(format.zone))
}

internal fun timeRange(start: Instant, end: Instant, format: TimeFormat): String =
    "${clockText(start, format)} - ${clockText(end, format)}"

/** Sports whose label contains [query], ignoring case and surrounding spaces. A blank query keeps all. */
internal fun filterSports(query: String): List<Sport> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return Sport.entries.toList()
    return Sport.entries.filter { it.label().contains(wanted, ignoreCase = true) }
}
