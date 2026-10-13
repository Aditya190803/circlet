package app.circlet.widget

import app.circlet.core.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetTextTest {
    @Test
    fun absentTodayValuesDrawDashes() {
        val reading = TodayReading(recovery = null, sleep = null, strain = null)
        assertEquals("—", reading.recoveryText)
        assertEquals("—", reading.sleepText)
        assertEquals("—", reading.strainText)
    }

    @Test
    fun presentTodayValuesAreFormatted() {
        val reading = TodayReading(recovery = 67, sleep = 88, strain = 12.34)
        assertEquals("67%", reading.recoveryText)
        assertEquals("88%", reading.sleepText)
        assertEquals("12.3", reading.strainText)
    }

    @Test
    fun batteryStatusFollowsLinkAndCharging() {
        assertEquals("CHARGING", BatteryReading(50, true, LinkState.CONNECTED).statusText)
        assertEquals("CONNECTED", BatteryReading(50, false, LinkState.CONNECTED).statusText)
        assertEquals("NOT CONNECTED", BatteryReading(50, null, LinkState.DISCONNECTED).statusText)
        assertEquals("NO STRAP", BatteryReading(null, null, LinkState.UNPAIRED).statusText)
        assertEquals("—", BatteryReading(null, null, LinkState.CONNECTED).percentText)
        assertEquals("50%", BatteryReading(50, null, LinkState.CONNECTED).percentText)
    }
}
