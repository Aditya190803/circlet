package app.circlet.data.pipeline

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until [signals] has been idle for [quietMs].
 * A history drain commits every few seconds; this collapses that burst into one recompute.
 */
internal class QuietPeriod(private val quietMs: Long) {
    init {
        require(quietMs >= 0L)
    }

    suspend fun await(signals: Channel<Unit>) {
        var again = true
        while (again) {
            again = withTimeoutOrNull(quietMs) { signals.receive() } != null
        }
    }
}
