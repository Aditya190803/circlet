package app.circlet.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

/**
 * A haptic alarm stored on the strap.
 *
 * [time] is the local time of day it is set for.
 * [weekdays] are the days it repeats. An empty set does not fire.
 * [smartWake] is how early the strap may fire when it sees light sleep while connected.
 * Zero means smart wake is off.
 */
data class StrapAlarm(
    val id: String,
    val time: LocalTime,
    val weekdays: Set<DayOfWeek>,
    val smartWake: Duration,
    val enabled: Boolean,
) {
    /**
     * The next time this alarm is scheduled to fire strictly after [after], in [zone].
     * Smart wake can buzz earlier than this instant; it does not move the schedule.
     * Returns null when the alarm is disabled or has no weekdays.
     */
    fun nextFire(after: Instant, zone: ZoneId): Instant? {
        if (!enabled || weekdays.isEmpty()) return null
        val startDate = after.atZone(zone).toLocalDate()
        for (day in 0..7) {
            val date = startDate.plusDays(day.toLong())
            if (date.dayOfWeek !in weekdays) continue
            val fire = date.atTime(time).atZone(zone).toInstant()
            if (fire.isAfter(after)) return fire
        }
        return null
    }
}

/** The next alarm that will fire, and the instant it is scheduled for. */
data class ScheduledAlarm(
    val alarm: StrapAlarm,
    val at: Instant,
)

/**
 * The strap's alarm list.
 * [set] inserts or replaces by id. [disable] leaves the alarm in the list with [StrapAlarm.enabled] false.
 * [remove] deletes the alarm and re-arms the strap for what remains.
 * [next] is recomputed from [alarms] and the [TimeSource] whenever the list changes.
 */
interface AlarmController {
    val alarms: StateFlow<List<StrapAlarm>>
    val next: StateFlow<ScheduledAlarm?>

    suspend fun set(alarm: StrapAlarm): Outcome<Unit>

    suspend fun disable(id: String): Outcome<Unit>

    suspend fun remove(id: String): Outcome<Unit>

    suspend fun setSmartWake(id: String, window: Duration): Outcome<Unit>
}
