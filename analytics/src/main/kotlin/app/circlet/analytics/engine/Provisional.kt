package app.circlet.analytics.engine

import app.circlet.analytics.sleep.SleepDay
import app.circlet.analytics.strain.DayLoad
import app.circlet.analytics.strain.dayLoad
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.DayHeart
import app.circlet.core.DayInput
import app.circlet.core.Metric
import app.circlet.core.Recovery
import app.circlet.core.SleepNeed
import app.circlet.core.SleepPerformance
import app.circlet.core.Vo2Max
import app.circlet.core.ageOn
import java.time.Duration
import kotlin.math.ln
import kotlin.math.roundToInt

// First-days scores. The personal-baseline methods abstain until they have history
// (three days for strain, about two weeks for recovery). Until then these give a
// provisional figure from the same night's own measurements and published adult
// norms, always at LOW confidence, so a new user sees a score on day one. As soon
// as the baseline method produces a value, that value is used and these are not.

/** Adult sleep need from the AASM/SRS 2015 consensus midpoint; teenagers need more (NSF 2015). */
private fun defaultNeed(age: Int?): Duration =
    if (age != null && age < 18) Duration.ofHours(9) else Duration.ofHours(8)

/** Sleep need and performance against the consensus need while the personal need is still unknown. */
internal fun provisionalSleep(sleep: SleepDay, input: DayInput): SleepDay {
    val session = sleep.main.value ?: return sleep
    if (sleep.performance.percent.present) return sleep
    val need = sleep.need.duration.value ?: defaultNeed(input.profile.ageOn(input.date))
    if (need.isZero || need.isNegative) return sleep
    val percent = (session.timeAsleep.toMinutes() * 100.0 / need.toMinutes()).roundToInt().coerceIn(0, 100)
    return sleep.copy(
        need = if (sleep.need.duration.present) sleep.need
        else SleepNeed(Metric.of(need, Confidence.LOW), sleep.need.recommendedBedtime),
        performance = SleepPerformance(Metric.of(percent, Confidence.LOW)),
    )
}

/**
 * Strain scored against today's own quiet waking level while the trailing personal
 * level (three prior days) does not exist yet.
 */
internal fun provisionalLoad(load: DayLoad, input: DayInput, restingBpm: Double?): DayLoad {
    if (load.strain.score.present || load.strain.score.absentReason != AbsentReason.CALIBRATING) return load
    val quietToday = load.quietWakingHrr ?: return load
    val again = dayLoad(
        samples = input.samples,
        profile = input.profile,
        zone = input.zone,
        date = input.date,
        now = input.now,
        restingHeartRateBpm = restingBpm,
        priorQuietWakingHrr = List(3) { quietToday },
    )
    val value = again.strain.score.value ?: return load
    return load.copy(strain = load.strain.copy(score = Metric.of(value, Confidence.LOW)))
}

// Short-term resting RMSSD in healthy adults, Nunan et al. 2010: mean 42 ms, on a log scale.
private const val NORM_LN_RMSSD = 3.74
private const val NORM_LN_RMSSD_SD = 0.45
private const val NORM_RESTING_BPM = 60.0
private const val NORM_RESTING_SD = 8.0

/**
 * Recovery from population norms while the personal HRV baseline is still forming.
 * The calibration status stays CALIBRATING, so the app can say the score is provisional.
 */
internal fun provisionalRecovery(recovery: Recovery, heart: DayHeart, performance: SleepPerformance): Recovery {
    if (recovery.score.present || recovery.score.absentReason != AbsentReason.CALIBRATING) return recovery
    val rmssd = heart.hrv.rmssdMs.value
    val resting = heart.resting.bpm.value
    val sleepPercent = performance.percent.value
    if (resting == null && rmssd == null) return recovery
    var score = 55.0
    if (rmssd != null && rmssd > 0) score += 18.0 * ((ln(rmssd) - NORM_LN_RMSSD) / NORM_LN_RMSSD_SD).coerceIn(-2.0, 2.0)
    if (resting != null) score -= 10.0 * ((resting - NORM_RESTING_BPM) / NORM_RESTING_SD).coerceIn(-2.0, 2.0)
    if (sleepPercent != null) score += (sleepPercent - 85) * 0.2
    return recovery.copy(score = Metric.of(score.roundToInt().coerceIn(1, 99), Confidence.LOW))
}

/**
 * VO2max from the heart-rate ratio (Uth, Sorensen, Overgaard and Pedersen 2004):
 * 15.3 x maximum heart rate / resting heart rate. It stands in while there is no
 * paced run with a known speed to measure from, which a strap without GPS never has.
 * The maximum is usually estimated from age, so the figure stays at LOW confidence.
 */
internal fun ratioVo2Max(measured: Vo2Max, restingBpm: Double?, maxBpm: Double?): Vo2Max {
    if (measured.mlPerKgMin.present) return measured
    if (restingBpm == null || maxBpm == null || restingBpm < 30 || maxBpm <= restingBpm) return measured
    val estimate = 15.3 * maxBpm / restingBpm
    if (estimate !in 15.0..90.0) return measured
    return Vo2Max(Metric.of(estimate, Confidence.LOW))
}
