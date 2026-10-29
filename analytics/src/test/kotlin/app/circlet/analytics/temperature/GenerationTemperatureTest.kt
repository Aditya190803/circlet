// Ported from OpenStrap analytics/lib/src/onehz/wellness/temp_circadian.dart — per-family refusal.
package app.circlet.analytics.temperature

import app.circlet.core.AbsentReason
import app.circlet.core.SkinTemperatureUnit
import app.circlet.core.StrapGeneration
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GenerationTemperatureTest {
    @Test fun gen5HasNoMeasuredSettlingCalibration() {
        assertNotNull(nightlySkinTemp(List(100) { 935 }).value)
        val gen5 = nightlySkinTemp(List(100) { 3057 }, generation = StrapGeneration.GEN5)
        assertNull(gen5.value)
        assertEquals(AbsentReason.CALIBRATING, gen5.absentReason)
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
