// Ported from OpenStrap analytics/lib/src/onehz/wellness/temp_circadian.dart
// (nightlySkinTemp and its settling gate) and temp_health.dart (the Smarr 2020
// illness flag; the menstrual coverline is not ported).
// Method: Smarr et al., Sci Rep 2020;10:21640 — a sustained per-subject
// elevation of the nightly mean against a trailing baseline. The 2.0 robust-z
// threshold is ours on an uncalibrated relative channel; Smarr reports +0.63 C
// against a per-subject baseline and supplies no z threshold.
//
// The nightly mean is ours, not OpenStrap's: its median-anchored settling gate
// rejected about half of real Gen 4 nights, whose covered and uncovered stretches
// sit hundreds of counts apart. The 40-count band now grades confidence only.
// Gen 5 runs the same method on its centi-degree channel with a 1 C band.
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

/** A Gen 4 sample is settled while it sits no more than this far below the warm mean, in counts. */
const val SKIN_TEMP_SETTLE_BAND_COUNTS = 40.0

/** A Gen 5 sample is settled while it sits no more than this far below the warm mean, in centi-degrees. */
const val SKIN_TEMP_SETTLE_BAND_CENTI_C = 100.0

/** Settled fraction at or above which a nightly mean is HIGH confidence. */
const val SKIN_TEMP_HIGH_SETTLED_FRACTION = 0.80

/** Settled fraction at or above which a nightly mean is MEDIUM confidence; below it, LOW. */
const val SKIN_TEMP_MEDIUM_SETTLED_FRACTION = 0.50

/** Minimum valid samples in the sleep window: half an hour at 1 Hz. */
const val SKIN_TEMP_MIN_SAMPLES = 1800

/** Trailing baseline window, in calendar days. */
const val SKIN_TEMP_BASELINE_DAYS = 21

/** Robust-z elevation threshold. Ours on a relative channel; see the file header. */
const val SKIN_TEMP_Z_THRESH = 2.0

/** Consecutive elevated nights before the flag fires. */
const val SKIN_TEMP_PERSIST_NIGHTS = 2

/** Minimum valid baseline nights before a deviation is shown, as in the source. */
const val SKIN_TEMP_MIN_BASELINE_NIGHTS = 7

/**
 * One night's warm-plateau mean, in the strap's own unit: ADC counts on Gen 4, centi-degrees on
 * Gen 5. [settledFraction] is the share of valid samples within the settling band of that mean.
 */
data class SettledSkinTemp(
    val meanCounts: Double,
    val settledFraction: Double,
)

/** The unit a strap family's nightly skin-temp means are kept in. Units never share a baseline. */
fun StrapGeneration.skinTempUnit(): SkinTemperatureUnit =
    if (this == StrapGeneration.GEN5) SkinTemperatureUnit.CENTI_CELSIUS else SkinTemperatureUnit.ADC_COUNTS

/**
 * Nightly skin-temp mean over the warm half of a sleep window.
 *
 * Wrist skin drops for whole stretches of a night while the arm is uncovered, so a single
 * night spans hundreds of counts on Gen 4. The covered-wrist plateau is the comparable part:
 * the mean of the samples at or above the night median. On real Gen 4 nights it moves about a
 * tenth as much between nights as the median does. Coolings that cover more of the night
 * lower the confidence instead of discarding it.
 *
 * Null and non-positive readings are not samples. A night under [minSamples] valid samples has
 * no usable temperature: the result is absent and the caller drops it.
 */
fun nightlySkinTemp(
    counts: List<Int?>,
    minSamples: Int = SKIN_TEMP_MIN_SAMPLES,
    generation: StrapGeneration = StrapGeneration.GEN4,
    settleBand: Double = if (generation == StrapGeneration.GEN5) SKIN_TEMP_SETTLE_BAND_CENTI_C else SKIN_TEMP_SETTLE_BAND_COUNTS,
): Metric<SettledSkinTemp> {
    val valid = ArrayList<Double>(counts.size)
    for (count in counts) {
        if (count != null && count > 0) valid += count.toDouble()
    }
    if (valid.size < minSamples) return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    val middle = median(valid)!!
    val warm = mean(valid.filter { it >= middle })!!
    val fraction = valid.count { it >= warm - settleBand }.toDouble() / valid.size
    return Metric.of(
        SettledSkinTemp(warm, fraction),
        when {
            fraction >= SKIN_TEMP_HIGH_SETTLED_FRACTION -> Confidence.HIGH
            fraction >= SKIN_TEMP_MEDIUM_SETTLED_FRACTION -> Confidence.MEDIUM
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
