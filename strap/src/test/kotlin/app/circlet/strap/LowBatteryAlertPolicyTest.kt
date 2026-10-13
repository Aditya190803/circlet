package app.circlet.strap.notify

import app.circlet.core.StrapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryAlertPolicyTest {
    private fun step(armed: Boolean, percent: Double?, charging: Boolean? = false, enabled: Boolean = true, quiet: Boolean = false) =
        LowBatteryAlertPolicy.evaluate(armed, StrapState(batteryPercent = percent, charging = charging), 20, enabled, quiet)

    @Test
    fun firesOnceWhenBatteryReachesThreshold() {
        val first = step(armed = true, percent = 20.0)
        assertTrue(first.fire)
        assertFalse(first.armed)
        assertEquals(LowBatteryDecision(armed = false, fire = false), step(armed = false, percent = 19.0))
    }

    @Test
    fun staysQuietAboveThresholdAndWithoutAReading() {
        assertEquals(LowBatteryDecision(true, false), step(armed = true, percent = 21.0))
        assertEquals(LowBatteryDecision(true, false), step(armed = true, percent = null))
        assertEquals(LowBatteryDecision(false, false), step(armed = false, percent = null))
    }

    @Test
    fun chargingNeverFiresAndRearms() {
        assertEquals(LowBatteryDecision(true, false), step(armed = true, percent = 5.0, charging = true))
        assertEquals(LowBatteryDecision(true, false), step(armed = false, percent = 5.0, charging = true))
    }

    @Test
    fun rearmsFiveAbovePlateauNotBefore() {
        assertEquals(LowBatteryDecision(false, false), step(armed = false, percent = 24.0))
        assertEquals(LowBatteryDecision(true, false), step(armed = false, percent = 25.0))
    }

    @Test
    fun quietOrDisabledKeepsTheAlertPending() {
        assertEquals(LowBatteryDecision(true, false), step(armed = true, percent = 10.0, quiet = true))
        assertEquals(LowBatteryDecision(true, false), step(armed = true, percent = 10.0, enabled = false))
        assertTrue(step(armed = true, percent = 10.0).fire)
    }

    @Test
    fun quietHoursSameDayWindow() {
        assertTrue(LowBatteryAlertPolicy.inQuietHours(600, 540, 720))
        assertTrue(LowBatteryAlertPolicy.inQuietHours(540, 540, 720))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(720, 540, 720))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(100, 540, 720))
    }

    @Test
    fun quietHoursWrapPastMidnight() {
        assertTrue(LowBatteryAlertPolicy.inQuietHours(23 * 60, 22 * 60, 7 * 60))
        assertTrue(LowBatteryAlertPolicy.inQuietHours(0, 22 * 60, 7 * 60))
        assertTrue(LowBatteryAlertPolicy.inQuietHours(6 * 60 + 59, 22 * 60, 7 * 60))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(7 * 60, 22 * 60, 7 * 60))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(12 * 60, 22 * 60, 7 * 60))
    }

    @Test
    fun quietHoursOffWhenMissingOrEmpty() {
        assertFalse(LowBatteryAlertPolicy.inQuietHours(100, null, 200))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(100, 50, null))
        assertFalse(LowBatteryAlertPolicy.inQuietHours(100, 100, 100))
    }
}
