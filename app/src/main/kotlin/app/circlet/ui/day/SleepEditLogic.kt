package app.circlet.ui.day

import app.circlet.core.SleepEdit
import app.circlet.core.SleepSession
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val DEFAULT_NIGHT = Duration.ofHours(8)

/**
 * Where the bed and wake steppers start: the detected night, or an 11 PM to 7 AM night
 * ending on [date], pulled back so the wake is not after [now].
 */
internal fun suggestedSleepWindow(
    date: LocalDate,
    zone: ZoneId,
    now: Instant,
    detected: SleepSession?,
): Pair<Instant, Instant> {
    if (detected != null) return detected.start.truncatedTo(ChronoUnit.MINUTES) to detected.end.truncatedTo(ChronoUnit.MINUTES)
    val usual = date.atTime(7, 0).atZone(zone).toInstant()
    val wake = minOf(usual, now.truncatedTo(ChronoUnit.MINUTES))
    val bed = if (wake == usual) date.minusDays(1).atTime(23, 0).atZone(zone).toInstant() else wake.minus(DEFAULT_NIGHT)
    return bed to wake
}

internal fun SleepEdit?.withWindow(start: Instant, end: Instant): SleepEdit =
    (this ?: SleepEdit()).copy(start = start, end = end, deleted = false)

internal fun SleepEdit?.withMainRemoved(): SleepEdit =
    (this ?: SleepEdit()).copy(start = null, end = null, deleted = true)

internal fun SleepEdit?.withDetectedMain(): SleepEdit =
    (this ?: SleepEdit()).copy(start = null, end = null, deleted = false)

internal fun SleepEdit?.withNap(start: Instant, counted: Boolean): SleepEdit {
    val edit = this ?: SleepEdit()
    val second = start.truncatedTo(ChronoUnit.SECONDS)
    return edit.copy(rejectedNaps = if (counted) edit.rejectedNaps - second else edit.rejectedNaps + second)
}

/** True when the night has been changed from what was detected. */
internal val SleepEdit?.changesMain: Boolean get() = this != null && (deleted || window != null)
