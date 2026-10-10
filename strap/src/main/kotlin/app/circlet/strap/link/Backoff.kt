package app.circlet.strap.link

import java.time.Duration

// Ported from OpenStrap edge/lib/ble/ble_state.dart — ReconnectPolicy.delayFor,
// without the jitter so a test can assert the delay.
/**
 * Bounded exponential backoff. Attempt 1 waits 2 seconds, then 4, 8, 16, and 30.
 */
object Backoff {
    fun delay(attempt: Int): Duration {
        val n = attempt.coerceAtLeast(1)
        val shift = (n - 1).coerceAtMost(4)
        val seconds = (2L shl shift).coerceAtMost(30L)
        return Duration.ofSeconds(seconds)
    }
}
