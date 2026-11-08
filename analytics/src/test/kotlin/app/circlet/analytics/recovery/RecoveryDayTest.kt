package app.circlet.analytics.recovery

import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.Recovery
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.SleepPerformance
import app.circlet.core.emptyDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDayTest {
    private val today = LocalDate.of(2026, 11, 8)

    private fun night(ago: Int, hrv: Double, resting: Int, resp: Double, sleep: Int, stored: Int? = null): DaySummary {
        val base = emptyDay(today.minusDays(ago.toLong()), AbsentReason.NOT_ENOUGH_DATA)
        return base.copy(
            recovery = Recovery(
                stored?.let { Metric.of(it, Confidence.LOW) } ?: Metric.absent(AbsentReason.CALIBRATING),
                CalibrationStatus.CALIBRATING,
                base.recovery.drivers,
            ),
            heart = base.heart.copy(
                resting = RestingHeartRate(Metric.of(resting, Confidence.MEDIUM)),
                hrv = base.heart.hrv.copy(rmssdMs = Metric.of(hrv, Confidence.MEDIUM)),
                respiratoryRate = RespiratoryRate(Metric.of(resp, Confidence.MEDIUM)),
            ),
            sleepPerformance = SleepPerformance(Metric.of(sleep, Confidence.MEDIUM)),
        )
    }

    /** Forty-five ordinary nights that wobble around 70 ms, 52 bpm, 15 breaths, 85 percent. */
    private fun ordinary(stored: Int? = null): List<DaySummary> = (45 downTo 1).map { ago ->
        val k = ago % 5 - 2
        night(ago, 70.0 + 4 * k, 52 - k, 15.0 + 0.2 * k, 85 + 2 * k, stored)
    }

    private fun score(history: List<DaySummary>, hrv: Double, resting: Int, resp: Double, sleep: Int): Recovery {
        val tonight = night(0, hrv, resting, resp, sleep)
        return computeRecovery(tonight.heart, tonight.sleepPerformance, history)
    }

    @Test
    fun aTypicalNightScoresAroundTheTypicalScore() {
        val r = score(ordinary(), 70.0, 52, 15.0, 85)
        assertEquals(CalibrationStatus.READY, r.calibration)
        assertEquals(recoveryTypicalScore, r.score.value!!.toDouble(), 3.0)
    }

    @Test
    fun aClearlyBetterNightIsGreenAndAClearlyWorseOneIsRed() {
        assertTrue(score(ordinary(), 84.0, 49, 14.6, 93).score.value!! >= 67)
        assertTrue(score(ordinary(), 52.0, 58, 16.0, 70).score.value!! < 34)
    }

    @Test
    fun storedScoresDoNotMoveTheBaseline() {
        val clean = score(ordinary(), 74.0, 51, 15.0, 87).score.value
        assertEquals(clean, score(ordinary(stored = 90), 74.0, 51, 15.0, 87).score.value)
        assertEquals(clean, score(ordinary(stored = 20), 74.0, 51, 15.0, 87).score.value)
    }

    @Test
    fun fourteenNightsOfBaselineMakeTheScorePersonal() {
        val short = score(ordinary().takeLast(13), 70.0, 52, 15.0, 85)
        assertNull(short.score.value)
        assertEquals(CalibrationStatus.CALIBRATING, short.calibration)
        val ready = score(ordinary().takeLast(14), 70.0, 52, 15.0, 85)
        assertEquals(CalibrationStatus.READY, ready.calibration)
        assertEquals(recoveryTypicalScore, ready.score.value!!.toDouble(), 3.0)
    }

    @Test
    fun theScaleIsCentredOnTheTypicalScoreAndGreenAtTheSpread() {
        val history = List(30) { if (it % 2 == 0) 0.4 else -0.4 }
        assertEquals(recoveryTypicalScore, personalRecoveryScore(0.0, history), 1e-9)
        val sigma = maxOf(readinessCalibrationSigmaFloor, 0.4 * 1.4826)
        assertEquals(67.0, personalRecoveryScore(recoveryGreenSpread * sigma, history), 1e-9)
    }

    @Test
    fun driverPointsShowWhatRaisedTheScore() {
        val r = score(ordinary(), 84.0, 52, 15.0, 85)
        val hrv = r.drivers.first { it.kind == app.circlet.core.RecoveryDriverKind.HRV }
        assertTrue(hrv.contribution!! > 0.0)
    }
}
