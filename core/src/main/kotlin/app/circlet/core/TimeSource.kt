package app.circlet.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The clock seam. Pure modules take a [TimeSource]; they do not read the system clock.
 * [now] is an instant on the UTC timeline. [zone] is the zone used to decide local dates.
 */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
}

/** The device clock. Only the application edge should construct this. */
object SystemTimeSource : TimeSource {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * A clock tests and the demo backend can move.
 * [advance] is the only way the instant changes.
 */
class ManualTimeSource(
    start: Instant,
    private val zoneId: ZoneId,
) : TimeSource {
    var instant: Instant = start
        private set

    fun advance(by: Duration) {
        instant = instant.plus(by)
    }

    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
}
