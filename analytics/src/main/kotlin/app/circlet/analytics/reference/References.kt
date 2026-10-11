// Reference ranges for the headline metrics: a personal baseline band plus a
// published ideal range.
//
// Personal bands reuse the foundations robust baseline (median + MAD, Leys
// 2013; Iglewicz-Hoaglin modified z): the trailing window's centre plus and
// minus one robust spread, requiring seven days — the same minimum the
// readiness baseline and the illness flags use.
//
// Ideal ranges are published population ranges only, each cited in
// [MetricReference.idealSource] and below. No invented numbers: a metric with
// no defensible published range carries a null ideal and a personal band only.
// - HRV RMSSD: Nunan et al. 2010, a quantitative review of short-term HRV in
//   21,438 healthy adults (pooled mean RMSSD ~42 ms, study means spanning
//   19–75 ms). These are SHORT-TERM RESTING norms; nightly sleep RMSSD usually
//   reads higher, so the band is context, not a target. One adult band: no
//   verified age/sex buckets were available, so none are encoded.
// - Resting heart rate: 60–100 bpm adult range (American Heart Association;
//   trained athletes often 40–60).
// - Respiratory rate at rest: 12–20 breaths per minute (adult clinical range).
// - Sleep duration: 7–9 h for adults, 8–10 h for teenagers, 7–8 h for older
//   adults (Watson et al. 2015, AASM/SRS consensus; Hirshkowitz et al. 2015,
//   National Sleep Foundation).
// - Sleep efficiency: 85% and above is considered normal (clinical criterion).
// - VO2max: Cooper Institute fitness category tables by age and sex (thresholds
//   as published in Garmin's manuals, reprinted with permission from The Cooper
//   Institute). The ideal is the "good" (60th percentile) threshold and above,
//   capped at the "superior" (95th) value for display; the label names the
//   user's category when today's value exists.
// - Steps: no ideal (the count is an estimate) and no personal band here.
// - Strain, recovery, stress: no population ideal; personal band only.
// - Skin temperature deviation: within ±1 SD of the user's own baseline
//   (Circlet's own nightly-deviation band, not a publication).

package app.circlet.analytics.reference

import app.circlet.analytics.foundations.robustBaseline
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.Profile
import app.circlet.core.ReferenceRange
import app.circlet.core.Sex
import app.circlet.core.ageOn
import java.time.LocalDate

/** Trailing window the personal bands are built from, in calendar days. */
const val REFERENCE_WINDOW_DAYS = 28

/** Minimum valid days before a personal band is shown. Matches the readiness baseline. */
const val REFERENCE_MIN_DAYS = 7

/**
 * The reference list for the target date.
 *
 * [history] is prior day summaries in any order; only days before [date] and
 * within [windowDays] of it are used. [minDays] valid values are required
 * before a personal band appears; [MetricReference.personalDays] reports how
 * many the band (or its absence) rests on.
 */
fun references(
    profile: Profile,
    date: LocalDate,
    history: List<DaySummary>,
    windowDays: Int = REFERENCE_WINDOW_DAYS,
    minDays: Int = REFERENCE_MIN_DAYS,
): List<MetricReference> {
    val prior = history.filter {
        it.date < date && it.date >= date.minusDays(windowDays.toLong())
    }
    val today = history.find { it.date == date }
    return listOf(
        reference(
            MetricKind.HRV,
            band(prior.mapNotNull { it.heart.hrv.rmssdMs.value }, minDays),
            ReferenceRange(19.0, 75.0),
            "Typical adult short-term resting range (nightly sleep values often read higher)",
            "Nunan et al. 2010",
            true,
        ),
        reference(
            MetricKind.RESTING_HEART_RATE,
            band(prior.mapNotNull { it.heart.resting.bpm.value?.toDouble() }, minDays),
            ReferenceRange(60.0, 100.0),
            "Normal adult range (trained athletes often 40–60)",
            "American Heart Association",
            false,
        ),
        reference(
            MetricKind.RESPIRATORY_RATE,
            band(prior.mapNotNull { it.heart.respiratoryRate.breathsPerMinute.value }, minDays),
            ReferenceRange(12.0, 20.0),
            "Normal adult resting range",
            "Adult clinical reference range",
            null,
        ),
        run {
            val (ideal, label, source) = sleepDurationIdeal(profile.ageOn(date))
            reference(
                MetricKind.SLEEP_DURATION,
                band(prior.mapNotNull { it.sleep.value?.timeAsleep?.toMinutes()?.toDouble() }, minDays),
                ideal,
                label,
                source,
                null,
            )
        },
        reference(
            MetricKind.SLEEP_EFFICIENCY,
            band(prior.mapNotNull { it.sleep.value?.efficiency?.value?.toDouble() }, minDays),
            ReferenceRange(85.0, 100.0),
            "85% and above is considered normal",
            "Clinical sleep criterion",
            true,
        ),
        vo2MaxReference(profile.ageOn(date), profile.sex, prior, today?.vo2Max?.mlPerKgMin?.value, minDays),
        reference(
            MetricKind.STRAIN,
            band(prior.mapNotNull { it.strain.score.value }, minDays),
            null,
            null,
            null,
            null,
        ),
        reference(
            MetricKind.RECOVERY,
            band(prior.mapNotNull { it.recovery.score.value?.toDouble() }, minDays),
            null,
            null,
            null,
            true,
        ),
        reference(
            MetricKind.STRESS,
            band(prior.mapNotNull { it.stress.score.value }, minDays),
            null,
            null,
            null,
            false,
        ),
        reference(
            MetricKind.SKIN_TEMPERATURE,
            band(prior.mapNotNull { it.skinTemperature?.deviation?.value }, minDays),
            ReferenceRange(-1.0, 1.0),
            "Within ±1 SD of your own baseline",
            "Circlet nightly-deviation band",
            null,
        ),
    )
}

private data class Personal(val band: ReferenceRange?, val days: Int)

private fun band(values: List<Double>, minDays: Int): Personal {
    if (values.size < minDays) return Personal(null, values.size)
    val base = robustBaseline(values, minValid = minDays)
    val center = base.center
    val scale = base.scale
    // A flat history has no spread to centre a band on; that is not a band of zero width.
    if (center == null || scale == null || scale == 0.0) return Personal(null, values.size)
    return Personal(ReferenceRange(center - scale, center + scale), values.size)
}

private fun reference(
    kind: MetricKind,
    personal: Personal,
    ideal: ReferenceRange?,
    idealLabel: String?,
    idealSource: String?,
    higherIsBetter: Boolean?,
): MetricReference = MetricReference(
    kind = kind,
    personal = personal.band,
    personalDays = personal.days,
    ideal = ideal,
    idealLabel = idealLabel,
    idealSource = idealSource,
    higherIsBetter = higherIsBetter,
)

private data class SleepIdeal(val ideal: ReferenceRange, val label: String, val source: String)

private fun sleepDurationIdeal(age: Int?): SleepIdeal = when {
    age != null && age < 18 -> SleepIdeal(
        ReferenceRange(480.0, 600.0),
        "8–10 hours for teenagers",
        "Hirshkowitz et al. 2015 (NSF)",
    )
    age != null && age >= 65 -> SleepIdeal(
        ReferenceRange(420.0, 480.0),
        "7–8 hours for older adults",
        "Hirshkowitz et al. 2015 (NSF)",
    )
    else -> SleepIdeal(
        ReferenceRange(420.0, 540.0),
        "7–9 hours for adults",
        "Watson et al. 2015 (AASM/SRS consensus)",
    )
}

/** One Cooper age row: the fair (40th), good (60th), excellent (80th) and superior (95th) thresholds. */
private data class Vo2Row(val fair: Double, val good: Double, val excellent: Double, val superior: Double)

/** Cooper Institute fitness category thresholds by decade, male then female. */
private val VO2_ROWS: List<Triple<IntRange, Vo2Row, Vo2Row>> = listOf(
    Triple(20..29, Vo2Row(41.7, 45.4, 51.1, 55.4), Vo2Row(36.1, 39.5, 43.9, 49.6)),
    Triple(30..39, Vo2Row(40.5, 44.0, 48.3, 54.0), Vo2Row(34.4, 37.8, 42.4, 47.4)),
    Triple(40..49, Vo2Row(38.5, 42.4, 46.4, 52.5), Vo2Row(33.0, 36.3, 39.7, 45.3)),
    Triple(50..59, Vo2Row(35.6, 39.2, 43.4, 48.9), Vo2Row(30.1, 33.0, 36.7, 41.1)),
    Triple(60..69, Vo2Row(32.3, 35.5, 39.5, 45.7), Vo2Row(27.5, 30.0, 33.0, 37.8)),
    Triple(70..79, Vo2Row(29.4, 32.3, 36.7, 42.1), Vo2Row(25.9, 28.1, 30.9, 36.7)),
)

private fun vo2Category(value: Double, row: Vo2Row): String = when {
    value >= row.superior -> "Superior"
    value >= row.excellent -> "Excellent"
    value >= row.good -> "Good"
    value >= row.fair -> "Fair"
    else -> "Poor"
}

private fun vo2MaxReference(
    age: Int?,
    sex: Sex,
    prior: List<DaySummary>,
    todayValue: Double?,
    minDays: Int,
): MetricReference {
    val personal = band(prior.mapNotNull { it.vo2Max.mlPerKgMin.value }, minDays)
    if (sex != Sex.MALE && sex != Sex.FEMALE) {
        return reference(MetricKind.VO2_MAX, personal, null, null, null, true)
    }
    if (age == null || age < 20) {
        return reference(MetricKind.VO2_MAX, personal, null, null, null, true)
    }
    // Past the last published decade the closest band is shown and labelled as such.
    val found = VO2_ROWS.find { age in it.first }
    val match = found ?: VO2_ROWS.last()
    val closest = found == null
    val row = if (sex == Sex.MALE) match.second else match.third
    val bandLabel = "${match.first.first}–${match.first.last}" + if (closest) " (closest published band)" else ""
    val who = if (sex == Sex.MALE) "men" else "women"
    val category = todayValue?.let { " · You: ${vo2Category(it, row)}" } ?: ""
    return reference(
        MetricKind.VO2_MAX,
        personal,
        ReferenceRange(row.good, row.superior),
        "Good or better for $who $bandLabel$category",
        "Cooper Institute fitness norms",
        true,
    )
}
