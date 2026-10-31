// Ported from OpenStrap analytics/lib/src/onehz/wellness/temp_circadian.dart — per-family units.
package app.circlet.analytics.temperature

import app.circlet.core.AbsentReason
import app.circlet.core.SkinTemperatureUnit
import app.circlet.core.StrapGeneration
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GenerationTemperatureTest {
    @Test fun gen5UsesItsOwnBandInCentiDegrees() {
        val night = List(3600) { i -> if (i < 1200) 3150 else 3300 }
        val gen5 = nightlySkinTemp(night, generation = StrapGeneration.GEN5).value!!
        assertEquals(3300.0, gen5.meanCounts, 1e-9)
        assertEquals(1.0 - 1200.0 / 3600, gen5.settledFraction, 1e-9)
        assertEquals(AbsentReason.NOT_ENOUGH_DATA, nightlySkinTemp(List(100) { 3057 }, generation = StrapGeneration.GEN5).absentReason)
    }

    @Test fun differentTemperatureUnitsNeverSeedEachOthersBaselines() {
        val date = LocalDate.of(2026, 10, 20)
        val counts = (10 downTo 1).map { ago -> NightlyTemp(date.minusDays(ago.toLong()), 930.0 + ago) }
        val centiC = NightlyTemp(date, 3057.0, SkinTemperatureUnit.CENTI_CELSIUS)
        val score = scoreSkinTemp(counts + centiC)
        assertEquals(0, score.baselineNights)
        assertNull(score.deviation.value)
        assertEquals(SkinTemperatureUnit.CENTI_CELSIUS, score.unit)
    }
}
