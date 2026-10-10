@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.circlet.strap

import app.circlet.strap.link.GattOperationQueue
import app.circlet.strap.link.QueueOutcome
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationQueueTest {
    @Test
    fun secondOperationWaitsForTheFirst() = runTest {
        val queue = GattOperationQueue(Duration.ofSeconds(5))
        val order = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        launch {
            queue.submit {
                order += 1
                gate.await()
                order += 2
            }
        }
        launch {
            queue.submit { order += 3 }
        }
        runCurrent()
        assertEquals(listOf(1), order)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun timeoutReleasesTheQueue() = runTest {
        val queue = GattOperationQueue(Duration.ofMillis(100))
        val timed = queue.submit {
            delay(1_000)
            "late"
        }
        assertTrue(timed is QueueOutcome.TimedOut)
        val next = queue.submit { 7 }
        assertEquals(QueueOutcome.Done(7), next)
    }

    @Test
    fun failureReleasesTheQueue() = runTest {
        val queue = GattOperationQueue(Duration.ofSeconds(1))
        val failed = queue.submit<Int> { error("radio") }
        assertTrue(failed is QueueOutcome.Failed)
        assertEquals("radio", (failed as QueueOutcome.Failed).detail)
        val next = queue.submit { 4 }
        assertEquals(QueueOutcome.Done(4), next)
    }

    @Test
    fun cancellationReleasesTheQueue() = runTest {
        val queue = GattOperationQueue(Duration.ofSeconds(5))
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            queue.submit { gate.await() }
        }
        runCurrent()
        job.cancel()
        runCurrent()
        val next = queue.submit { 1 }
        assertEquals(QueueOutcome.Done(1), next)
    }
}
