package app.circlet.analytics.reference

import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DayStress
import app.circlet.core.DaySummary
import app.circlet.core.Hrv
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.Profile
import app.circlet.core.Recovery
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.Sex
import app.circlet.core.Vo2Max
import app.circlet.core.emptyDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencesTest {
    private val date = LocalDate.of(2026, 6, 15)
    private val adultMale = Profile(null, 35, Sex.MALE, 180.0, 80.0, null, emptyList())

    private fun dayWith(
        date: LocalDate,
        hrv: Double? = null,
        resting: Int? = null,
        resp: Double? = null,
        vo2: Double? = null,
        strain: Double? = null,
        recovery: Int? = null,
        stress: Double? = null,
    ): DaySummary {
        val base = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
        return base.copy(
            heart = base.heart.copy(
                resting = RestingHeartRate(
                    resting?.let { Metric.of(it, Confidence.HIGH) }
                        ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                ),
                hrv = Hrv(
                    rmssdMs = hrv?.let { Metric.of(it, Confidence.HIGH) }
                        ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                    sdnnMs = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                    frequency = null,
                ),
                respiratoryRate = RespiratoryRate(
                    resp?.let { Metric.of(it, Confidence.HIGH) }
                        ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                ),
            ),
            strain = base.strain.copy(
                score = strain?.let { Metric.of(it, Confidence.MEDIUM) }
                    ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            ),
            recovery = base.recovery.copy(
                score = recovery?.let { Metric.of(it, Confidence.HIGH) }
                    ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                calibration = if (recovery == null) CalibrationStatus.CALIBRATING else CalibrationStatus.READY,
            ),
            stress = DayStress(
                score = stress?.let { Metric.of(it, Confidence.MEDIUM) }
                    ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                timeline = emptyList(),
            ),
            vo2Max = Vo2Max(
                vo2?.let { Metric.of(it, Confidence.LOW) }
                    ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            ),
        )
    }

    private fun history(days: Int, read: (Int) -> DaySummary): List<DaySummary> =
        (1..days).map { read(it) }

    @Test
    fun personalBandIsCentrePlusMinusRobustSpread() {
        val values = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0)
        val prior = history(8) { i -> dayWith(date.minusDays(i.toLong()), hrv = values[i - 1]) }
        val hrv = references(adultMale, date, prior).single { it.kind == MetricKind.HRV }
        assertEquals(8, hrv.personalDays)
        // Median 13.5, MAD 2.0 scaled by 1.4826.
        assertEquals(13.5 - 2.9652, hrv.personal!!.low, 1e-4)
        assertEquals(13.5 + 2.9652, hrv.personal!!.high, 1e-4)
    }

    @Test
    fun fewerThanSevenDaysLeavesPersonalNullButKeepsIdeal() {
        val prior = history(6) { i -> dayWith(date.minusDays(i.toLong()), hrv = 45.0) }
        val hrv = references(adultMale, date, prior).single { it.kind == MetricKind.HRV }
        assertNull(hrv.personal)
        assertEquals(6, hrv.personalDays)
        assertNotNull(hrv.ideal)
    }

    @Test
    fun flatHistoryHasNoSpreadToCentreABandOn() {
        val prior = history(9) { i -> dayWith(date.minusDays(i.toLong()), resting = 52) }
        val rhr = references(adultMale, date, prior).single { it.kind == MetricKind.RESTING_HEART_RATE }
        assertNull(rhr.personal)
        assertEquals(9, rhr.personalDays)
    }

    @Test
    fun daysOutsideTheWindowAndOnTheTargetDateAreIgnored() {
        val values = (0 until 8).map { 40.0 + it }
        val prior = history(8) { i -> dayWith(date.minusDays(i.toLong()), hrv = values[i - 1]) } +
            dayWith(date.minusDays(60), hrv = 200.0) +
            dayWith(date, hrv = 200.0)
        val hrv = references(adultMale, date, prior).single { it.kind == MetricKind.HRV }
        assertEquals(8, hrv.personalDays)
        // Median 43.5, MAD 2.0 scaled by 1.4826: the 200.0 outliers must not leak in.
        assertEquals(43.5 - 2.9652, hrv.personal!!.low, 1e-4)
        assertEquals(43.5 + 2.9652, hrv.personal!!.high, 1e-4)
    }

    @Test
    fun sleepDurationBucketsFollowAge() {
        val teen = Profile(LocalDate.of(2012, 3, 1), null, Sex.FEMALE, null, null, null, emptyList())
        val older = Profile(LocalDate.of(1950, 3, 1), null, Sex.MALE, null, null, null, emptyList())
        val unknown = Profile(null, null, Sex.MALE, null, null, null, emptyList())
        fun ideal(profile: Profile) =
            references(profile, date, emptyList()).single { it.kind == MetricKind.SLEEP_DURATION }
        assertEquals(480.0, ideal(teen).ideal!!.low, 0.0)
        assertEquals(600.0, ideal(teen).ideal!!.high, 0.0)
        assertEquals(420.0, ideal(older).ideal!!.low, 0.0)
        assertEquals(480.0, ideal(older).ideal!!.high, 0.0)
        assertEquals(420.0, ideal(adultMale).ideal!!.low, 0.0)
        assertEquals(540.0, ideal(adultMale).ideal!!.high, 0.0)
        assertEquals(540.0, ideal(unknown).ideal!!.high, 0.0)
        assertTrue(ideal(teen).idealSource!!.contains("Hirshkowitz"))
    }

    @Test
    fun vo2MaxIdealFollowsAgeSexAndNamesTheCategory() {
        val female35 = Profile(null, 35, Sex.FEMALE, 168.0, 64.0, null, emptyList())
        val male35 = Profile(null, 35, Sex.MALE, 180.0, 80.0, null, emptyList())
        val history = listOf(dayWith(date, vo2 = 49.0))
        val female = references(female35, date, history).single { it.kind == MetricKind.VO2_MAX }
        assertEquals(37.8, female.ideal!!.low, 1e-9)
        assertEquals(47.4, female.ideal!!.high, 1e-9)
        assertTrue(female.idealLabel!!, female.idealLabel!!.contains("women 30–39"))
        assertTrue(female.idealLabel!!, female.idealLabel!!.contains("You: Superior"))
        val male = references(male35, date, history).single { it.kind == MetricKind.VO2_MAX }
        assertEquals(44.0, male.ideal!!.low, 1e-9)
        assertEquals(54.0, male.ideal!!.high, 1e-9)
        assertTrue(male.idealLabel!!, male.idealLabel!!.contains("You: Excellent"))
    }

    @Test
    fun vo2MaxIdealIsNullWithoutSexAgeOrForChildren() {
        val noSex = Profile(null, 35, Sex.UNSPECIFIED, null, null, null, emptyList())
        val noAge = Profile(null, null, Sex.MALE, null, null, null, emptyList())
        val child = Profile(null, 15, Sex.FEMALE, null, null, null, emptyList())
        for (profile in listOf(noSex, noAge, child)) {
            val vo2 = references(profile, date, emptyList()).single { it.kind == MetricKind.VO2_MAX }
            assertNull(vo2.ideal)
            assertNull(vo2.idealLabel)
        }
    }

    @Test
    fun strainRecoveryAndStressHavePersonalBandsOnly() {
        val prior = history(10) { i ->
            dayWith(date.minusDays(i.toLong()), strain = 10.0 + i, recovery = 60 + i, stress = 28.0 + i)
        }
        val refs = references(adultMale, date, prior)
        val strain = refs.single { it.kind == MetricKind.STRAIN }
        assertNotNull(strain.personal)
        assertNull(strain.ideal)
        assertNull(strain.higherIsBetter)
        val recovery = refs.single { it.kind == MetricKind.RECOVERY }
        assertNotNull(recovery.personal)
        assertNull(recovery.ideal)
        assertEquals(true, recovery.higherIsBetter)
        val stress = refs.single { it.kind == MetricKind.STRESS }
        assertNotNull(stress.personal)
        assertNull(stress.ideal)
        assertEquals(false, stress.higherIsBetter)
    }

    @Test
    fun headlineIdealsCarryCitations() {
        val refs = references(adultMale, date, emptyList())
        fun cited(kind: MetricKind): MetricReference = refs.single { it.kind == kind }
        assertEquals("Nunan et al. 2010", cited(MetricKind.HRV).idealSource)
        assertEquals("American Heart Association", cited(MetricKind.RESTING_HEART_RATE).idealSource)
        assertNotNull(cited(MetricKind.RESPIRATORY_RATE).idealSource)
        assertNotNull(cited(MetricKind.SLEEP_EFFICIENCY).idealSource)
        val skin = cited(MetricKind.SKIN_TEMPERATURE)
        assertEquals(-1.0, skin.ideal!!.low, 0.0)
        assertEquals(1.0, skin.ideal!!.high, 0.0)
        assertNull(skin.higherIsBetter)
    }
}
