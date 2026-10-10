package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sleepNeed, sleepPerformance, recommendedBedtime, recommendedWake. From coaching_test.dart. */
class SleepCoachingTest {
    @Test
    fun fullStrainAddsThe45MinuteBonus() {
        val m = sleepNeed(
            baselineNeedSec = 28800.0,
            sleepDebtSec = 0.0,
            dayStrain = 21.0,
            napCreditSec = 0.0,
        )
        assertTrue(m.present)
        assertEquals(Tier.ESTIMATE, m.tier)
        assertEquals(0.6, m.confidence, 1e-9)
        assertEquals(28800.0 + 2700.0, m.value!!.needSec, 1.0)
    }

    @Test
    fun midCaseAddsDebtAndPartialStrainAndSubtractsNap() {
        val m = sleepNeed(
            baselineNeedSec = 28800.0,
            sleepDebtSec = 3600.0,
            dayStrain = 10.5,
            napCreditSec = 1800.0,
        )
        assertEquals(31950.0, m.value!!.needSec, 1e-6)
    }

    @Test
    fun napCreditIsSubtracted() {
        val base = sleepNeed(28800.0, 0.0, 0.0, 0.0).value!!.needSec
        val withNap = sleepNeed(28800.0, 0.0, 0.0, 1800.0).value!!.needSec
        assertEquals(base - 1800.0, withNap, 1e-6)
    }

    @Test
    fun clampsToThe11HourCeiling() {
        val m = sleepNeed(999999.0, 0.0, 0.0, 0.0)
        assertEquals(11 * 3600.0, m.value!!.needSec, 0.0)
    }

    @Test
    fun clampsToThe6HourFloor() {
        val m = sleepNeed(28800.0, 0.0, 0.0, 999999.0)
        assertEquals(6 * 3600.0, m.value!!.needSec, 0.0)
    }

    @Test
    fun exactNeedIs100Percent() {
        val m = sleepPerformance(28800.0, 28800.0)
        assertTrue(m.present)
        assertEquals(Tier.ESTIMATE, m.tier)
        assertEquals(0.7, m.confidence, 1e-9)
        assertEquals(100.0, m.value!!.pct, 1e-9)
    }

    @Test
    fun halfOfNeedIs50Percent() {
        assertEquals(50.0, sleepPerformance(14400.0, 28800.0).value!!.pct, 1e-9)
    }

    @Test
    fun overNeedCapsAt100Percent() {
        assertEquals(100.0, sleepPerformance(40000.0, 28800.0).value!!.pct, 1e-9)
    }

    @Test
    fun zeroSleepIs0Percent() {
        assertEquals(0.0, sleepPerformance(0.0, 28800.0).value!!.pct, 1e-9)
    }

    @Test
    fun nonPositiveNeedIsAbsent() {
        assertFalse(sleepPerformance(28800.0, 0.0).present)
        assertFalse(sleepPerformance(28800.0, -1.0).present)
    }

    @Test
    fun bedtimeBacksOffFromWakeByEfficiencyAdjustedTimeInBed() {
        val m = recommendedBedtime(
            needSec = 28800.0,
            typicalWakeMinOfDay = 420.0,
            typicalEfficiencyPct = 90.0,
        )
        assertTrue(m.present)
        assertEquals(Tier.ESTIMATE, m.tier)
        assertEquals(1326.6667, m.value!!.bedtimeMinOfDay, 1e-3)
    }

    @Test
    fun efficiencyIsClampedToThePublishedRange() {
        val m = recommendedBedtime(
            needSec = 28800.0,
            typicalWakeMinOfDay = 600.0,
            typicalEfficiencyPct = 200.0,
        )
        assertEquals(115.1515, m.value!!.bedtimeMinOfDay, 1e-3)
    }

    @Test
    fun bedtimeMinuteOfDayWrapsIntoTheDay() {
        val m = recommendedBedtime(
            needSec = 28800.0,
            typicalWakeMinOfDay = 60.0,
            typicalEfficiencyPct = 90.0,
        )
        assertTrue(m.value!!.bedtimeMinOfDay >= 0.0)
        assertTrue(m.value!!.bedtimeMinOfDay < 1440.0)
    }

    @Test
    fun wakeUsesTheSameTimeInBedTheBedtimeWasBackedOffFrom() {
        val bed = recommendedBedtime(
            needSec = 28800.0,
            typicalWakeMinOfDay = 420.0,
            typicalEfficiencyPct = 88.0,
        )
        val wake = recommendedWake(
            bedtimeMinOfDay = bed.value!!.bedtimeMinOfDay,
            needSec = 28800.0,
            typicalEfficiencyPct = 88.0,
        )
        assertTrue(wake.present)
        assertEquals(Tier.ESTIMATE, wake.tier)
        assertEquals(0.55, wake.confidence, 1e-9)
        assertEquals(420.0, wake.value!!.wakeMinOfDay, 1e-9)
    }

    @Test
    fun spanIsNeverShortOfNeedOverEfficiency() {
        for (needH in listOf(6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 10.0, 11.0)) {
            for (effPct in listOf(75.0, 80.0, 85.0, 88.0, 92.0, 99.0)) {
                val needSec = needH * 3600.0
                val bed = recommendedBedtime(needSec, 420.0, effPct).value!!.bedtimeMinOfDay
                val wake = recommendedWake(bed, needSec, effPct).value!!.wakeMinOfDay
                val span = dartMod(wake - bed, 1440.0)
                val required = needSec / (effPct / 100.0) / 60.0
                assertTrue(
                    "need ${needH}h at $effPct% eff: span $span min < in-bed $required min",
                    span >= required - 1e-6,
                )
            }
        }
    }

    @Test
    fun moreNeedNeverMovesTargetWakeEarlier() {
        for (h in listOf(7.0, 7.5, 8.0, 8.5, 9.0)) {
            val bed = recommendedBedtime(h * 3600.0, 420.0, 88.0).value!!.bedtimeMinOfDay
            val wake = recommendedWake(bed, h * 3600.0, 88.0).value!!.wakeMinOfDay
            assertEquals(420.0, wake, 1e-9)
        }
    }

    @Test
    fun wakeWrapsAroundMidnight() {
        val m = recommendedWake(
            bedtimeMinOfDay = 1410.0,
            needSec = 27000.0,
            typicalEfficiencyPct = 88.0,
        )
        assertEquals(481.3636, m.value!!.wakeMinOfDay, 1e-3)
        assertTrue(m.value!!.wakeMinOfDay >= 0.0)
        assertTrue(m.value!!.wakeMinOfDay < 1440.0)
    }

    /** Dart `%` on a positive modulus: remainder in [0, m). */
    private fun dartMod(a: Double, m: Double): Double {
        val r = a % m
        return if (r < 0.0) r + m else r
    }
}
