// Ported from OpenStrap analytics/lib/src/onehz/sleep/circadian_np.dart.
// van Someren 1999 nonparametric circadian IS/IV/RA/L5/M10, phase anchored on the L5 trough.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.round6
import kotlin.math.max

data class CircadianNp(
    /** Interdaily stability, 0..1. */
    val interdailyStability: Double,
    /** Intradaily variability, floored at 0. */
    val intradailyVariability: Double,
    /** Mean of the most-active 10 h (scaled to the epoch grid). */
    val m10: Double,
    /** Mean of the least-active 5 h. Anchor phase on this trough, not on M10. */
    val l5: Double,
    /** (M10 − L5) / (M10 + L5), 0..1. */
    val relativeAmplitude: Double,
    val m10StartEpoch: Int,
    val l5StartEpoch: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "IS" to round6(interdailyStability),
        "IV" to round6(intradailyVariability),
        "M10" to round6(m10),
        "L5" to round6(l5),
        "RA" to round6(relativeAmplitude),
        "m10_start_epoch" to m10StartEpoch,
        "l5_start_epoch" to l5StartEpoch,
    )
}

private val circadianInputs = listOf("activity_or_hr_epochs")

/**
 * IS / IV / M10 / L5 / RA over an epoch-binned activity or HR series.
 *
 * [x] is consecutive days of exactly [epochsPerDay] epochs. Needs one full
 * day. [m10Epochs] / [l5Epochs] default to 10 h and 5 h on that grid
 * (`epochsPerDay * hours / 24`, truncating). [epochMin] is not a parameter:
 * epochs are already binned.
 */
fun circadianNonparametric(
    x: List<Double>,
    epochsPerDay: Int,
    m10Epochs: Int? = null,
    l5Epochs: Int? = null,
): OsMetric<CircadianNp> {
    val n = x.size
    if (epochsPerDay <= 0 || n < epochsPerDay) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = circadianInputs,
            note = "need ≥1 full day of epochs for circadian metrics",
        )
    }
    val mEp = m10Epochs ?: (epochsPerDay * 10 / 24)
    val lEp = l5Epochs ?: (epochsPerDay * 5 / 24)

    val grand = mean(x)!!
    var ssTot = 0.0
    for (v in x) {
        val d = v - grand
        ssTot += d * d
    }
    if (ssTot == 0.0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = circadianInputs,
            note = "no variance in activity series",
        )
    }

    val p = epochsPerDay
    val hourMeans = MutableList(p) { 0.0 }
    val hourCounts = MutableList(p) { 0 }
    for (i in 0 until n) {
        val h = i % p
        hourMeans[h] += x[i]
        hourCounts[h]++
    }
    for (h in 0 until p) {
        if (hourCounts[h] > 0) hourMeans[h] /= hourCounts[h]
    }
    var ssHour = 0.0
    for (h in 0 until p) {
        val d = hourMeans[h] - grand
        ssHour += d * d
    }
    val interdaily = (n * ssHour) / (p * ssTot)

    var ssDiff = 0.0
    for (i in 1 until n) {
        val d = x[i] - x[i - 1]
        ssDiff += d * d
    }
    val iv = (n.toDouble() * ssDiff) / ((n - 1).toDouble() * ssTot)

    val m10 = bestWindow(hourMeans, mEp, maximize = true)
    val l5 = bestWindow(hourMeans, lEp, maximize = false)
    val ra = if (m10.value + l5.value > 0.0) {
        (m10.value - l5.value) / (m10.value + l5.value)
    } else {
        0.0
    }

    val days = n.toDouble() / epochsPerDay
    val conf = (days / 7.0).coerceIn(0.3, 0.95)
    return OsMetric(
        value = CircadianNp(
            interdailyStability = interdaily.coerceIn(0.0, 1.0),
            intradailyVariability = max(0.0, iv),
            m10 = m10.value,
            l5 = l5.value,
            relativeAmplitude = ra.coerceIn(0.0, 1.0),
            m10StartEpoch = m10.start,
            l5StartEpoch = l5.start,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = circadianInputs,
        note = "IS/IV/RA/L5/M10 (van Someren 1999); circadian phase anchored on the " +
            "L5 nocturnal trough, not the daytime peak",
    )
}

private data class Window(val value: Double, val start: Int)

/** Best (max or min) mean over a contiguous circular window. Ties keep the earlier start. */
private fun bestWindow(profile: List<Double>, w: Int, maximize: Boolean): Window {
    val p = profile.size
    if (w >= p) return Window(mean(profile) ?: 0.0, 0)
    var best: Double? = null
    var bestStart = 0
    for (s in 0 until p) {
        var sum = 0.0
        for (k in 0 until w) {
            sum += profile[(s + k) % p]
        }
        val m = sum / w
        val current = best
        if (current == null || (if (maximize) m > current else m < current)) {
            best = m
            bestStart = s
        }
    }
    return Window(best ?: 0.0, bestStart)
}
