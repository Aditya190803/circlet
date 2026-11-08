package app.circlet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkinTemperatureTest {
    private fun skin(mean: Double?, unit: SkinTemperatureUnit, imported: Double? = null) = SkinTemperature(
        deviation = Metric.absent(AbsentReason.CALIBRATING),
        baselineNights = 0,
        flag = SkinTempFlag.NOT_ENOUGH_BASELINE,
        settledMeanCounts = mean,
        importedCelsius = imported,
        unit = unit,
    )

    @Test
    fun gen5CentiDegreesReadAsCelsius() {
        assertEquals(33.87, skin(3387.0, SkinTemperatureUnit.CENTI_CELSIUS).celsius!!, 1e-9)
    }

    @Test
    fun gen4CountsAreNeverDegrees() {
        assertNull(skin(30412.0, SkinTemperatureUnit.ADC_COUNTS).celsius)
        assertNull(skin(null, SkinTemperatureUnit.CENTI_CELSIUS).celsius)
    }

    @Test
    fun importedWhoopFigureWins() {
        assertEquals(34.1, skin(30412.0, SkinTemperatureUnit.ADC_COUNTS, imported = 34.1).celsius!!, 1e-9)
    }
}
