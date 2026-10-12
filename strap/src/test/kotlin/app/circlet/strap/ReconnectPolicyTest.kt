package app.circlet.strap

import app.circlet.strap.link.Backoff
import app.circlet.strap.link.ReconnectAction
import app.circlet.strap.link.ReconnectPolicy
import app.circlet.strap.sync.DrainSchedule
import app.circlet.strap.sync.DrainTrigger
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun unpairedDoesNothing() {
        assertEquals(ReconnectAction.NONE, decide(paired = false, bluetoothOn = true, connected = false))
    }

    @Test
    fun bluetoothOffWaitsForTheRadio() {
        assertEquals(
            ReconnectAction.WAIT_FOR_RADIO,
            decide(paired = true, bluetoothOn = false, connected = false),
        )
    }

    @Test
    fun aDropWithNoAttemptStartsAutoConnect() {
        assertEquals(
            ReconnectAction.AUTO_CONNECT,
            decide(paired = true, bluetoothOn = true, connected = false, attemptRunning = null),
        )
    }

    @Test
    fun aYoungAttemptIsLeftAlone() {
        assertEquals(
            ReconnectAction.NONE,
            decide(paired = true, bluetoothOn = true, connected = false, attemptRunning = Duration.ofMinutes(1)),
        )
    }

    @Test
    fun aStaleAttemptIsRenewed() {
        assertEquals(
            ReconnectAction.RENEW,
            decide(
                paired = true,
                bluetoothOn = true,
                connected = false,
                attemptRunning = ReconnectPolicy.STALE_ATTEMPT,
            ),
        )
    }

    @Test
    fun silenceOnALiveLinkIsRenewed() {
        assertEquals(
            ReconnectAction.RENEW,
            decide(paired = true, bluetoothOn = true, connected = true, silentFor = ReconnectPolicy.LIVENESS),
        )
    }

    @Test
    fun aQuietButFreshLinkStaysUp() {
        assertEquals(
            ReconnectAction.NONE,
            decide(paired = true, bluetoothOn = true, connected = true, silentFor = Duration.ofSeconds(30)),
        )
    }

    @Test
    fun backoffCapsAtThirtySeconds() {
        assertEquals(Duration.ofSeconds(2), Backoff.delay(1))
        assertEquals(Duration.ofSeconds(4), Backoff.delay(2))
        assertEquals(Duration.ofSeconds(8), Backoff.delay(3))
        assertEquals(Duration.ofSeconds(16), Backoff.delay(4))
        assertEquals(Duration.ofSeconds(30), Backoff.delay(5))
        assertEquals(Duration.ofSeconds(30), Backoff.delay(9))
    }

    @Test
    fun promptAndPeriodicDrainsAreFloored() {
        val start = Instant.parse("2026-10-12T00:00:00Z")
        assertTrue(DrainSchedule.due(DrainTrigger.MANUAL, start, start))
        assertTrue(DrainSchedule.due(DrainTrigger.CONNECT, start, start))
        assertFalse(DrainSchedule.due(DrainTrigger.PROMPT, start.plusSeconds(89), start))
        assertTrue(DrainSchedule.due(DrainTrigger.PROMPT, start.plusSeconds(90), start))
        assertFalse(DrainSchedule.due(DrainTrigger.PERIODIC, start.plus(Duration.ofMinutes(14)), start))
        assertTrue(DrainSchedule.due(DrainTrigger.PERIODIC, start.plus(Duration.ofMinutes(14).plusSeconds(30)), start))
        assertFalse(DrainSchedule.due(DrainTrigger.FOREGROUND, start.plusSeconds(59), start))
        assertTrue(DrainSchedule.due(DrainTrigger.FOREGROUND, start.plusSeconds(60), start))
    }

    private fun decide(
        paired: Boolean,
        bluetoothOn: Boolean,
        connected: Boolean,
        attemptRunning: Duration? = null,
        silentFor: Duration? = null,
    ): ReconnectAction = ReconnectPolicy.decide(paired, bluetoothOn, connected, attemptRunning, silentFor)
}
