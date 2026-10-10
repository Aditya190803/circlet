package app.circlet.analytics.workout

import app.circlet.analytics.strain.CalorieProfile
import app.circlet.analytics.strain.Calories
import app.circlet.analytics.strain.HeartRateZones
import app.circlet.analytics.strain.HrPoint
import app.circlet.analytics.strain.banisterSex
import app.circlet.analytics.strain.calorieSex
import app.circlet.analytics.strain.resolveMaxHr
import app.circlet.analytics.strain.strainScoreFromSeries
import app.circlet.analytics.strain.zoneSet
import app.circlet.analytics.support.confidenceTier
import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.HeartRateRecovery
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.SignalBounds
import app.circlet.core.Sport
import app.circlet.core.ZoneMinutes
import app.circlet.core.ageOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Ported from OpenStrap lib/src/onehz/workout/auto_detect.dart, sport.dart,
// and workout.dart. A detected bout is a suggestion: heart rate at or above
// max(RHR+40, RHR+0.45·HRR) for 12 minutes. The 1 Hz stream cannot type a
// sport (Mannini HAR needs ~100 Hz), so the guess is "detected" at low
// confidence. A recorded window and a merge of windows use the same Banister
// strain, zones, calories, and heart-rate recovery as a detected bout.

fun detectActivities(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    restingHeartRateBpm: Double?,
    quietWakingHrr: Double?,
    quietSettled: Boolean,
    priorResting: List<Double> = emptyList(),
    saved: List<Pair<Instant, Instant>> = emptyList(),
): List<Activity> {
    val worn = samples.filter { it.epochSecond <= now.epochSecond && it.on(date, zone) && it.measured() != null }
    if (worn.isEmpty()) return emptyList()
    val hrTs = worn.map { it.epochSecond.toInt() }
    val hrBpm = worn.map { it.measured()!! }
    val motion = motionOf(samples.filter { it.epochSecond <= now.epochSecond && it.on(date, zone) })
    val savedSpans = saved.map { SavedSpan(it.first.epochSecond.toInt(), it.second.epochSecond.toInt()) }
    val maxHr = resolveMaxHr(profile, date)?.roundToInt()
    val found = AutoWorkoutDetector.detect(
        hrTs = hrTs,
        hrBpm = hrBpm,
        restingBpm = restingHeartRateBpm?.roundToInt(),
        maxBpm = maxHr,
        motion = motion,
        savedSpans = savedSpans,
    )
    return found.map { bout ->
        windowActivity(
            samples = samples,
            profile = profile,
            zone = zone,
            start = Instant.ofEpochSecond(bout.startSec.toLong()),
            end = Instant.ofEpochSecond(bout.endSec.toLong()),
            sport = sportFromLabel(bout.sport),
            source = ActivitySource.AUTO,
            sportConfidence = Confidence.LOW,
            id = "auto-${bout.startSec}",
            restingHeartRateBpm = restingHeartRateBpm,
            quietWakingHrr = quietWakingHrr,
            quietSettled = quietSettled,
            priorResting = priorResting,
        )
    }
}

fun recordedActivity(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    start: Instant,
    end: Instant,
    sport: Sport,
    id: String,
    restingHeartRateBpm: Double?,
    quietWakingHrr: Double?,
    quietSettled: Boolean,
    priorResting: List<Double> = emptyList(),
): Activity = windowActivity(
    samples, profile, zone, start, end, sport, ActivitySource.MANUAL, null, id,
    restingHeartRateBpm, quietWakingHrr, quietSettled, priorResting,
)

fun mergeActivities(
    activities: List<Activity>,
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    restingHeartRateBpm: Double?,
    quietWakingHrr: Double?,
    quietSettled: Boolean,
    priorResting: List<Double> = emptyList(),
): Activity {
    require(activities.isNotEmpty())
    val start = activities.minOf { it.start }
    val end = activities.maxOf { it.end }
    val sport = activities.map { it.sport }.distinct().singleOrNull() ?: Sport.OTHER
    val source = if (activities.any { it.source == ActivitySource.MANUAL }) {
        ActivitySource.MANUAL
    } else {
        ActivitySource.AUTO
    }
    return windowActivity(
        samples, profile, zone, start, end, sport, source,
        if (source == ActivitySource.AUTO) Confidence.LOW else null,
        "merged-" + activities.joinToString("-") { it.id },
        restingHeartRateBpm, quietWakingHrr, quietSettled, priorResting,
    )
}

fun heartRateRecovery(
    samples: List<Sample>,
    activityEnd: Instant,
    lookbackSec: Int = 30,
    followSec: Int = 180,
): HeartRateRecovery? {
    val endSec = activityEnd.epochSecond
    val window = samples
        .filter { it.epochSecond in (endSec - lookbackSec)..(endSec + followSec) }
        .sortedBy { it.epochSecond }
    if (window.isEmpty()) return null
    val endIndex = window.indexOfLast { it.epochSecond <= endSec }
    if (endIndex < 0) return null
    val scored = hrRecovery(
        hrTailBpm = window.map { it.heartRateBpm ?: 0 },
        endIndex = endIndex,
        tsSec = window.map { it.epochSecond.toInt() },
    )
    val drop = scored.value?.dropBpm ?: return null
    return HeartRateRecovery(Metric.of(drop.roundToInt(), confidenceTier(scored.confidence)))
}

private fun windowActivity(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    start: Instant,
    end: Instant,
    sport: Sport,
    source: ActivitySource,
    sportConfidence: Confidence?,
    id: String,
    restingHeartRateBpm: Double?,
    quietWakingHrr: Double?,
    quietSettled: Boolean,
    priorResting: List<Double>,
): Activity {
    val inside = samples.filter { it.epochSecond in start.epochSecond..end.epochSecond }.sortedBy { it.epochSecond }
    val bpm = inside.mapNotNull { it.measured() }
    val date = start.atZone(zone).toLocalDate()
    val maxHr = resolveMaxHr(profile, date)
    val minutes = perMinuteIn(inside)
    val strain = if (quietWakingHrr == null) {
        Metric.absent<Double>(AbsentReason.CALIBRATING)
    } else {
        strainScoreFromSeries(
            minutes,
            restingHeartRateBpm,
            maxHr,
            quietWakingHrr,
            banisterSex(profile.sex),
            quietSettled,
        ).toCore()
    }
    val avg = if (bpm.isEmpty()) {
        Metric.absent<Int>(if (inside.isEmpty()) AbsentReason.NOT_WORN else AbsentReason.NOT_ENOUGH_DATA)
    } else {
        Metric.of((bpm.sum().toDouble() / bpm.size).roundToInt(), confidenceTier(0.6))
    }
    val peak = if (bpm.isEmpty()) avg else Metric.of(bpm.max(), confidenceTier(0.6))
    val zones = zonesOf(inside, profile, date, priorResting, restingHeartRateBpm)
    val calories = boutCalories(inside, profile, date, restingHeartRateBpm, maxHr)
    return Activity(
        id = id,
        source = source,
        sport = sport,
        sportConfidence = sportConfidence,
        start = start,
        end = end,
        strain = strain,
        averageHeartRateBpm = avg,
        maxHeartRateBpm = peak,
        calories = calories,
        zoneMinutes = zones,
        heartRateRecovery = heartRateRecovery(samples, end),
    )
}

private fun boutCalories(
    inside: List<Sample>,
    profile: Profile,
    date: LocalDate,
    resting: Double?,
    maxHr: Double?,
): Metric<Int> {
    val age = profile.ageOn(date)?.toDouble()
    val weight = profile.weightKg
    val height = profile.heightCm
    val measured = inside.mapNotNull { s -> s.measured()?.let { s.epochSecond.toInt() to it.toDouble() } }
    if (age == null || weight == null || height == null || resting == null || maxHr == null || measured.isEmpty()) {
        return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    }
    val kcal = Calories.estimateBoutCalories(
        measured.map { it.first },
        measured.map { it.second },
        CalorieProfile(weight, height, age, calorieSex(profile.sex)),
        maxHr,
        resting,
    )
    if (kcal.usedDefaultAnchors) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return Metric.of(kcal.kcal.roundToInt(), confidenceTier(0.55))
}

private fun zonesOf(
    inside: List<Sample>,
    profile: Profile,
    date: LocalDate,
    priorResting: List<Double>,
    resting: Double?,
): ZoneMinutes {
    val set = zoneSet(profile, date, priorResting, resting) ?: return ZoneMinutes.ZERO
    val points = inside.mapNotNull { s ->
        s.measured()?.let { HrPoint(s.epochSecond * 1000.0, it.toDouble()) }
    }
    val time = HeartRateZones.timeInZone(points, set) ?: return ZoneMinutes.ZERO
    val rounded = time.toRoundedMinuteMap()
    return ZoneMinutes.of(
        kotlin.math.round(time.belowZone1 / 60.0).toInt(),
        rounded.getValue("z1"),
        rounded.getValue("z2"),
        rounded.getValue("z3"),
        rounded.getValue("z4"),
        rounded.getValue("z5"),
    )
}

private fun perMinuteIn(samples: List<Sample>): List<Double> {
    if (samples.isEmpty()) return emptyList()
    val byMinute = LinkedHashMap<Long, MutableList<Int>>()
    for (s in samples) {
        val bpm = s.measured() ?: continue
        byMinute.getOrPut(s.epochSecond / 60) { ArrayList() }.add(bpm)
    }
    if (byMinute.isEmpty()) return emptyList()
    val first = byMinute.keys.min()
    val last = byMinute.keys.max()
    return (first..last).map { minute ->
        val xs = byMinute[minute]
        if (xs.isNullOrEmpty()) 0.0 else xs.sum().toDouble() / xs.size
    }
}

private fun motionOf(samples: List<Sample>): List<MotionPoint> {
    val withG = samples.filter { it.gravity != null }.sortedBy { it.epochSecond }
    if (withG.isEmpty()) return emptyList()
    return AutoWorkoutDetector.motionPoints(
        withG.map { it.epochSecond.toInt() },
        withG.map { it.gravity!!.x.toDouble() },
        withG.map { it.gravity!!.y.toDouble() },
        withG.map { it.gravity!!.z.toDouble() },
    )
}

private fun Sample.on(date: LocalDate, zone: ZoneId): Boolean =
    Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate() == date

private fun Sample.measured(): Int? {
    val bpm = heartRateBpm ?: return null
    if (bpm == 0 || !SignalBounds.plausibleBpm(bpm)) return null
    return bpm
}

internal fun sportFromLabel(label: String): Sport = when (label.lowercase()) {
    "running", "run" -> Sport.RUNNING
    "cycling", "cycle" -> Sport.CYCLING
    "walking", "walk" -> Sport.WALKING
    "strength" -> Sport.STRENGTH
    "hiit" -> Sport.HIIT
    "yoga" -> Sport.YOGA
    else -> Sport.OTHER
}
