package app.circlet.core

import java.time.Duration
import java.time.Instant

/**
 * The user's correction to one night.
 *
 * [start] and [end] replace the detected main sleep window together; both are null when the
 * detected window stands. [deleted] removes the main sleep. [rejectedNaps] are the start
 * instants of detected naps the user does not count.
 */
data class SleepEdit(
    val start: Instant? = null,
    val end: Instant? = null,
    val deleted: Boolean = false,
    val rejectedNaps: Set<Instant> = emptySet(),
) {
    val window: Pair<Instant, Instant>?
        get() = if (!deleted && start != null && end != null) start to end else null

    val isEmpty: Boolean get() = window == null && !deleted && rejectedNaps.isEmpty()
}

/**
 * Why [start] and [end] cannot be a sleep window, as a sentence safe to show.
 * Null when the window is usable: the wake is after the bed time, not after [now],
 * and the night is shorter than 24 hours.
 */
fun rejectedSleepWindow(start: Instant, end: Instant, now: Instant): String? {
    if (!end.isAfter(start)) return "Wake time has to be after bed time."
    if (end.isAfter(now)) return "Wake time cannot be in the future."
    if (Duration.between(start, end) >= Duration.ofHours(24)) return "Sleep has to be shorter than 24 hours."
    return null
}
