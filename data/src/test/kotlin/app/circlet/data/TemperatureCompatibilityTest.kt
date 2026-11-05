package app.circlet.data

import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.ReferenceRange
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.emptyDay
import app.circlet.data.codec.DayCodec
import app.circlet.data.db.DaySummaryEntity
import app.circlet.data.repo.assemble
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureCompatibilityTest {
    private val date = LocalDate.of(2026, 6, 15)
    private val legacy = SkinTemperature(
        deviation = Metric.of(2.5, Confidence.HIGH),
        baselineNights = 12,
        flag = SkinTempFlag.ELEVATED,
        settledMeanCounts = 935.0,
    )

    @Test
    fun cachedLegacyTemperatureIsUnavailableBeforeRecomputation() {
        val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(
            skinTemperature = legacy,
            calories = Metric.of(200, Confidence.HIGH),
            references = listOf(
                MetricReference(MetricKind.SKIN_TEMPERATURE, ReferenceRange(-1.0, 1.0), 12, null, null, null, null),
            ),
        )
        val payload = DayCodec.encode(day)
        val row = DaySummaryEntity(date.toString(), 9, 0, payload)
        val visible = assemble(date, row, emptyList())
        assertEquals(SkinTemperature.unavailable(), visible.skinTemperature)
        assertTrue(visible.references.none { it.kind == MetricKind.SKIN_TEMPERATURE })
        assertEquals(200, visible.calories.value)
        // Reading compatibility does not rewrite the backup or the source recording.
        assertEquals(legacy, DayCodec.decode(payload).skinTemperature)
    }

    @Test
    fun importedDegreesSurviveButLegacyDeviationsDoNot() {
        val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(
            skinTemperature = legacy.copy(importedCelsius = 33.4),
        )
        val row = DaySummaryEntity(date.toString(), -1, 0, DayCodec.encode(day))
        val skin = checkNotNull(assemble(date, row, emptyList()).skinTemperature)
        assertEquals(33.4, checkNotNull(skin.importedCelsius), 0.0)
        assertNull(skin.deviation.value)
        assertNull(skin.settledMeanCounts)
        assertEquals(0, skin.baselineNights)
    }

    @Test
    fun emptyAndInvalidImportedReadingsDoNotStartCalibration() {
        assertEquals(SkinTemperature.unavailable(), assemble(date, null, emptyList()).skinTemperature)
        val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(
            skinTemperature = legacy.copy(importedCelsius = 90.0),
        )
        val row = DaySummaryEntity(date.toString(), 9, 0, DayCodec.encode(day))
        assertEquals(SkinTemperature.unavailable(), assemble(date, row, emptyList()).skinTemperature)
    }
}
