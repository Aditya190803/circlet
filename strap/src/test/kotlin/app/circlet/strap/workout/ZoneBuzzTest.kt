package app.circlet.strap.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneBuzzTest {
    private fun step(state: ZoneBuzzState, zone: Int?, seconds: Int, enabled: Boolean = true, paused: Boolean = false) =
        stepZoneBuzz(state, zone, seconds * 1000L, enabled, paused)

    private fun settledIn(zone: Int) = step(ZoneBuzzState(), zone, 0).state

    @Test
    fun theFirstReadingSettlesWithoutBuzzing() {
        val first = step(ZoneBuzzState(), 2, 0)
        assertFalse(first.buzz)
        assertEquals(2, first.state.settled)
    }

    @Test
    fun aNewZoneBuzzesOnlyAfterItHeldTenSeconds() {
        var state = settledIn(2)
        state = step(state, 3, 100).state
        assertFalse(step(state, 3, 109).buzz)
        val done = step(state, 3, 110)
        assertTrue(done.buzz)
        assertEquals(3, done.state.settled)
    }

    @Test
    fun fallingBackBeforeTheHoldCancelsTheChange() {
        var state = settledIn(2)
        state = step(state, 3, 100).state
        state = step(state, 2, 105).state
        assertNull(state.candidate)
        state = step(state, 3, 108).state
        assertFalse(step(state, 3, 115).buzz)
        assertTrue(step(state, 3, 118).buzz)
    }

    @Test
    fun aChangeWaitsForThirtySecondsSinceTheLastBuzz() {
        var state = settledIn(2)
        state = step(state, 3, 100).state
        state = step(state, 3, 110).state
        state = step(state, 4, 112).state
        assertFalse(step(state, 4, 125).buzz)
        state = step(state, 4, 125).state
        assertFalse(step(state, 4, 139).buzz)
        assertTrue(step(state, 4, 140).buzz)
    }

    @Test
    fun nothingBuzzesWhilePausedAndAPendingChangeIsDropped() {
        var state = settledIn(2)
        state = step(state, 3, 100).state
        val paused = step(state, 3, 120, paused = true)
        assertFalse(paused.buzz)
        assertNull(paused.state.candidate)
        assertFalse(step(paused.state, 3, 121).buzz)
    }

    @Test
    fun nothingBuzzesWhenTheSettingIsOffAndEnablingDoesNotReplayAnOldChange() {
        var state = settledIn(2)
        state = step(state, 3, 100, enabled = false).state
        assertFalse(step(state, 3, 130, enabled = false).buzz)
        assertEquals(3, state.settled)
        assertFalse(step(state, 3, 140).buzz)
    }

    @Test
    fun aMissingZoneKeepsTheSettledZone() {
        val state = settledIn(2)
        val step = step(state, null, 50)
        assertFalse(step.buzz)
        assertEquals(2, step.state.settled)
    }
}
