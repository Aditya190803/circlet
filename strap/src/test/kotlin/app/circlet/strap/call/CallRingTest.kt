package app.circlet.strap.call

import app.circlet.core.CallSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRingTest {
    private fun notice(
        category: String? = "call",
        callType: Int? = null,
        fullScreen: Boolean = false,
        chronometer: Boolean = false,
        phoneRinging: Boolean = false,
    ) = CallNotice("com.samsung.android.incallui", category, callType, fullScreen, chronometer, phoneRinging)

    @Test
    fun incomingCallStyleRings() {
        assertTrue(ringing(notice(callType = CALL_TYPE_INCOMING)))
    }

    @Test
    fun ongoingCallStyleDoesNotRingWithoutTheRinger() {
        assertFalse(ringing(notice(callType = 2, fullScreen = true)))
    }

    @Test
    fun fullScreenCallWithoutStyleRings() {
        assertTrue(ringing(notice(fullScreen = true)))
    }

    @Test
    fun answeredCallShowsTimerAndStops() {
        assertFalse(ringing(notice(fullScreen = true, chronometer = true, phoneRinging = true)))
    }

    @Test
    fun dialerCallRingsWhileTheRingerSoundsWhateverItsShape() {
        assertTrue(ringing(notice(callType = 2, phoneRinging = true)))
        assertTrue(ringing(notice(category = null, fullScreen = true, phoneRinging = true)))
        assertFalse(ringing(notice(callType = 2)))
    }

    @Test
    fun otherDialerNoticesDoNotRingEvenWhileTheRingerSounds() {
        assertFalse(ringing(notice(category = "missed_call", phoneRinging = true)))
        assertFalse(ringing(notice(category = null, phoneRinging = false)))
    }

    @Test
    fun sourcesMatchDialersAndWhatsApp() {
        assertEquals(CallSource.WHATSAPP, sourceOf("com.whatsapp", "com.samsung.android.dialer"))
        assertEquals(CallSource.PHONE, sourceOf("com.samsung.android.incallui", "com.samsung.android.dialer"))
        assertEquals(CallSource.PHONE, sourceOf("com.example.dialer", "com.example.dialer"))
        assertNull(sourceOf("com.example.chat", "com.samsung.android.dialer"))
    }
}
