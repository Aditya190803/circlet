package app.circlet.analytics.strain

import app.circlet.analytics.support.confidenceTier
import app.circlet.analytics.support.reasonFor
import app.circlet.core.AbsentReason
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.Sex
import app.circlet.core.SignalBounds
import app.circlet.core.Strain
import app.circlet.core.TargetStrain
import app.circlet.core.ZoneMinutes
import app.circlet.core.ageOn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

// Day strain, zone minutes, calories, and today's target.
// Ported from OpenStrap lib/src/onehz/clinical/load_trimp.dart (Banister 1991),
// workout/hr_zones.dart (Karvonen reserve, else Tanaka %HRmax), workout/calories.dart
// (Keytel 2005 + Mifflin–St Jeor), and human/coaching.dart strainTarget.
// The 0–21 number is strainScoreFromSeries: TRIMP above this user's own
// quiet-waking heart-rate reserve. Fewer than three prior quiet days abstains.

/**
 * One local day's cardiovascular load.
 *
 * [priorQuietWakingHrr] is the trailing series of [dailyQuietFromSamples]
 * values from days strictly before [date], oldest first. [DaySummary] does
 * not store that level, so the day engine has to keep it.
 */
data class DayLoad(
    val strain: Strain,
    val calories: Metric<Int>,
    val quietWakingHrr: Double?,
)

fun dailyQuietFromSamples(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    restingHeartRateBpm: Double?,
    maxHeartRateBpm: Double?,
): Double? = dailyQuietWakingHrr(
    perMinuteBpm(samples.upTo(now), zone, date),
    restingHeartRateBpm,
    maxHeartRateBpm,
    minMinutes = QUIET_HRR_TRAIT_MIN_MINUTES,
)

fun dayLoad(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    restingHeartRateBpm: Double?,
    priorQuietWakingHrr: List<Double>,
    priorResting: List<Double> = emptyList(),
): DayLoad {
    val clipped = samples.upTo(now)
    val minutes = perMinuteBpm(clipped, zone, date)
    val worn = minutes.count { it > 0 }
    if (worn == 0) {
        val absent = Metric.absent<Double>(AbsentReason.NOT_WORN)
        return DayLoad(
            Strain(absent, ZoneMinutes.ZERO),
            Metric.absent(AbsentReason.NOT_WORN),
            null,
        )
    }
    val maxHr = resolveMaxHr(profile, date)
    val sex = banisterSex(profile.sex)
    val quietToday = dailyQuietWakingHrr(minutes, restingHeartRateBpm, maxHr)
    val personal = personalQuietWakingHrr(priorQuietWakingHrr)
    val strainMetric = if (personal.value == null) {
        personal.toCore(AbsentReason.CALIBRATING).let { Metric.absent<Double>(AbsentReason.CALIBRATING) }
    } else {
        strainScoreFromSeries(
            minutes,
            restingHeartRateBpm,
            maxHr,
            personal.value.hrr,
            sex,
            personal.value.settled,
        ).toCore()
    }
    val zones = zoneMinutes(clipped, profile, zone, date, now, restingHeartRateBpm, priorResting)
    val kcal = dayCaloriesFromMinutes(minutes, profile, date, restingHeartRateBpm, maxHr)
    return DayLoad(Strain(strainMetric, zones), kcal, quietToday)
}

/**
 * Target strain for today from a recovery score, on the 0–21 scale.
 * The OpenStrap band (coaching.dart `strainTarget`) is reduced to its midpoint
 * because [TargetStrain] carries one number. Load ratios use raw daily TRIMP.
 */
fun targetStrain(
    recoveryScore: Int?,
    ctl: Double? = null,
    atl: Double? = null,
    tsb: Double? = null,
): TargetStrain {
    val band = strainTargetBand(recoveryScore?.toDouble(), ctl, atl, tsb)
    val value = band.value ?: return TargetStrain(Metric.absent(reasonFor(band.note)))
    val mid = (value.low + value.high) / 2.0
    return TargetStrain(Metric.of(mid, confidenceTier(band.confidence)))
}

internal data class StrainBand(val low: Double, val high: Double, val name: String)

internal fun strainTargetBand(
    recovery0to100: Double?,
    ctl: Double?,
    atl: Double?,
    tsb: Double?,
): ScoredBand {
    if (recovery0to100 == null) return ScoredBand(null, 0.0, "recovery absent")
    val rec = recovery0to100.coerceIn(0.0, 100.0)
    var lo: Double
    var hi: Double
    val name: String
    when {
        rec < 40 -> {
            lo = 0.0; hi = 5.0; name = "recover"
        }
        rec < 60 -> {
            lo = 5.0; hi = 10.0; name = "ease"
        }
        rec < 80 -> {
            lo = 9.0; hi = 14.0; name = "maintain"
        }
        else -> {
            lo = 13.0; hi = 18.0; name = "push"
        }
    }
    val hasLoad = ctl != null && ctl > 0
    val fatigue = if (hasLoad && atl != null) atl / ctl else null
    val freshness = if (hasLoad && tsb != null) tsb / ctl else null
    if (fatigue != null && fatigue > 1.10) {
        lo -= 1
        hi -= 2
    } else if (freshness != null && freshness > 0.10) {
        hi += 1
    }
    lo = lo.coerceIn(0.0, 21.0)
    hi = hi.coerceIn(lo + 1, 21.0)
    return ScoredBand(StrainBand(lo, hi, name), 0.6, "Target shaped by recovery and recent load.")
}

internal data class ScoredBand(val value: StrainBand?, val confidence: Double, val note: String)

fun zoneMinutes(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    restingHeartRateBpm: Double?,
    priorResting: List<Double>,
): ZoneMinutes {
    val set = zoneSet(profile, date, priorResting, restingHeartRateBpm) ?: return ZoneMinutes.ZERO
    val range = dayRange(date, zone)
    val nowSec = now.epochSecond
    val points = ArrayList<HrPoint>()
    for (sample in samples) {
        val epoch = sample.epochSecond
        if (epoch > nowSec || epoch !in range) continue
        val bpm = sample.heartRateBpm ?: continue
        if (bpm == 0 || !SignalBounds.plausibleBpm(bpm)) continue
        points.add(HrPoint(epoch * 1000.0, bpm.toDouble()))
    }
    val time = HeartRateZones.timeInZone(points, set) ?: return ZoneMinutes.ZERO
    val rounded = time.toRoundedMinuteMap()
    val below = kotlin.math.round(time.belowZone1 / 60.0).toInt()
    return ZoneMinutes.of(
        below,
        rounded.getValue("z1"),
        rounded.getValue("z2"),
        rounded.getValue("z3"),
        rounded.getValue("z4"),
        rounded.getValue("z5"),
    )
}

internal fun zoneSet(
    profile: Profile,
    date: LocalDate,
    priorResting: List<Double>,
    restingToday: Double?,
): HeartRateZoneSet? {
    if (profile.customZones.size == 5) {
        val ordered = profile.customZones.sortedBy { it.index }
        val built = ordered.map { z ->
            DisplayZone(z.index, z.fromBpm.toDouble(), z.untilBpm.toDouble(), 0.0, 0.0)
        }
        val maxHr = resolveMaxHr(profile, date) ?: ordered.last().untilBpm.toDouble()
        return HeartRateZoneSet(built, maxHr, "custom")
    }
    val maxHr = resolveMaxHr(profile, date) ?: return null
    val history = if (restingToday != null) priorResting + restingToday else priorResting
    return HeartRateZones.reserveZones(history, maxHr)
        ?: profile.ageOn(date)?.let { HeartRateZones.zones(it.toDouble(), profile.maxHeartRateBpm?.toDouble()) }
}

fun dayCalories(
    samples: List<Sample>,
    profile: Profile,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    restingHeartRateBpm: Double?,
    maxHeartRateBpm: Double?,
): Metric<Int> {
    val age = profile.ageOn(date)?.toDouble()
    val weight = profile.weightKg
    val height = profile.heightCm
    if (age == null || weight == null || height == null || restingHeartRateBpm == null || maxHeartRateBpm == null) {
        return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    }
    val minutes = perMinuteBpm(samples.upTo(now), zone, date)
    if (minutes.none { it > 0 }) return Metric.absent(AbsentReason.NOT_WORN)
    return dayCaloriesFromMinutes(minutes, profile, date, restingHeartRateBpm, maxHeartRateBpm)
}

/**
 * [dayCalories] over an already-averaged minute table. The day engine's load
 * already averaged the day, so the table is computed once and shared instead
 * of scanning the samples a second time.
 */
internal fun dayCaloriesFromMinutes(
    minutes: List<Double>,
    profile: Profile,
    date: LocalDate,
    restingHeartRateBpm: Double?,
    maxHeartRateBpm: Double?,
): Metric<Int> {
    val age = profile.ageOn(date)?.toDouble()
    val weight = profile.weightKg
    val height = profile.heightCm
    if (age == null || weight == null || height == null || restingHeartRateBpm == null || maxHeartRateBpm == null) {
        return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    }
    if (minutes.none { it > 0 }) return Metric.absent(AbsentReason.NOT_WORN)
    val energy = Calories.dailyEnergy(
        minutes,
        CalorieProfile(weight, height, age, calorieSex(profile.sex)),
        maxHeartRateBpm,
        restingHeartRateBpm,
    ) ?: return Metric.absent(AbsentReason.IMPLAUSIBLE)
    return Metric.of(energy.total.roundToInt(), confidenceTier(0.55))
}

internal fun List<Sample>.upTo(now: Instant): List<Sample> = filter { it.epochSecond <= now.epochSecond }

/**
 * Epoch seconds of one local day. Exactly the instants whose local date is
 * [date], daylight-saving transitions included, so filtering by range is the
 * same as comparing local dates one sample at a time.
 */
internal fun dayRange(date: LocalDate, zone: ZoneId): LongRange {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return start until end
}

internal fun perMinuteBpm(samples: List<Sample>, zone: ZoneId, date: LocalDate): List<Double> {
    val sums = DoubleArray(1440)
    val counts = IntArray(1440)
    for (sample in samples) {
        val local = Instant.ofEpochSecond(sample.epochSecond).atZone(zone)
        if (local.toLocalDate() != date) continue
        val bpm = sample.heartRateBpm ?: continue
        if (bpm == 0 || !SignalBounds.plausibleBpm(bpm)) continue
        val minute = local.hour * 60 + local.minute
        sums[minute] += bpm
        counts[minute]++
    }
    return List(1440) { i -> if (counts[i] == 0) 0.0 else sums[i] / counts[i] }
}

internal fun resolveMaxHr(profile: Profile, date: LocalDate): Double? {
    profile.maxHeartRateBpm?.let { return it.toDouble() }
    val age = profile.ageOn(date) ?: return null
    return StrainScorer.tanakaHrMax(age.toDouble())
}

internal fun banisterSex(sex: Sex): BanisterSex =
    if (sex == Sex.FEMALE) BanisterSex.FEMALE else BanisterSex.MALE

internal fun calorieSex(sex: Sex): String = when (sex) {
    Sex.MALE -> "male"
    Sex.FEMALE -> "female"
    Sex.UNSPECIFIED -> "nonbinary"
}
