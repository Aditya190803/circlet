package app.circlet.analytics.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessCompositeTest {
    private fun around(c: Double, step: Double = 1.0): List<Double> =
        List(14) { i -> c + if (i % 2 == 0) step else -step }

    @Test
    fun singleBadInputDominatesTheDriverBreakdown() {
        val m = readinessComposite(
            listOf(
                hrvInput(40.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
                respInput(14.0, around(14.0)),
                tempInput(2000.0, around(2000.0), settledFraction = 0.97),
            ),
        )
        assertTrue(m.present)
        assertTrue(m.drivers != null)
        assertEquals("HRV", m.drivers!!.first().label)
        assertTrue(m.value!!.score < 50.0)
        assertTrue(m.inputsUsed.contains("HRV"))
    }

    @Test
    fun allAtBaselineScoresNear50() {
        val base = List(14) { i -> if (i % 2 == 0) 1.0 else -1.0 }
        val m = readinessComposite(
            listOf(
                hrvInput(60.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
                respInput(14.0, around(14.0)),
                tempInput(2000.0, around(2000.0), settledFraction = 0.97),
            ),
        )
        assertEquals(50.0, m.value!!.score, 8.0)
        assertFalse(m.value!!.toJson().containsKey("meaningful"))
        assertEquals(14, base.size)
    }

    @Test
    fun weightsRenormalizeOverPresentInputsAbsentIsDash() {
        val present = readinessComposite(
            listOf(
                hrvInput(70.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
            ),
        )
        assertTrue(present.present)
        assertEquals(listOf("HRV", "RHR"), present.inputsUsed)
        val none = readinessComposite(
            listOf(
                hrvInput(null, around(60.0)),
                rhrInput(55.0, listOf(1.0, 2.0)),
            ),
        )
        assertFalse(none.present)
        assertEquals("—", none.toJson()["value"])
        assertEquals(0.0, none.confidence, 0.0)
    }

    @Test
    fun degenerateMadBaselineIsRescuedByMeanSdZ() {
        val tightBase = listOf(
            52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 49.0, 55.0, 53.0,
        )
        val m = readinessComposite(
            listOf(rhrInput(56.0, tightBase)),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertTrue(m.note, m.present)
        assertEquals(listOf("RHR"), m.inputsUsed)
        assertTrue(m.value!!.score < 50.0)
        val flat = readinessComposite(listOf(rhrInput(56.0, List(14) { 52.0 })))
        assertFalse(flat.present)
    }

    @Test
    fun threeQuantizedNightsAreNotABaseline() {
        val m = readinessComposite(
            listOf(rhrInput(52.0, listOf(58.0, 58.0, 59.0))),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertFalse(m.present)
        assertTrue(m.note!!.contains("need_baseline:have=3,need=14"))
    }

    @Test
    fun fourteenNightSubBpmDispersionIsRefusedByName() {
        val base = List(13) { 58.0 } + 59.0
        val m = readinessComposite(
            listOf(rhrInput(52.0, base)),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertFalse(m.present)
        assertTrue(m.note!!.contains("RHR: baseline_dispersion_below_quantum:"))
        assertTrue(m.note!!.contains("quantum=1"))
    }

    @Test
    fun alternatingBaselineWithNonzeroMadIsStillRefusedBelowQuantum() {
        val base = List(14) { i -> if (i % 2 == 0) 58.0 else 59.0 }
        val m = readinessComposite(
            listOf(rhrInput(52.0, base)),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertFalse(m.present)
        assertTrue(m.note!!.contains("RHR: baseline_dispersion_below_quantum:"))
        assertTrue(m.note!!.contains("quantum=1"))
    }

    @Test
    fun quantizedBaselineDisclosesMeanSdFallback() {
        val base = List(12) { 55.0 } + listOf(58.0, 51.0)
        val m = readinessComposite(
            listOf(rhrInput(60.0, base)),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertTrue(m.note, m.present)
        val detail = m.drivers!!.single().detail!!
        assertTrue(detail.contains("mean+SD fallback"))
        assertFalse(detail.contains("robust-z"))
    }

    @Test
    fun dispersedBaselineDisclosesRobustZ() {
        val base = listOf(
            50.0, 52.0, 54.0, 56.0, 58.0, 60.0, 62.0, 51.0, 53.0, 55.0, 57.0, 59.0, 61.0, 63.0,
        )
        val m = readinessComposite(
            listOf(rhrInput(70.0, base)),
            minInputs = 1,
            minWeightSum = 0.0,
        )
        assertTrue(m.note, m.present)
        assertTrue(m.drivers!!.single().detail!!.contains("robust-z (median+MAD)"))
    }

    @Test
    fun fullyConstantBaselineStillAbstains() {
        val m = readinessComposite(listOf(rhrInput(60.0, List(14) { 55.0 })))
        assertFalse(m.present)
        assertEquals("—", m.toJson()["value"])
    }

    @Test
    fun unsettledNightDropsTheTempDriver() {
        fun inputs(settled: Double?) = listOf(
            hrvInput(60.0, around(60.0)),
            rhrInput(55.0, around(55.0)),
            tempInput(784.2, around(805.0), settledFraction = settled),
        )
        val ungated = readinessComposite(inputs(0.97))
        val gated = readinessComposite(inputs(0.787))
        assertTrue(ungated.inputsUsed.contains("temp"))
        assertFalse(gated.inputsUsed.contains("temp"))
        assertTrue(gated.value!!.score < ungated.value!!.score)
        assertTrue(gated.note!!.contains("unsettled_skin_temp:settled=0.787"))
    }

    @Test
    fun unmeasuredSettledFractionIsARefusal() {
        val m = readinessComposite(
            listOf(
                hrvInput(60.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
                tempInput(784.2, around(805.0), settledFraction = null),
            ),
        )
        assertFalse(m.inputsUsed.contains("temp"))
        assertTrue(m.note!!.contains("no settled fraction measured"))
    }

    @Test
    fun settledNightStillDrivesTemperature() {
        val m = readinessComposite(
            listOf(
                hrvInput(60.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
                tempInput(784.2, around(805.0), settledFraction = 0.90),
            ),
        )
        assertTrue(m.inputsUsed.contains("temp"))
        assertTrue(m.drivers!!.map { it.label }.contains("temp"))
    }

    @Test
    fun oneSurvivingInputIsNotAComposite() {
        val m = readinessComposite(
            listOf(tempInput(760.0, around(805.0), settledFraction = 0.99)),
        )
        assertFalse(m.present)
        assertTrue(m.note!!.startsWith("need_inputs:have=1,need=2"))
        assertEquals(listOf("temp"), m.inputsUsed)
    }

    @Test
    fun rrPlusTempClearTheCountButNotTheWeightFloor() {
        val m = readinessComposite(
            listOf(
                respInput(14.0, around(14.0)),
                tempInput(805.0, around(805.0), settledFraction = 0.99),
            ),
        )
        assertFalse(m.note, m.present)
        assertTrue(m.note!!.contains("need_weight=0.5"))
    }

    @Test
    fun hrvPlusRhrCompute() {
        val m = readinessComposite(
            listOf(
                hrvInput(60.0, around(60.0)),
                rhrInput(55.0, around(55.0)),
            ),
        )
        assertTrue(m.note, m.present)
    }

    @Test
    fun valuePresentButOneDayBaselineIsAbsentWithNeedNote() {
        val m = readinessComposite(listOf(hrvInput(50.0, listOf(48.0))))
        assertFalse(m.present)
        assertEquals(0.0, m.confidence, 0.0)
        assertEquals(
            "need_baseline:have=1,need=$readinessCompositeMinBaseline",
            m.note,
        )
        val ok = readinessComposite(
            listOf(
                hrvInput(60.0, List(14) { i -> 48.0 + (i % 5) }),
                rhrInput(55.0, List(14) { i -> 54.0 + (i % 5) }),
            ),
        )
        assertTrue(ok.note, ok.present)
    }
}
