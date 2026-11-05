package app.circlet.analytics.engine

import app.circlet.analytics.fitness.vo2Max
import app.circlet.analytics.foundations.joinSame
import app.circlet.analytics.foundations.workerPool
import app.circlet.analytics.heart.computeHeart
import app.circlet.analytics.motion.movementMinutes
import app.circlet.analytics.recovery.computeRecovery
import app.circlet.analytics.reference.references
import app.circlet.analytics.sleep.alignSamples
import app.circlet.analytics.sleep.finishSleep
import app.circlet.analytics.sleep.segmentDay
import app.circlet.analytics.motion.phoneStepsOnDay
import app.circlet.core.Steps
import app.circlet.analytics.strain.QUIET_HRR_TRAIT_MIN_MINUTES
import app.circlet.analytics.strain.dailyQuietWakingHrr
import app.circlet.analytics.strain.dayLoad
import app.circlet.analytics.strain.personalQuietWakingHrr
import app.circlet.analytics.strain.resolveMaxHr
import app.circlet.analytics.strain.targetStrain
import app.circlet.analytics.stress.dayStress
import app.circlet.analytics.wellness.wellnessFlags
import app.circlet.analytics.workout.detectActivities
import app.circlet.analytics.workout.recordedActivity
import app.circlet.core.Activity
import app.circlet.core.DayEngine
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import app.circlet.core.InsightEngine
import app.circlet.analytics.insight.CircletInsight
import app.circlet.analytics.motion.MotionDay
import app.circlet.core.DayStress
import app.circlet.core.Recovery
import app.circlet.core.SkinTemperature
import app.circlet.core.withVerifiedSkinTemperature
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Bump when stored days need recomputing. Version 10 retires the unverified offset-68 temperature channel.
 */
const val CIRCLET_ALGORITHM_VERSION: Int = 10

/** The [InsightEngine] the app passes to storage. The maths live in [CircletInsight]. */
fun circletInsightEngine(): InsightEngine = CircletInsight()

/**
 * One local day from the sleep, heart, strain, stress, motion, workout, recovery,
 * and wellness packages.
 *
 * A package that abstains stays absent. This class does not fill a missing input
 * with a population default.
 */
class CircletDayEngine : DayEngine {
    override val algorithmVersion: Int = CIRCLET_ALGORITHM_VERSION

    override fun computeDay(input: DayInput): DaySummary {
        val history = input.history.sortedBy { it.date }.filter { it.date < input.date }.map { it.withVerifiedSkinTemperature() }
        val priorQuiet = history.mapNotNull { quietWakingHrr(it, input.profile) }
        val priorResting = history.mapNotNull { it.heart.resting.bpm.value?.toDouble() }
        val personal = personalQuietWakingHrr(priorQuiet)
        val quiet = personal.value?.hrr
        val quietSettled = personal.value?.settled == true

        // One alignment and one night staging per day. The strain-fed sleep
        // need is rescored below; the sessions are not staged twice.
        val series = alignSamples(input.samples, input.now)
        val segments = segmentDay(series, input.zone, input.date, input.profile, history, input.sleepEdit)
        var sleep = finishSleep(segments, history, input.zone, input.date, null)
        val session = sleep.main.value
        val heart = computeHeart(
            samples = input.samples,
            series = series,
            zone = input.zone,
            date = input.date,
            now = input.now,
            profile = input.profile,
            sleepStart = session?.start,
            sleepEnd = session?.end,
        )
        val resting = heart.resting.bpm.value?.toDouble()
        val load = provisionalLoad(
            dayLoad(
                samples = input.samples,
                profile = input.profile,
                zone = input.zone,
                date = input.date,
                now = input.now,
                restingHeartRateBpm = resting,
                priorQuietWakingHrr = priorQuiet,
                priorResting = priorResting,
            ),
            input,
            resting,
        )
        // Until three prior days give a personal quiet level, workouts are scored against today's own.
        val activityQuiet = quiet ?: load.quietWakingHrr
        val strainValue = load.strain.score.value
        if (strainValue != null) {
            sleep = finishSleep(segments, history, input.zone, input.date, strainValue)
        }
        sleep = provisionalSleep(sleep, input)
        // The tail sections only read what is already computed, so a full day
        // runs them concurrently and assembles the results in order. Small
        // inputs stay sequential, with the same failure order as before.
        val tail = if (input.samples.size < PARALLEL_TAIL_SAMPLES) {
            TailSections(
                recovery = computeRecovery(heart, sleep.performance, history),
                activities = activitiesOf(input, resting, activityQuiet, quietSettled, priorResting),
                motion = movementMinutes(
                    input.samples, input.zone, input.date, input.now, null, history.size,
                ),
                stress = dayStress(input.samples, input.zone, input.date, input.now),
                byMinute = heartRateByMinute(input.samples, input.zone, input.date, input.now),
            )
        } else {
            val pool = workerPool
            val fRecovery = pool.submit<Recovery> { computeRecovery(heart, sleep.performance, history) }
            val fActivities = pool.submit<List<Activity>> {
                activitiesOf(input, resting, activityQuiet, quietSettled, priorResting)
            }
            val fMotion = pool.submit<MotionDay> {
                movementMinutes(input.samples, input.zone, input.date, input.now, null, history.size)
            }
            val fStress = pool.submit<DayStress> { dayStress(input.samples, input.zone, input.date, input.now) }
            val fByMinute = pool.submit<List<Int?>> {
                heartRateByMinute(input.samples, input.zone, input.date, input.now)
            }
            TailSections(
                recovery = fRecovery.joinSame(),
                activities = fActivities.joinSame(),
                motion = fMotion.joinSame(),
                stress = fStress.joinSame(),
                byMinute = fByMinute.joinSame(),
            )
        }
        val recovery = provisionalRecovery(tail.recovery, heart, sleep.performance)
        val activities = tail.activities
        val motion = tail.motion
        val stress = tail.stress
        val maxHr = resolveMaxHr(input.profile, input.date)
        val vo2 = vo2Max(
            speedMps = null,
            avgHrBpm = null,
            boutDurationSec = null,
            restingHrBpm = resting,
            hrMaxBpm = maxHr,
        )
        val summary = DaySummary(
            date = input.date,
            sleep = sleep.main,
            naps = sleep.naps,
            recovery = recovery,
            strain = load.strain,
            stress = stress,
            heart = heart,
            steps = Steps(phoneSteps(input)),
            calories = load.calories,
            activities = activities,
            heartRateByMinute = tail.byMinute,
            sleepNeed = sleep.need,
            sleepDebt = sleep.debt,
            sleepPerformance = sleep.performance,
            sleepConsistency = sleep.consistency,
            targetStrain = targetStrain(recovery.score.value),
            vo2Max = ratioVo2Max(vo2, resting, maxHr),
            wellness = emptyList(),
            skinTemperature = SkinTemperature.unavailable(),
            references = references(input.profile, input.date, history).filterNot { it.kind == app.circlet.core.MetricKind.SKIN_TEMPERATURE },
        )
        return summary.copy(wellness = wellnessFlags(history + summary))
    }
}

/** Samples above which the day's independent tail sections run concurrently. */
private const val PARALLEL_TAIL_SAMPLES = 8192

/** The tail of a day: everything that only reads computed sections. */
private data class TailSections(
    val recovery: Recovery,
    val activities: List<Activity>,
    val motion: MotionDay,
    val stress: DayStress,
    val byMinute: List<Int?>,
)

private fun phoneSteps(input: DayInput): app.circlet.core.Metric<Int> {
    val total = phoneStepsOnDay(input.phoneSteps, input.zone, input.date)
        ?: return app.circlet.core.Metric.absent(app.circlet.core.AbsentReason.NOT_ENOUGH_DATA)
    return app.circlet.core.Metric.of(total, app.circlet.core.Confidence.MEDIUM)
}

/**
 * Quiet-waking heart-rate reserve for one stored day, or null when that day
 * cannot support it. [DaySummary] does not store the level. The minute curve does.
 */
fun quietWakingHrr(day: DaySummary, profile: Profile): Double? {
    val minutes = day.heartRateByMinute
    if (minutes.size != MINUTES_PER_DAY) return null
    val resting = day.heart.resting.bpm.value?.toDouble() ?: return null
    val maxHr = resolveMaxHr(profile, day.date) ?: return null
    val series = List(MINUTES_PER_DAY) { index -> minutes[index]?.toDouble() ?: 0.0 }
    return dailyQuietWakingHrr(series, resting, maxHr, minMinutes = QUIET_HRR_TRAIT_MIN_MINUTES)
}

private fun activitiesOf(
    input: DayInput,
    resting: Double?,
    quiet: Double?,
    quietSettled: Boolean,
    priorResting: List<Double>,
): List<Activity> {
    val saved = input.manualActivities.map { it.start to it.end }
    val detected = detectActivities(
        samples = input.samples,
        profile = input.profile,
        zone = input.zone,
        date = input.date,
        now = input.now,
        restingHeartRateBpm = resting,
        quietWakingHrr = quiet,
        quietSettled = quietSettled,
        priorResting = priorResting,
        saved = saved,
    )
    val manuals = input.manualActivities.map { manual ->
        val scored = recordedActivity(
            samples = input.samples,
            profile = input.profile,
            zone = input.zone,
            start = manual.start,
            end = manual.end,
            sport = manual.sport,
            id = manual.id,
            restingHeartRateBpm = resting,
            quietWakingHrr = quiet,
            quietSettled = quietSettled,
            priorResting = priorResting,
            pauses = manual.pauses,
        )
        manual.copy(
            strain = scored.strain,
            averageHeartRateBpm = scored.averageHeartRateBpm,
            maxHeartRateBpm = scored.maxHeartRateBpm,
            calories = scored.calories,
            zoneMinutes = scored.zoneMinutes,
            heartRateRecovery = scored.heartRateRecovery,
        )
    }
    return (detected + manuals).sortedWith(compareBy<Activity> { it.start }.thenBy { it.id })
}

/** 1440 local minutes. Null is a minute with no plausible on-wrist heart rate. */
internal fun heartRateByMinute(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
): List<Int?> {
    val sums = LongArray(MINUTES_PER_DAY)
    val counts = IntArray(MINUTES_PER_DAY)
    val nowSec = now.epochSecond
    for (sample in samples) {
        if (sample.epochSecond > nowSec) continue
        val local = Instant.ofEpochSecond(sample.epochSecond).atZone(zone)
        if (local.toLocalDate() != date) continue
        val bpm = sample.heartRateBpm ?: continue
        if (bpm == 0 || !SignalBounds.plausibleBpm(bpm)) continue
        val minute = local.hour * 60 + local.minute
        sums[minute] += bpm
        counts[minute]++
    }
    return List(MINUTES_PER_DAY) { minute ->
        val count = counts[minute]
        if (count == 0) null else (sums[minute].toDouble() / count).roundToInt()
    }
}

private const val MINUTES_PER_DAY = 24 * 60
