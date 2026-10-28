// Ported from OpenStrap analytics/lib/src/onehz/wellness/temp_circadian.dart
// (nightlySkinTemp and its settling gate) and temp_health.dart (the Smarr 2020
// illness flag; the menstrual coverline is not ported).
// Method: Smarr et al., Sci Rep 2020;10:21640 — a sustained per-subject
// elevation of the nightly mean against a trailing baseline. The 2.0 robust-z
// threshold is ours on an uncalibrated relative channel; Smarr reports +0.63 C
// against a per-subject baseline and supplies no z threshold.
//
// Gen 4 uses the measured 40-count settling band. OpenStrap has no measured
// Gen 5 settling band, so its centi-degree channel abstains from nightly metrics.
// Counts and centi-degrees never share a baseline.

package app.circlet.analytics.temperature

import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.robustBaseline
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.StrapGeneration
import app.circlet.core.SkinTemperatureUnit
import java.time.LocalDate

/** A sample is settled while it sits no more than this far below the night median, in counts. */
const val SKIN_TEMP_SETTLE_BAND_COUNTS = 40.0

/** Minimum settled fraction before a nightly mean may be used. Costs about one night in eight. */
const val SKIN_TEMP_MIN_SETTLED_FRACTION = 0.80

/** Minimum valid samples in the sleep window. */
const val SKIN_TEMP_MIN_SAMPLES = 60

/** Trailing baseline window, in calendar days. */
const val SKIN_TEMP_BASELINE_DAYS = 21

/** Robust-z elevation threshold. Ours on a relative channel; see the file header. */
const val SKIN_TEMP_Z_THRESH = 2.0

/** Consecutive elevated nights before the flag fires. */
const val SKIN_TEMP_PERSIST_NIGHTS = 2

/** Minimum valid baseline nights before a deviation is shown, as in the source. */
const val SKIN_TEMP_MIN_BASELINE_NIGHTS = 7

/**
 * One night's settled mean. [meanCounts] is ADC counts, never degrees.
 * [settledFraction] is the share of valid samples the mean is made of.
 */
data class SettledSkinTemp(
    val meanCounts: Double,
    val settledFraction: Double,
)

/**
 * Nightly skin-temp mean over the settled portion of a sleep window.
 *
 * Null and non-positive counts are not samples. A night under [minSamples]
 * valid samples, or one whose settled fraction is under [minSettledFraction],
 * has no usable temperature: the result is absent and the caller drops the
 * input instead of substituting one. The channel itself is never nulled here.
 */
fun nightlySkinTemp(
    counts: List<Int?>,
    minSamples: Int = SKIN_TEMP_MIN_SAMPLES,
    minSettledFraction: Double = SKIN_TEMP_MIN_SETTLED_FRACTION,
    settleBandLow: Double = SKIN_TEMP_SETTLE_BAND_COUNTS,
    generation: StrapGeneration = StrapGeneration.GEN4,
): Metric<SettledSkinTemp> {
    // OpenStrap has no measured Gen 5 settling band; Gen 4 counts cannot calibrate centi-°C.
    if (generation != StrapGeneration.GEN4) return Metric.absent(AbsentReason.CALIBRATING)
    val valid = ArrayList<Double>(counts.size)
    for (count in counts) {
        if (count != null && count > 0) valid += count.toDouble()
    }
    if (valid.size < minSamples) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    val floor = median(valid)!! - settleBandLow
    val kept = valid.filter { it >= floor }
    val fraction = kept.size.toDouble() / valid.size
    if (fraction < minSettledFraction) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return Metric.of(
        SettledSkinTemp(mean(kept)!!, fraction),
        when {
            fraction >= 0.95 -> Confidence.HIGH
            fraction >= 0.85 -> Confidence.MEDIUM
            else -> Confidence.LOW
        },
    )
}

/** One night's settled mean in counts. Null is a missing night. */
data class NightlyTemp(
    val date: LocalDate,
    val meanCounts: Double?,
    val unit: SkinTemperatureUnit = SkinTemperatureUnit.ADC_COUNTS,
)

enum class TempElevation {
    NORMAL,
    ELEVATED,
}

data class TempIllnessNight(
    val date: LocalDate,
    val elevation: TempElevation,
    /** Robust z of tonight's mean against the trailing baseline. Null when unscorable. */
    val z: Double?,
    val confidence: Confidence,
)

/**
 * Nightly relative skin-temp illness flag over raw nightly means in counts.
 *
 * Pass raw means, never z-scores: this standardises internally against its own
 * trailing window, so a pre-standardised series is standardised twice and
 * [zThresh] stops meaning what it says. "N nights running" means consecutive
 * calendar nights, not consecutive rows. A degenerate (flat) baseline reports
 * a null z and stays normal rather than inventing a deviation.
 *
 * [luteal] marks nights in the luteal phase, which warms distal skin enough to
 * mimic a fever on a relative sensor. A sustained elevation on such a night is
 * suppressed to normal at low confidence instead of flagged. Circlet tracks no
 * cycle, so callers pass null; the parameter stays so the gate is not lost.
 */
fun tempIllnessFlag(
    nights: List<NightlyTemp>,
    baselineDays: Int = SKIN_TEMP_BASELINE_DAYS,
    zThresh: Double = SKIN_TEMP_Z_THRESH,
    persistDays: Int = SKIN_TEMP_PERSIST_NIGHTS,
    minBaseline: Int = SKIN_TEMP_MIN_BASELINE_NIGHTS,
    luteal: List<Boolean>? = null,
): List<TempIllnessNight> {
    val out = ArrayList<TempIllnessNight>(nights.size)
    val days = nights.map { it.date.toEpochDay() }
    var elevatedRun = 0
    var lastScoredDay = Long.MIN_VALUE / 4
    for (i in nights.indices) {
        val tonight = nights[i].meanCounts
        val lut = luteal?.getOrNull(i) == true
        val window = ArrayList<Double>()
        for (j in i - 1 downTo 0) {
            if (days[i] - days[j] > baselineDays) break
            if (nights[j].unit == nights[i].unit) nights[j].meanCounts?.let { window += it }
        }
        if (tonight == null || window.size < minBaseline) {
            out += TempIllnessNight(nights[i].date, TempElevation.NORMAL, null, Confidence.LOW)
            elevatedRun = 0
            continue
        }
        val z = robustBaseline(window, minValid = minBaseline).modZ(tonight)
        if (z == null) {
            out += TempIllnessNight(nights[i].date, TempElevation.NORMAL, null, Confidence.LOW)
            elevatedRun = 0
            continue
        }
        if (days[i] - lastScoredDay > 1 || (i > 0 && nights[i].unit != nights[i - 1].unit)) elevatedRun = 0
        lastScoredDay = days[i]
        val elevated = z >= zThresh
        if (elevated) elevatedRun++ else elevatedRun = 0
        if (!elevated || elevatedRun < persistDays) {
            out += TempIllnessNight(
                nights[i].date,
                TempElevation.NORMAL,
                z,
                if (elevated) Confidence.LOW else Confidence.MEDIUM,
            )
        } else if (lut) {
            out += TempIllnessNight(nights[i].date, TempElevation.NORMAL, z, Confidence.LOW)
        } else {
            out += TempIllnessNight(nights[i].date, TempElevation.ELEVATED, z, Confidence.HIGH)
        }
    }
    return out
}

/**
 * The night's skin temperature for a day summary.
 *
 * [nights] is trailing settled means in ascending date order with the target
 * night last; the engine rebuilds the means from stored samples. [deviation]
 * is tonight's robust z against the trailing baseline, in standard deviations
 * of the user's own nights. The flag stays [SkinTempFlag.NOT_ENOUGH_BASELINE]
 * until [minBaseline] prior nights exist, and turns
 * [SkinTempFlag.ELEVATED] only on a sustained elevation of [persistDays]
 * consecutive nights at or above [zThresh].
 */
fun scoreSkinTemp(
    nights: List<NightlyTemp>,
    baselineDays: Int = SKIN_TEMP_BASELINE_DAYS,
    zThresh: Double = SKIN_TEMP_Z_THRESH,
    persistDays: Int = SKIN_TEMP_PERSIST_NIGHTS,
    minBaseline: Int = SKIN_TEMP_MIN_BASELINE_NIGHTS,
    luteal: List<Boolean>? = null,
): SkinTemperature {
    require(nights.isNotEmpty()) { "scoreSkinTemp needs the target night" }
    val target = nights.last()
    val targetDay = target.date.toEpochDay()
    var baselineNights = 0
    for (i in nights.size - 2 downTo 0) {
        if (targetDay - nights[i].date.toEpochDay() > baselineDays) break
        if (nights[i].unit == target.unit && nights[i].meanCounts != null) baselineNights++
    }
    val tonight = tempIllnessFlag(nights, baselineDays, zThresh, persistDays, minBaseline, luteal).last()
    val deviation = tonight.z?.let { Metric.of(it, tonight.confidence) }
        ?: Metric.absent(
            if (target.meanCounts == null) AbsentReason.NOT_ENOUGH_DATA else AbsentReason.CALIBRATING,
        )
    // A flat baseline standardises to nothing: history exists but no honest
    // deviation does. That is missing data, not a missing baseline.
    val honest = if (tonight.z == null && target.meanCounts != null && baselineNights >= minBaseline) {
        Metric.absent<Double>(AbsentReason.NOT_ENOUGH_DATA)
    } else {
        deviation
    }
    val flag = when {
        target.meanCounts == null || baselineNights < minBaseline -> SkinTempFlag.NOT_ENOUGH_BASELINE
        tonight.elevation == TempElevation.ELEVATED -> SkinTempFlag.ELEVATED
        else -> SkinTempFlag.NORMAL
    }
    return SkinTemperature(deviation = honest, baselineNights = baselineNights, flag = flag, unit = target.unit)
}
