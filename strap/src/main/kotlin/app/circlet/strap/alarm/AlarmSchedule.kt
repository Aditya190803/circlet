package app.circlet.strap.alarm

import app.circlet.core.ScheduledAlarm
import app.circlet.core.SleepStage
import app.circlet.core.StrapAlarm
import java.time.Instant
import java.time.ZoneId

// The weekly instant comes from StrapAlarm.nextFire. Smart wake does not move that instant.
object AlarmScheduler {
    /** The soonest enabled alarm strictly after [after]. */
    fun next(alarms: List<StrapAlarm>, after: Instant, zone: ZoneId): ScheduledAlarm? =
        alarms.mapNotNull { alarm ->
            alarm.nextFire(after, zone)?.let { ScheduledAlarm(alarm, it) }
        }.minByOrNull { it.at }

    /** The occurrence to arm once [firedAt] has passed or been buzzed early. */
    fun following(alarms: List<StrapAlarm>, firedAt: Instant, zone: ZoneId): ScheduledAlarm? =
        next(alarms, firedAt, zone)
}

object SmartWake {
    /**
     * True when [alarm] may buzz now, before its scheduled instant, because the wearer
     * is in light sleep and the phone is connected. The scheduled instant itself is unchanged.
     */
    fun shouldFire(
        alarm: StrapAlarm,
        now: Instant,
        zone: ZoneId,
        stage: SleepStage?,
        connected: Boolean,
    ): Boolean {
        if (!connected || !alarm.enabled || alarm.smartWake.isZero || alarm.smartWake.isNegative) return false
        if (stage != SleepStage.LIGHT) return false
        val scheduled = alarm.nextFire(now.minusMillis(1), zone) ?: return false
        val windowStart = scheduled.minus(alarm.smartWake)
        return !now.isBefore(windowStart) && now.isBefore(scheduled)
    }
}

sealed interface AlarmTick {
    data object None : AlarmTick
    data object Disarm : AlarmTick
    data class Rearm(val at: Instant) : AlarmTick
    data class FireEarly(val alarm: StrapAlarm, val occurrence: Instant, val then: Instant?) : AlarmTick
}

/**
 * One look at the clock. A passed occurrence is re-armed for the next weekday.
 * Light sleep inside the window buzzes early and arms the occurrence after that one,
 * so the strap does not buzz again at the original time.
 */
fun decideTick(
    alarms: List<StrapAlarm>,
    armedFor: Instant?,
    now: Instant,
    zone: ZoneId,
    stage: SleepStage?,
    connected: Boolean,
    alreadyBuzzed: (StrapAlarm, Instant) -> Boolean,
): AlarmTick {
    if (armedFor != null && !now.isBefore(armedFor)) {
        val next = AlarmScheduler.following(alarms, armedFor, zone)
        return if (next == null) AlarmTick.Disarm else AlarmTick.Rearm(next.at)
    }
    val early = alarms
        .filter { SmartWake.shouldFire(it, now, zone, stage, connected) }
        .mapNotNull { alarm ->
            val occurrence = alarm.nextFire(now.minusMillis(1), zone) ?: return@mapNotNull null
            if (alreadyBuzzed(alarm, occurrence)) null else alarm to occurrence
        }
        .minByOrNull { it.second }
        ?: return AlarmTick.None
    val (alarm, occurrence) = early
    val then = alarm.nextFire(occurrence, zone)
    return AlarmTick.FireEarly(alarm, occurrence, then)
}
