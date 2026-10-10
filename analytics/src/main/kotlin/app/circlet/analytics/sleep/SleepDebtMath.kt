// Ported from OpenStrap analytics/lib/src/onehz/human/sleep_regularity.dart.
// The p75 unconstrained-night gap, not Kitamura OSD.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.percentile
import app.circlet.analytics.foundations.round6

/**
 * p75 of unconstrained nights minus habitual sleep.
 * Not Kitamura optimal sleep duration: an ad-lib percentile understates that by ~1 h.
 * Named so it does not clash with core SleepDebt.
 */
data class OsSleepDebt(
    /** p75 of unconstrained nights (h), or null when no free night exists. */
    val osdHours: Double?,
    val habitualHours: Double,
    /** [osdHours] − [habitualHours], or null when there is nothing to compare. */
    val debtHours: Double?,
    val hasFreeNight: Boolean,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (osdHours != null) m["osd_hours"] = round6(osdHours)
        m["habitual_hours"] = round6(habitualHours)
        if (debtHours != null) m["debt_hours"] = round6(debtHours)
        m["has_free_night"] = hasFreeNight
        return m
    }
}

private val debtInputs = listOf("sleep_duration_recent", "sleep_duration_free")

/**
 * Longer unconstrained nights versus the recent habitual night.
 *
 * [recentSleepH] is oldest→newest, in hours. [freeNightSleepH] is sleep on
 * nights with no alarm. No free night reports habitual sleep and declines to
 * compare — it does not invent an 8 h need.
 */
fun sleepDebt(
    recentSleepH: List<Double>,
    freeNightSleepH: List<Double>,
    minRecent: Int = 3,
): OsMetric<OsSleepDebt> {
    if (recentSleepH.size < minRecent) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = debtInputs,
            note = "need more recent nights to estimate habitual sleep",
        )
    }
    val habitual = median(recentSleepH)!!
    if (freeNightSleepH.isEmpty()) {
        return OsMetric(
            value = OsSleepDebt(null, habitual, null, false),
            confidence = 0.3,
            tier = Tier.HIGH,
            inputsUsed = debtInputs,
            note = "no free night yet — cannot honestly estimate your sleep need",
        )
    }
    val osd = percentile(freeNightSleepH, 75.0)!!
    val debt = osd - habitual
    return OsMetric(
        value = OsSleepDebt(osd, habitual, debt, true),
        confidence = (freeNightSleepH.size / 5.0).coerceIn(0.3, 0.85),
        tier = Tier.HIGH,
        inputsUsed = debtInputs,
        note = "p75 of your unconstrained nights vs your habitual night — a " +
            "percentile of ad-lib sleep, NOT a measured sleep need (that needs a " +
            "run of consecutive free days and a saturating fit)",
    )
}
