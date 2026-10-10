// Ported from OpenStrap analytics/lib/src/onehz/human/coaching.dart.
// Sleep need / performance / bedtime (baseline need adjusted by debt, strain, naps; performance is asleep/need clamped 0..100).

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6

/** Baseline need adjusted by debt, strain, and naps. Named so it does not clash with core SleepNeed. */
data class NeedSeconds(val needSec: Double) {
    fun toJson(): Map<String, Any?> = linkedMapOf("need_sec" to needSec)
}

/** Asleep / need, clamped to 0..100. Named so it does not clash with core SleepPerformance. */
data class PerformancePct(val pct: Double) {
    fun toJson(): Map<String, Any?> = linkedMapOf("pct" to pct)
}

data class BedtimeRec(val bedtimeMinOfDay: Double) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "bedtime_min_of_day" to round6(bedtimeMinOfDay),
    )
}

data class WakeRec(val wakeMinOfDay: Double) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "wake_min_of_day" to round6(wakeMinOfDay),
    )
}

private val needInputs = listOf("sleep_debt", "strain", "naps")
private val performanceInputs = listOf("sleep_sec", "need_sec")
private val bedtimeInputs = listOf("sleep_need", "wake_time", "efficiency")
private val wakeInputs = listOf("sleep_need", "bedtime", "efficiency")

fun sleepNeed(
    baselineNeedSec: Double,
    sleepDebtSec: Double,
    dayStrain: Double,
    napCreditSec: Double,
): OsMetric<NeedSeconds> {
    val strainBonusSec = (dayStrain.coerceIn(0.0, 21.0) / 21.0) * 45.0 * 60.0
    val adjusted = (baselineNeedSec + sleepDebtSec + strainBonusSec - napCreditSec)
        .coerceIn(6 * 3600.0, 11 * 3600.0)
    return OsMetric(
        value = NeedSeconds(adjusted),
        confidence = 0.6,
        tier = Tier.ESTIMATE,
        inputsUsed = needInputs,
        note = "baseline need adjusted by debt, strain, and naps",
    )
}

fun sleepPerformance(sleepSec: Double, needSec: Double): OsMetric<PerformancePct> {
    if (needSec <= 0.0) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = performanceInputs,
        )
    }
    val pct = ((sleepSec / needSec) * 100.0).coerceIn(0.0, 100.0)
    return OsMetric(
        value = PerformancePct(pct),
        confidence = 0.7,
        tier = Tier.ESTIMATE,
        inputsUsed = performanceInputs,
    )
}

/**
 * Minutes in bed that deliver [needSec] of sleep at the user's own efficiency.
 * Shared by [recommendedBedtime] and [recommendedWake]. Efficiency is clamped
 * to [0.75, 0.99] so the two ends of the night cannot be built from different durations.
 */
private fun inBedMin(needSec: Double, typicalEfficiencyPct: Double): Double {
    val eff = (typicalEfficiencyPct / 100.0).coerceIn(0.75, 0.99)
    return needSec / eff / 60.0
}

fun recommendedBedtime(
    needSec: Double,
    typicalWakeMinOfDay: Double,
    typicalEfficiencyPct: Double,
): OsMetric<BedtimeRec> {
    val raw = typicalWakeMinOfDay - inBedMin(needSec, typicalEfficiencyPct)
    return OsMetric(
        value = BedtimeRec(wrap1440(raw)),
        confidence = 0.6,
        tier = Tier.ESTIMATE,
        inputsUsed = bedtimeInputs,
    )
}

/**
 * Target wake = the recommended bedtime plus the same time-in-bed the bedtime
 * was backed off from. Not cycle-quantized sleep minutes.
 */
fun recommendedWake(
    bedtimeMinOfDay: Double,
    needSec: Double,
    typicalEfficiencyPct: Double,
): OsMetric<WakeRec> {
    val raw = bedtimeMinOfDay + inBedMin(needSec, typicalEfficiencyPct)
    return OsMetric(
        value = WakeRec(wrap1440(raw)),
        confidence = 0.55,
        tier = Tier.ESTIMATE,
        inputsUsed = wakeInputs,
        note = "wake = bedtime + the time in bed the need and your efficiency imply",
    )
}

/** Dart `%` is Euclidean; Kotlin `%` follows the dividend, so a negative remainder wraps. */
private fun wrap1440(raw: Double): Double {
    val bedMin = raw % 1440.0
    return if (bedMin < 0.0) bedMin + 1440.0 else bedMin
}
