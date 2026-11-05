package app.circlet.strap.call

import app.circlet.core.CallSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallBuzzTest {
    private class Haptics : CallHaptics {
        var pulses = 0
        var stops = 0

        override suspend fun pulse() {
            pulses++
        }

        override suspend fun stop() {
            stops++
        }
    }

    private fun TestScope.buzz(haptics: Haptics, enabled: Set<CallSource> = CallSource.entries.toSet()) =
        CallBuzz(backgroundScope, { enabled }, haptics)

    @Test
    fun aRingingCallPulsesUntilItsNotificationGoes() = runTest {
        val haptics = Haptics()
        val calls = buzz(haptics)
        calls.posted("call", CallSource.PHONE, ringing = true)
        runCurrent()
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * 2)
        runCurrent()
        assertEquals(3, haptics.pulses)
        calls.removed("call")
        runCurrent()
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * 5)
        assertEquals(3, haptics.pulses)
        assertEquals(1, haptics.stops)
    }

    @Test
    fun aTapSilencesTheCallAndAnUpdateDoesNotRestartIt() = runTest {
        val haptics = Haptics()
        val calls = buzz(haptics)
        calls.posted("call", CallSource.WHATSAPP, ringing = true)
        runCurrent()
        assertTrue(calls.silence())
        runCurrent()
        calls.posted("call", CallSource.WHATSAPP, ringing = true)
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * 5)
        runCurrent()
        assertEquals(1, haptics.pulses)
        assertEquals(1, haptics.stops)
        assertFalse(calls.silence())
    }

    @Test
    fun aTapWithNothingRingingKeepsItsUsualAction() = runTest {
        assertFalse(buzz(Haptics()).silence())
    }

    @Test
    fun aSourceTheUserLeftOffNeverBuzzes() = runTest {
        val haptics = Haptics()
        val calls = buzz(haptics, enabled = setOf(CallSource.PHONE))
        calls.posted("wa", CallSource.WHATSAPP, ringing = true)
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * 3)
        runCurrent()
        assertEquals(0, haptics.pulses)
    }

    @Test
    fun anAnsweredCallStopsTheBuzz() = runTest {
        val haptics = Haptics()
        val calls = buzz(haptics)
        calls.posted("call", CallSource.PHONE, ringing = true)
        runCurrent()
        calls.posted("call", CallSource.PHONE, ringing = false)
        runCurrent()
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * 3)
        assertEquals(1, haptics.pulses)
        assertEquals(1, haptics.stops)
    }

    @Test
    fun aCallThatRingsOutStopsAfterTheLastPulse() = runTest {
        val haptics = Haptics()
        val calls = buzz(haptics)
        calls.posted("call", CallSource.PHONE, ringing = true)
        advanceTimeBy(CALL_PULSE_GAP_MILLIS * (CALL_PULSES + 5))
        runCurrent()
        assertEquals(CALL_PULSES, haptics.pulses)
        assertFalse(calls.silence())
    }

    @Test
    fun theDialerAndWhatsAppAreTheOnlySources() {
        assertEquals(CallSource.PHONE, sourceOf("com.oem.phone", defaultDialer = "com.oem.phone"))
        assertEquals(CallSource.PHONE, sourceOf("com.google.android.dialer", defaultDialer = null))
        assertEquals(CallSource.WHATSAPP, sourceOf("com.whatsapp", defaultDialer = null))
        assertEquals(CallSource.WHATSAPP, sourceOf("com.whatsapp.w4b", defaultDialer = null))
        assertNull(sourceOf("org.telegram.messenger", defaultDialer = "com.google.android.dialer"))
    }

    @Test
    fun onlyAnIncomingCallRings() {
        val incoming = CallNotice("com.whatsapp", "call", callType = null, fullScreen = true, chronometer = false)
        assertTrue(ringing(incoming))
        assertFalse(ringing(incoming.copy(chronometer = true)))
        assertFalse(ringing(incoming.copy(fullScreen = false)))
        assertFalse(ringing(incoming.copy(category = "msg")))
        assertTrue(ringing(incoming.copy(callType = CALL_TYPE_INCOMING, fullScreen = false)))
        assertFalse(ringing(incoming.copy(callType = 2)))
    }
}
