// Ported from OpenStrap analytics/lib/src/onehz/sleep/van_hees.dart.
// van Hees 2015/2018 (GGIR) z-angle sustained-inactivity rest window.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.foundations.sampleCadenceSeconds
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Per-second sleep/wake spine derived from the accel vector. */
class SleepWindow(
    val onsetIdx: Int,
    val offsetIdx: Int,
    val onsetMs: Double?,
    val offsetMs: Double?,
    /** True = asserted "no movement". False is movement or undecidable. */
    val immobile: List<Boolean>,
    val zAngleDeg: List<Double>,
    val sptSec: Int,
    val immobileUnknown: List<Boolean> = emptyList(),
    val cadenceSec: Double = 1.0,
) {
    /** Seconds whose immobility is genuinely undecidable. */
    val undecidableSec: Int
        get() {
            var c = 0
            for (u in immobileUnknown) {
                if (u) c++
            }
            return roundHalfAwayFromZero(c * cadenceSec).toInt()
        }

    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "onset_idx" to onsetIdx,
            "offset_idx" to offsetIdx,
        )
        if (onsetMs != null) m["onset_ms"] = onsetMs
        if (offsetMs != null) m["offset_ms"] = offsetMs
        m["spt_sec"] = sptSec
        val undecided = undecidableSec
        if (undecided > 0) m["undecidable_sec"] = undecided
        return m
    }
}

/** van Hees z-angle for one accel vector (degrees, arm elevation vs gravity). */
fun zAngle(x: Double, y: Double, z: Double): Double {
    val denom = sqrt(x * x + y * y)
    return atan2(z, denom) * 180.0 / PI
}

/** Per-second immobility evidence. No window selection. */
class ImmobilityMask(
    val immobile: List<Boolean>,
    val immobileUnknown: List<Boolean>,
    val zAngleDeg: List<Double>,
    val deltaDeg: List<Double>,
    val sustainedSec: Int,
    val thresholdDeg: Double,
    val cadenceSec: Double?,
    val sustainedSamples: Int,
)

/**
 * Coarsest cadence the van Hees rule is published at (seconds per sample).
 * A 5-second rolling median is the published epoch; slower sampling abstains.
 */
const val vanHeesMaxCadenceSec = 5.0

/** Immobility mask for an accel series. Short series yield an all-undecidable mask. */
fun immobilityMask(
    accel: List<AccelSample>,
    angleThresholdDeg: Double = 5.0,
    sustainedMin: Int = 5,
    smoothSec: Int = 5,
): ImmobilityMask {
    val n = accel.size
    val win = sustainedMin * 60
    if (n == 0) {
        return ImmobilityMask(
            immobile = emptyList(),
            immobileUnknown = emptyList(),
            zAngleDeg = emptyList(),
            deltaDeg = emptyList(),
            sustainedSec = win,
            thresholdDeg = angleThresholdDeg,
            cadenceSec = null,
            sustainedSamples = win,
        )
    }
    val cadence = sampleCadenceSeconds(List(n) { accel[it].tsMs / 1000.0 })
    if (cadence == null || cadence > vanHeesMaxCadenceSec) {
        return ImmobilityMask(
            immobile = List(n) { false },
            immobileUnknown = List(n) { true },
            zAngleDeg = List(n) { Double.NaN },
            deltaDeg = List(n) { Double.NaN },
            sustainedSec = win,
            thresholdDeg = angleThresholdDeg,
            cadenceSec = null,
            sustainedSamples = win,
        )
    }
    val winSamples = max(1, roundHalfAwayFromZero(win / cadence).toInt())
    val smoothSamples = max(1, roundHalfAwayFromZero(smoothSec / cadence).toInt())

    val raw = DoubleArray(n) { i ->
        if (accel[i].valid) zAngle(accel[i].x, accel[i].y, accel[i].z) else Double.NaN
    }
    val ang = rollingMedian(raw, smoothSamples)

    val dAng = DoubleArray(n)
    for (i in 1 until n) {
        dAng[i] = abs(ang[i] - ang[i - 1])
    }
    val immobile = ArrayList<Boolean>(n)
    val immobileUnknown = ArrayList<Boolean>(n)
    for (i in 0 until n) {
        val hi = min(n, i + winSamples)
        var maxd = 0.0
        var gap = !accel[i].valid
        var k = i + 1
        while (k < hi) {
            if (!accel[k].valid) {
                gap = true
                break
            }
            if (dAng[k] > maxd) {
                maxd = dAng[k]
                if (maxd >= angleThresholdDeg) break
            }
            k++
        }
        val still = maxd < angleThresholdDeg
        val fullWindow = hi - i >= winSamples
        immobile.add(still && fullWindow && !gap)
        immobileUnknown.add(still && (!fullWindow || gap))
    }
    return ImmobilityMask(
        immobile = immobile,
        immobileUnknown = immobileUnknown,
        zAngleDeg = ang.toList(),
        deltaDeg = dAng.toList(),
        sustainedSec = win,
        thresholdDeg = angleThresholdDeg,
        cadenceSec = cadence,
        sustainedSamples = winSamples,
    )
}

/**
 * Nocturnal sleep window: the single longest sustained-inactivity block.
 * Coarser than [vanHeesMaxCadenceSec] is absent.
 */
fun vanHeesSleepWindow(
    accel: List<AccelSample>,
    angleThresholdDeg: Double = 5.0,
    sustainedMin: Int = 5,
    bridgeGapMin: Int = 30,
    smoothSec: Int = 5,
): OsMetric<SleepWindow> {
    val inputs = listOf("accel_1hz")
    val n = accel.size
    val mask = immobilityMask(
        accel,
        angleThresholdDeg = angleThresholdDeg,
        sustainedMin = sustainedMin,
        smoothSec = smoothSec,
    )
    val cadence = mask.cadenceSec
    if (cadence == null) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "accel cadence not measurable, or coarser than the published " +
                "van Hees epoch (see vanHeesMaxCadenceSec) — the successive-angle " +
                "rule cannot bound movement inside a sampling gap",
        )
    }
    val win = mask.sustainedSamples
    if (n < win) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few accel samples for a sustained-inactivity block",
        )
    }
    val immobile = mask.immobile
    val bridge = max(1, roundHalfAwayFromZero((bridgeGapMin * 60) / cadence).toInt())
    var bestStart = -1
    var bestEnd = -1
    var bestLen = 0
    var i = 0
    while (i < n) {
        if (!immobile[i]) {
            i++
            continue
        }
        var j = i
        while (j < n) {
            if (immobile[j]) {
                j++
                continue
            }
            var k = j
            while (k < n && !immobile[k] && (k - j) < bridge) {
                k++
            }
            if (k < n && immobile[k] && (k - j) < bridge) {
                j = k
            } else {
                break
            }
        }
        val len = j - i
        if (len > bestLen) {
            bestLen = len
            bestStart = i
            bestEnd = j
        }
        i = j + 1
    }
    if (bestStart < 0 || bestLen < win) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no sustained-inactivity block ≥${sustainedMin}min found",
        )
    }
    val onsetSample = accel[bestStart]
    val offsetSample = accel[min(bestEnd, n - 1)]
    val onsetMs = if (onsetSample.tsMs != 0.0) onsetSample.tsMs else null
    val offsetMs = if (offsetSample.tsMs != 0.0) offsetSample.tsMs else null
    var unresolved = 0
    for (u in mask.immobileUnknown) {
        if (u) unresolved++
    }
    val sptSec = roundHalfAwayFromZero(bestLen * cadence).toInt()
    val undecidableSec = roundHalfAwayFromZero(unresolved * cadence).toInt()
    val conf = (sptSec / (7.0 * 3600.0)).coerceIn(0.3, 0.95)
    val tail = if (unresolved > 0) {
        "; ${undecidableSec}s are undecidable (forward window " +
            "truncated by the record end, or unmeasured gravity) and are " +
            "excluded"
    } else {
        ""
    }
    return OsMetric(
        value = SleepWindow(
            onsetIdx = bestStart,
            offsetIdx = bestEnd,
            onsetMs = onsetMs,
            offsetMs = offsetMs,
            immobile = immobile,
            immobileUnknown = mask.immobileUnknown,
            zAngleDeg = mask.zAngleDeg,
            sptSec = sptSec,
            cadenceSec = cadence,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "van Hees angle-based REST window (5°/${sustainedMin}min); " +
            "a rest period, not PSG sleep$tail",
    )
}

/** Centered rolling median. NaN in stays NaN at that index; neighbours are skipped. */
private fun rollingMedian(x: DoubleArray, w: Int): DoubleArray {
    val n = x.size
    if (w <= 1) return x.copyOf()
    val half = w / 2
    val out = DoubleArray(n) { Double.NaN }
    for (i in 0 until n) {
        if (x[i].isNaN()) continue
        val lo = max(0, i - half)
        val hi = min(n - 1, i + half)
        val seg = ArrayList<Double>()
        for (k in lo..hi) {
            if (!x[k].isNaN()) seg.add(x[k])
        }
        out[i] = median(seg)!!
    }
    return out
}
