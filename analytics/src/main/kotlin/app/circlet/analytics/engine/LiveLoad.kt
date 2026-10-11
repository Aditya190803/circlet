package app.circlet.analytics.engine

import app.circlet.analytics.strain.Calories
import app.circlet.analytics.strain.banisterSex
import app.circlet.analytics.strain.calorieSex
import app.circlet.analytics.strain.personalQuietWakingHrr
import app.circlet.analytics.strain.resolveMaxHr
import app.circlet.analytics.strain.strainScoreFromSeries
import app.circlet.analytics.strain.zoneSet
import app.circlet.analytics.support.confidenceTier
import app.circlet.core.AbsentReason
import app.circlet.core.DaySummary
import app.circlet.core.LiveLoad
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.SignalBounds
import app.circlet.core.ZoneMinutes
import app.circlet.core.ageOn
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Anchors a live workout can use without inventing a resting rate or a quiet level.
 * [days] are oldest first and may include today. Today does not train the quiet baseline.
 */
data class LiveAnchors(
    val restingHeartRateBpm: Double?,
    val quietWakingHrr: Double?,
    val quietSettled: Boolean,
    val priorResting: List<Double>,
)

fun liveAnchors(days: List<DaySummary>, profile: Profile): LiveAnchors {
    val ordered = days.sortedBy { it.date }
    val today = ordered.lastOrNull()
    val prior = if (ordered.size <= 1) emptyList() else ordered.dropLast(1)
    val quiet = personalQuietWakingHrr(prior.mapNotNull { quietWakingHrr(it, profile) })
    return LiveAnchors(
        restingHeartRateBpm = today?.heart?.resting?.bpm?.value?.toDouble(),
        quietWakingHrr = quiet.value?.hrr,
        quietSettled = quiet.value?.settled == true,
        priorResting = prior.mapNotNull { it.heart.resting.bpm.value?.toDouble() },
    )
}

/**
 * Strain, calories, and zone minutes for a workout that is still being recorded.
 * [heartRatesBpm] is about one beat per second, in order. Missing anchors stay absent.
 */
fun estimateLiveLoad(
    heartRatesBpm: List<Int>,
    profile: Profile,
    on: LocalDate,
    anchors: LiveAnchors,
): LiveLoad {
    if (heartRatesBpm.isEmpty()) {
        return LiveLoad(
            strain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            zoneMinutes = ZoneMinutes.ZERO,
        )
    }
    val minutes = sessionMinutes(heartRatesBpm)
    val resting = anchors.restingHeartRateBpm
    val maxHr = resolveMaxHr(profile, on)
    val quiet = anchors.quietWakingHrr
    val strain = if (quiet == null) {
        Metric.absent(AbsentReason.CALIBRATING)
    } else {
        strainScoreFromSeries(
            minutes,
            resting,
            maxHr,
            quiet,
            banisterSex(profile.sex),
            anchors.quietSettled,
        ).toCore()
    }
    return LiveLoad(
        strain = strain,
        calories = boutCalories(heartRatesBpm, profile, on, resting, maxHr),
        zoneMinutes = zonesOf(heartRatesBpm, profile, on, anchors.priorResting, resting),
    )
}

private fun sessionMinutes(heartRatesBpm: List<Int>): List<Double> {
    val minutes = ArrayList<Double>((heartRatesBpm.size + 59) / 60)
    var index = 0
    while (index < heartRatesBpm.size) {
        val end = minOf(index + 60, heartRatesBpm.size)
        var sum = 0.0
        var count = 0
        for (cursor in index until end) {
            val bpm = heartRatesBpm[cursor]
            if (SignalBounds.plausibleBpm(bpm)) {
                sum += bpm
                count++
            }
        }
        minutes.add(if (count == 0) 0.0 else sum / count)
        index = end
    }
    return minutes
}

private fun zonesOf(
    heartRatesBpm: List<Int>,
    profile: Profile,
    on: LocalDate,
    priorResting: List<Double>,
    resting: Double?,
): ZoneMinutes {
    val set = zoneSet(profile, on, priorResting, resting) ?: return ZoneMinutes.ZERO
    val seconds = IntArray(ZoneMinutes.ZONE_COUNT)
    for (bpm in heartRatesBpm) {
        if (!SignalBounds.plausibleBpm(bpm)) continue
        val zone = set.zoneNumber(bpm.toDouble())
        if (zone in seconds.indices) seconds[zone]++
    }
    return ZoneMinutes(seconds.map { it / 60 })
}

private fun boutCalories(
    heartRatesBpm: List<Int>,
    profile: Profile,
    on: LocalDate,
    resting: Double?,
    maxHr: Double?,
): Metric<Int> {
    val age = profile.ageOn(on)?.toDouble()
    val weight = profile.weightKg
    val height = profile.heightCm
    if (age == null || weight == null || height == null || resting == null || maxHr == null) {
        return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    }
    val times = ArrayList<Int>(heartRatesBpm.size)
    val rates = ArrayList<Double>(heartRatesBpm.size)
    heartRatesBpm.forEachIndexed { index, bpm ->
        if (!SignalBounds.plausibleBpm(bpm)) return@forEachIndexed
        times.add(index)
        rates.add(bpm.toDouble())
    }
    if (rates.isEmpty()) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    val kcal = Calories.estimateBoutCalories(
        times,
        rates,
        app.circlet.analytics.strain.CalorieProfile(weight, height, age, calorieSex(profile.sex)),
        maxHr,
        resting,
    )
    if (kcal.usedDefaultAnchors) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return Metric.of(kcal.kcal.roundToInt(), confidenceTier(0.55))
}
