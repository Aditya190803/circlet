package app.circlet.analytics.recovery

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class ReadinessGlassboxTest {
    @Test
    fun oneDriverOffNamesThatDriverAndBreakdownIsComplete() {
        val hrvHist = List(20) { i -> 4.0 + (i % 5) * 0.02 }
        val rhrHist = List(20) { i -> 55.0 + (i % 5) * 0.2 }
        val respHist = List(20) { i -> 14.0 + (i % 5) * 0.1 }
        val tempHist = List(20) { i -> (i % 5) * 0.02 }
        val inputs = listOf(
            GlassBoxInput("hrv", 3.0, hrvHist, wHrv),
            GlassBoxInput("rhr", 55.4, rhrHist, wRhr, lowerIsBetter = true),
            GlassBoxInput("resp", 14.2, respHist, wResp, lowerIsBetter = true),
            GlassBoxInput("temp", 0.04, tempHist, wTemp, lowerIsBetter = true),
        )
        val m = glassBoxReadiness(inputs)
        assertTrue(m.present)
        val v = m.value!!
        assertEquals(4, v.breakdown.size)
        assertTrue(v.drivers.isNotEmpty())
        assertEquals("hrv", v.drivers.first().label)
        assertTrue(v.drivers.first().contribution < 0.0)
        assertTrue(v.narrative.lowercase(Locale.ROOT).contains("hrv"))
        assertTrue(v.score < 50.0)
    }

    @Test
    fun realNightOffBaselineNamesItsDriver() {
        val rhr = listOf(57.73, 57.07, 60.87, 56.90, 60.40, 55.97, 67.67)
        val tonight = 60.93
        val m = glassBoxReadiness(
            listOf(GlassBoxInput("rhr", tonight, rhr, wRhr, lowerIsBetter = true)),
        )
        val v = m.value!!
        assertTrue(v.breakdown.single().beyondUsualSpread)
        assertTrue(v.drivers.isNotEmpty())
        assertEquals("rhr", v.drivers.first().label)
        assertFalse(v.narrative.lowercase(Locale.ROOT).contains("noise"))
    }

    @Test
    fun narrativeDirectionFollowsTheRawValue() {
        val hist = listOf(55.0, 56.0, 57.0, 58.0, 59.0, 55.0, 56.0, 57.0, 58.0, 59.0)
        fun say(tonight: Double): String = glassBoxReadiness(
            listOf(GlassBoxInput("rhr", tonight, hist, wRhr, lowerIsBetter = true)),
        ).value!!.narrative
        assertTrue(say(48.0).contains("resting heart rate is down"))
        assertTrue(say(66.0).contains("resting heart rate is up"))
        assertFalse(say(66.0).contains("easier"))
    }

    @Test
    fun wholeBpmQuantizedBaselineIsNotNamedADriver() {
        val rhr = List(14) { i -> if (i % 2 == 0) 58.0 else 59.0 }
        val m = glassBoxReadiness(
            listOf(
                GlassBoxInput(
                    label = "rhr",
                    value = 58.0,
                    history = rhr,
                    weight = wRhr,
                    lowerIsBetter = true,
                    quantum = 1.0,
                ),
            ),
        )
        assertFalse(m.value!!.breakdown.single().beyondUsualSpread)
        assertTrue(m.value!!.drivers.isEmpty())
    }

    @Test
    fun moverInsideTheUsualSpreadIsNeverNamed() {
        val hist = List(20) { i -> 50.0 + (i % 5) * 0.1 }
        val m = glassBoxReadiness(
            listOf(
                GlassBoxInput("hrv", 50.2, hist, wHrv),
                GlassBoxInput("rhr", 50.2, hist, wRhr, lowerIsBetter = true),
            ),
        )
        assertTrue(m.value!!.drivers.isEmpty())
        assertTrue(m.value!!.narrative.lowercase(Locale.ROOT).contains("noise"))
    }

    @Test
    fun missingInputReweightsAndDoesNotZeroTheScore() {
        val hist = List(20) { i -> 60.0 + (i % 5) }
        val m = glassBoxReadiness(
            listOf(
                GlassBoxInput("hrv", 64.0, hist, wHrv),
                GlassBoxInput("temp", 0.0, emptyList(), wTemp),
            ),
        )
        assertTrue(m.present)
        assertEquals(1, m.value!!.inputsUsed)
        val temp = m.value!!.breakdown.first { it.label == "temp" }
        assertFalse(temp.used)
    }

    @Test
    fun underBaselinedGlassBoxInputIsAbsentNeverNan() {
        val inputs = listOf(
            GlassBoxInput("hrv", 60.0, List(20) { i -> 50.0 + i }, 0.40),
            GlassBoxInput("temp", 0.5, listOf(0.1, 0.2), 0.12, lowerIsBetter = true),
        )
        val m = glassBoxReadiness(inputs)
        val json = m.toJson { it.toJson() }
        @Suppress("UNCHECKED_CAST")
        val breakdown = (json["value"] as Map<String, Any?>)["breakdown"] as List<Map<String, Any?>>
        val temp = breakdown.first { it["label"] == "temp" }
        assertNull(temp["percentile_of_you"])
        assertEquals(false, temp["used"])
        assertEquals("need_baseline:have=2,need=7", temp["note"])
    }
}
