package app.circlet.ui.alarm

import app.circlet.core.ScheduledAlarm
import app.circlet.core.StrapAlarm
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Monday first, the order the weekday chips are drawn in. */
val WEEK_ORDER: List<DayOfWeek> = DayOfWeek.values().toList()

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** The three smart-wake choices, in the order the segmented control shows them. Zero is off. */
val SMART_WAKE_OPTIONS: List<Duration> = listOf(Duration.ZERO, Duration.ofMinutes(15), Duration.ofMinutes(30))

/** Minutes between the marks on the minute stepper. */
const val MINUTE_STEP = 5

/** The soonest enabled alarm strictly after [after], or null when none is enabled and repeating. */
fun earliestNext(alarms: List<StrapAlarm>, after: Instant, zone: ZoneId): ScheduledAlarm? =
    alarms
        .mapNotNull { alarm -> alarm.nextFire(after, zone)?.let { ScheduledAlarm(alarm, it) } }
        .minByOrNull { it.at }

/** Three-letter weekday for a chip, such as "MON". */
fun shortDay(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
    day.getDisplayName(TextStyle.SHORT, locale).uppercase()

/** What the days of an alarm say: "NO DAYS", "EVERY DAY", "WEEKDAYS", "WEEKENDS", or the short names. */
fun describeDays(days: Set<DayOfWeek>, locale: Locale = Locale.getDefault()): String = when {
    days.isEmpty() -> "NO DAYS"
    days.size == 7 -> "EVERY DAY"
    days == WEEKDAYS -> "WEEKDAYS"
    days == WEEKEND -> "WEEKENDS"
    else -> WEEK_ORDER.filter { it in days }.joinToString(", ") { shortDay(it, locale) }
}

/** "06:30" in 24-hour mode, "6:30 AM" otherwise. Matches the clock used across the app. */
fun formatAlarmTime(time: LocalTime, twentyFourHour: Boolean, locale: Locale = Locale.getDefault()): String =
    time.format(DateTimeFormatter.ofPattern(if (twentyFourHour) "HH:mm" else "h:mm a", locale)).uppercase()

/** The hour as the stepper shows it: 0-23 in 24-hour mode, 1-12 otherwise. */
fun displayHour(hour24: Int, twentyFourHour: Boolean): Int = when {
    twentyFourHour -> hour24
    else -> (hour24 + 11) % 12 + 1
}

/** Moves the hour by [delta], wrapping past midnight and noon. */
fun stepHour(hour24: Int, delta: Int): Int = Math.floorMod(hour24 + delta, 24)

/** Moves the minute to the next or previous [MINUTE_STEP] mark, wrapping past the hour. */
fun stepMinute(minute: Int, delta: Int): Int = Math.floorMod((minute / MINUTE_STEP + delta) * MINUTE_STEP, 60)

/** The hour in the AM or PM half of the day, keeping the hour within it. */
fun withPeriod(hour24: Int, pm: Boolean): Int = hour24 % 12 + if (pm) 12 else 0

/** Index into [SMART_WAKE_OPTIONS] for a stored window. Anything between the marks goes to the nearer one. */
fun smartWakeOption(window: Duration): Int = when {
    window.isZero || window.isNegative -> 0
    window.seconds <= 22 * 60 + 30 -> 1
    else -> 2
}

/** The line under the smart-wake control. */
fun smartWakeLine(option: Int): String = when (option) {
    0 -> "Off. The strap buzzes only at the set time."
    1 -> "The strap may buzz up to 15 minutes early when it sees light sleep while connected."
    else -> "The strap may buzz up to 30 minutes early when it sees light sleep while connected."
}

/** Short caption for a list row, empty when smart wake is off. */
fun smartWakeCaption(window: Duration): String = when (smartWakeOption(window)) {
    0 -> ""
    1 -> "SMART WAKE 15 MIN"
    else -> "SMART WAKE 30 MIN"
}
