package app.circlet.strap.link

import java.time.Duration

// Ported from OpenStrap edge/lib/sync/sync_policy.dart — superviseReconnect,
// and edge/lib/ble/ble_engine.dart — the 120s liveness fuse (kLivenessFuseSeconds).
/**
 * What to do with a paired strap that is not in a healthy session.
 *
 * [AUTO_CONNECT] is the background connect after a drop, or when Bluetooth has just come back.
 * [RENEW] drops a connect that has been silent for [LIVENESS], or an attempt that has been
 * pending for [STALE_ATTEMPT], and starts again.
 * [WAIT_FOR_RADIO] means Bluetooth is off; the loop starts when it comes back.
 */
enum class ReconnectAction {
    NONE,
    AUTO_CONNECT,
    RENEW,
    WAIT_FOR_RADIO,
}

object ReconnectPolicy {
    val LIVENESS: Duration = Duration.ofSeconds(120)
    val STALE_ATTEMPT: Duration = Duration.ofMinutes(25)

    fun decide(
        paired: Boolean,
        bluetoothOn: Boolean,
        connected: Boolean,
        attemptRunning: Duration?,
        silentFor: Duration?,
    ): ReconnectAction {
        if (!paired) return ReconnectAction.NONE
        if (!bluetoothOn) return ReconnectAction.WAIT_FOR_RADIO
        if (connected) {
            if (silentFor != null && silentFor >= LIVENESS) return ReconnectAction.RENEW
            return ReconnectAction.NONE
        }
        if (attemptRunning != null && attemptRunning >= STALE_ATTEMPT) return ReconnectAction.RENEW
        if (attemptRunning == null) return ReconnectAction.AUTO_CONNECT
        return ReconnectAction.NONE
    }
}
