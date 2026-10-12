package app.circlet.strap.sync

import java.time.Duration
import java.time.Instant

// Ported from OpenStrap edge/lib/sync/sync_policy.dart — BackfillPolicy.
// Manual and the connect drain are not floored. A strap prompt waits 90 seconds.
// Opening the app asks again after 60 seconds. The 15 minute periodic drain allows 30 seconds of timer slack.
enum class DrainTrigger {
    MANUAL,
    CONNECT,
    PROMPT,
    PERIODIC,
    FOREGROUND,
}

object DrainSchedule {
    private val PROMPT_FLOOR: Duration = Duration.ofSeconds(90)
    private val PERIODIC: Duration = Duration.ofMinutes(15)
    private val PERIODIC_SLACK: Duration = Duration.ofSeconds(30)
    private val FOREGROUND_FLOOR: Duration = Duration.ofSeconds(60)

    fun due(trigger: DrainTrigger, now: Instant, lastStart: Instant?): Boolean {
        if (trigger == DrainTrigger.MANUAL || trigger == DrainTrigger.CONNECT) return true
        if (lastStart == null) return true
        val elapsed = Duration.between(lastStart, now)
        return when (trigger) {
            DrainTrigger.PROMPT -> elapsed >= PROMPT_FLOOR
            DrainTrigger.PERIODIC -> elapsed >= PERIODIC.minus(PERIODIC_SLACK)
            DrainTrigger.FOREGROUND -> elapsed >= FOREGROUND_FLOOR
            DrainTrigger.MANUAL, DrainTrigger.CONNECT -> true
        }
    }
}
