// Ported from OpenStrap analytics/lib/src/onehz/clinical/readiness_lnrmssd.dart.
// lnRMSSD readiness stack (Plews 2013/2014; Kiviniemi 2007): rolling mean, CV, SWC band, and z-score.

package app.circlet.analytics.recovery

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.calendarDays
import app.circlet.analytics.foundations.mad
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.needBaselineNote
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.stddev
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlin.math.abs

data class ReadinessLnRmssd(
    val lnRmssdToday: Double,
    val rolling7Mean: Double,
    /** Window median. z, SWC, and the band are taken against this, not the mean. */
    val rolling7Median: Double,
    val cvPct: Double,
    val z: Double?,
    val swc: Double?,
    val band: String,
    val saturationFlag: Boolean,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "ln_rmssd_today" to round6(lnRmssdToday),
            "rolling7_mean" to round6(rolling7Mean),
            "rolling7_median" to round6(rolling7Median),
            "cv_pct" to round6(cvPct),
        )
        if (z != null) m["z"] = round6(z)
        if (swc != null) m["swc"] = round6(swc)
        m["band"] = band
        m["saturation_flag"] = saturationFlag
        return m
    }
}

/** Nights of lnRMSSD history required before this metric computes. */
const val readinessLnRmssdMinNights = 4

private val lnRmssdInputs = listOf("ln_rmssd_history")

/**
 * Trailing nightly ln(RMSSD), oldest to newest, including tonight as the last element.
 * [dates] are `yyyy-MM-dd` labels parallel to [historyLnRmssd]. Without them the
 * window is the last [windowDays] rows, which after a wear gap are nights from weeks ago.
 * [meanNnTodayMs] is tonight's mean NN for the saturation guard.
 */
fun readinessLnRmssd(
    historyLnRmssd: List<Double>,
    dates: List<String>? = null,
    meanNnTodayMs: Double? = null,
    windowDays: Int = 7,
    minNights: Int = readinessLnRmssdMinNights,
): OsMetric<ReadinessLnRmssd> {
    if (historyLnRmssd.size < minNights) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = lnRmssdInputs,
            note = needBaselineNote(historyLnRmssd.size, minNights),
        )
    }
    val today = historyLnRmssd.last()
    val n = historyLnRmssd.size
    val start = if (n - 1 - windowDays < 0) 0 else n - 1 - windowDays
    // Prior nights only. Tonight in its own baseline pulls the centre toward it.
    // One unparseable label drops the whole series back to rows: an index mixed
    // with epoch days is not a window.
    val priorWindow: List<Double> = if (
        dates != null &&
        dates.size == n &&
        dates.all { openStrapDateParses(it) }
    ) {
        val day = calendarDays(dates)
        val todayDay = day[n - 1]
        buildList {
            for (i in 0 until n - 1) {
                if (day[i] < todayDay && todayDay - day[i] <= windowDays) {
                    add(historyLnRmssd[i])
                }
            }
        }
    } else {
        historyLnRmssd.slice(start until n - 1)
    }
    if (priorWindow.isEmpty() || priorWindow.size < minNights - 1) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = lnRmssdInputs,
            note = needBaselineNote(
                priorWindow.size + 1,
                if (minNights < 2) 2 else minNights,
            ),
        )
    }
    val m = mean(priorWindow)!!
    val sd = stddev(priorWindow)
    if (sd == null || m == 0.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = lnRmssdInputs,
            note = "lnRMSSD baseline dispersion undefined (needs ≥2 prior nights) — " +
                "CV/z/SWC/band are undefined",
        )
    }
    val cv = abs(sd / m) * 100.0
    // Median + scaled MAD. One illness night drags the mean and inflates the SD,
    // so a genuinely low night reads "normal". CV above stays SD/mean (Plews).
    val centre = median(priorWindow)!!
    val robustSd = mad(priorWindow)!!
    val spread = if (robustSd > 0.0) robustSd else sd
    val z = if (spread > 0.0) (today - centre) / spread else null
    val swc = 0.5 * spread
    val band = when {
        today < centre - swc -> "suppressed"
        today > centre + swc -> "elevated"
        else -> "normal"
    }
    val saturation = meanNnTodayMs != null && meanNnTodayMs > 1100.0 && z != null && z > 1.0
    val conf = (priorWindow.size / windowDays.toDouble()).coerceIn(0.3, 0.9)
    return OsMetric(
        value = ReadinessLnRmssd(
            lnRmssdToday = today,
            rolling7Mean = m,
            rolling7Median = centre,
            cvPct = cv,
            z = z,
            swc = swc,
            band = band,
            saturationFlag = saturation,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = lnRmssdInputs,
        note = if (saturation) {
            "lnRMSSD:RR saturation — high-HRV interpretation caution"
        } else {
            "Plews/Kiviniemi whole-night lnRMSSD readiness; PRV"
        },
    )
}

/** Dart `DateTime.tryParse`: a label the calendar window is allowed to trust. */
private fun openStrapDateParses(label: String): Boolean {
    if (label.isEmpty()) return false
    if (runCatching { LocalDate.parse(label) }.isSuccess) return true
    val iso = label.replace(' ', 'T')
    if (runCatching { OffsetDateTime.parse(iso) }.isSuccess) return true
    return runCatching { LocalDateTime.parse(iso) }.isSuccess
}
