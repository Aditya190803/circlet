package app.circlet.strap.link

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One GATT operation at a time, each bounded by [timeout].
 *
 * The Android stack completes one write, descriptor write, or discovery before the next
 * starts. A timeout or a failure releases the queue so the following operation can run.
 */
class GattOperationQueue(
    private val timeout: java.time.Duration,
) {
    private val mutex = Mutex()

    suspend fun <T> submit(block: suspend () -> T): QueueOutcome<T> = mutex.withLock {
        try {
            withTimeout(timeout.toMillis()) {
                QueueOutcome.Done(block())
            }
        } catch (timeout: TimeoutCancellationException) {
            QueueOutcome.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Throwable) {
            QueueOutcome.Failed(failed.message ?: failed.javaClass.simpleName)
        }
    }
}

sealed interface QueueOutcome<out T> {
    data class Done<T>(val value: T) : QueueOutcome<T>
    data object TimedOut : QueueOutcome<Nothing>
    data class Failed(val detail: String) : QueueOutcome<Nothing>
}
