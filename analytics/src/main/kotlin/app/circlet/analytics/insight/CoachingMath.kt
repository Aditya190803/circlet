package app.circlet.analytics.insight

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.median
import app.circlet.analytics.support.percentile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

// Ported from OpenStrap lib/src/onehz/human/coaching.dart (sleep need,
// recommended bedtime and wake) and human/sleep_regularity.dart (the
// free-night percentile, which is not Kitamura's optimal sleep duration).
// The strain target lives in the strain package; it is the same function.

internal data class SleepNeedValue(val needSec: Double)

internal fun sleepNeed(
    baselineNeedSec: Double,
    sleepDebtSec: Double,
    dayStrain: Double,
    napCreditSec: Double,
): Scored<SleepNeedValue> {
    val strainBonus = (dayStrain.coerceIn(0.0, 21.0) / 21.0) * 45.0 * 60.0
    val adjusted = (baselineNeedSec + sleepDebtSec + strainBonus - napCreditSec).coerceIn(6 * 3600.0, 11 * 3600.0)
    return Scored.of(SleepNeedValue(adjusted), 0.6, "baseline need adjusted by debt, strain, and naps")
}

internal data class BedtimeRec(val bedtimeMinOfDay: Double)

internal fun recommendedBedtimeMinutes(
    needSec: Double,
    typicalWakeMinOfDay: Double,
    typicalEfficiencyPct: Double,
): Scored<BedtimeRec> {
    val bed = (typicalWakeMinOfDay - inBedMin(needSec, typicalEfficiencyPct))
    val wrapped = ((bed % 1440.0) + 1440.0) % 1440.0
    return Scored.of(BedtimeRec(wrapped), 0.6, "bedtime backed off from wake by time in bed")
}

internal data class WakeRec(val wakeMinOfDay: Double)

internal fun recommendedWakeMinutes(
    bedtimeMinOfDay: Double,
    needSec: Double,
    typicalEfficiencyPct: Double,
): Scored<WakeRec> {
    val wake = bedtimeMinOfDay + inBedMin(needSec, typicalEfficiencyPct)
    val wrapped = ((wake % 1440.0) + 1440.0) % 1440.0
    return Scored.of(WakeRec(wrapped), 0.55, "wake = bedtime + the time in bed the need and your efficiency imply")
}

private fun inBedMin(needSec: Double, typicalEfficiencyPct: Double): Double {
    val eff = (typicalEfficiencyPct / 100.0).coerceIn(0.75, 0.99)
    return needSec / eff / 60.0
}

/**
 * Instant to be in bed so [need] of sleep is met by a 07:00 wake, at the
 * caller's efficiency. Null when efficiency is missing: inventing 90% would
 * move the bedtime.
 */
fun recommendedBedtime(
    need: java.time.Duration,
    efficiencyPercent: Double?,
    zone: ZoneId,
    wakeDate: LocalDate,
    wakeMinuteOfDay: Int = 7 * 60,
): Instant? {
    if (efficiencyPercent == null || need.isZero || need.isNegative) return null
    val minutes = recommendedBedtimeMinutes(need.seconds.toDouble(), wakeMinuteOfDay.toDouble(), efficiencyPercent)
    val bedMin = minutes.value?.bedtimeMinOfDay ?: return null
    val whole = bedMin.toInt()
    val second = ((bedMin - whole) * 60.0).roundToLong()
    val time = LocalTime.of(whole / 60, whole % 60).plusSeconds(second)
    val wakeIsAfterBed = bedMin < wakeMinuteOfDay
    val bedDate = if (wakeIsAfterBed) wakeDate else wakeDate.minusDays(1)
    return ZonedDateTime.of(bedDate, time, zone).toInstant()
}

internal data class FreeNightDebt(
    val osdHours: Double?,
    val habitualHours: Double,
    val debtHours: Double?,
    val hasFreeNight: Boolean,
)

internal fun sleepDebtEstimate(
    recentSleepH: List<Double>,
    freeNightSleepH: List<Double>,
    minRecent: Int = 3,
): Scored<FreeNightDebt> {
    if (recentSleepH.size < minRecent) {
        return Scored.absent("need more recent nights to estimate habitual sleep")
    }
    val habitual = median(recentSleepH)!!
    if (freeNightSleepH.isEmpty()) {
        return Scored.of(FreeNightDebt(null, habitual, null, false), 0.3, "no free night yet")
    }
    val osd = percentile(freeNightSleepH, 75.0)!!
    return Scored.of(
        FreeNightDebt(osd, habitual, osd - habitual, true),
        (freeNightSleepH.size / 5.0).coerceIn(0.3, 0.85),
        "p75 of unconstrained nights vs habitual night",
    )
}
