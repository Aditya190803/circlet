// Ported from OpenStrap analytics/lib/src/onehz/motion/orientation.dart.
// Static wrist gravity-tilt as a coarse sleep-position proxy (supine/prone/lateral/upright).

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

private const val RAD2DEG = 180.0 / PI

/**
 * Wrist pitch/roll over a low-motion epoch. [position] is a body-position
 * proxy, not torso posture.
 */
data class Tilt(
    val pitchDeg: Double,
    val rollDeg: Double,
    val position: String,
    val nSamples: Int,
    val stillness: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "pitch_deg" to round6(pitchDeg),
        "roll_deg" to round6(rollDeg),
        "position" to position,
        "n" to nSamples,
        "stillness" to round6(stillness),
    )
}

/**
 * |pitch| > 60 → upright. Else |roll| < 45 supine, |roll| > 135 prone,
 * and the sign of roll picks lateral_right / lateral_left.
 */
fun classifyPosition(pitchDeg: Double, rollDeg: Double): String {
    if (abs(pitchDeg) > 60.0) return "upright"
    val r = abs(rollDeg)
    if (r < 45.0) return "supine"
    if (r > 135.0) return "prone"
    return if (rollDeg > 0.0) "lateral_right" else "lateral_left"
}

/**
 * Mean sample-to-sample rotation (degrees) allowed for an epoch to count as static.
 * 3° is the angular equivalent of the old 0.05 g magnitude gate (~2.87° on a 1 g vector).
 */
const val defaultMaxRotationDeg = 3.0

private val tiltInputs = listOf("accel_1hz")

/**
 * Static tilt over one low-motion epoch.
 *
 * Absent when the epoch is too short, the wrist is turning, or ‖a‖ is swinging.
 * Rotation is blind to an axial shake; magnitude is blind to rotation. Both
 * gates are required. Stillness is the worse of the two.
 */
fun staticTilt(
    epoch: List<AccelSample>,
    maxRotationDeg: Double = defaultMaxRotationDeg,
    maxJitterG: Double = 0.05,
    minSamples: Int = 3,
): OsMetric<Tilt> {
    val valid = epoch.filter { it.valid }
    if (valid.size < minSamples) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = tiltInputs,
            note = "epoch too short for a static-tilt estimate",
        )
    }
    val mags = ArrayList<Double>(valid.size)
    for (s in valid) mags.add(sqrt(s.x * s.x + s.y * s.y + s.z * s.z))
    var rotSum = 0.0
    var rotN = 0
    for (i in 1 until valid.size) {
        val a = valid[i - 1]
        val b = valid[i]
        val ma = mags[i - 1]
        val mb = mags[i]
        if (ma <= 0.0 || mb <= 0.0) continue
        val cosTheta = ((a.x * b.x + a.y * b.y + a.z * b.z) / (ma * mb)).coerceIn(-1.0, 1.0)
        rotSum += acos(cosTheta) * RAD2DEG
        rotN++
    }
    if (rotN == 0) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = tiltInputs,
            note = "accel vectors carry no direction (degenerate magnitude)",
        )
    }
    val rotDeg = rotSum / rotN
    var jitter = 0.0
    for (i in 1 until mags.size) jitter += abs(mags[i] - mags[i - 1])
    jitter = if (mags.size > 1) jitter / (mags.size - 1) else 0.0
    val stillness = min(1.0 - rotDeg / maxRotationDeg, 1.0 - jitter / maxJitterG).coerceIn(0.0, 1.0)
    if (rotDeg > maxRotationDeg) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = tiltInputs,
            note = "wrist rotating ${dartFixed(rotDeg, 2)}°/sample > " +
                "${maxRotationDeg}°; no single posture during motion",
        )
    }
    if (jitter > maxJitterG) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = tiltInputs,
            note = "epoch too dynamic (jitter ${dartFixed(jitter, 3)}g > " +
                "${maxJitterG}g); no static posture during motion",
        )
    }
    val mx = mean(valid.map { it.x })!!
    val my = mean(valid.map { it.y })!!
    val mz = mean(valid.map { it.z })!!
    val pitch = atan2(-mx, sqrt(my * my + mz * mz)) * RAD2DEG
    val roll = atan2(my, mz) * RAD2DEG
    val pos = classifyPosition(pitch, roll)
    val conf = (stillness * (valid.size / 30.0).coerceIn(0.3, 1.0)).coerceIn(0.0, 0.9)
    return OsMetric(
        value = Tilt(pitch, roll, pos, valid.size, stillness),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = tiltInputs,
        note = "wrist gravity-tilt; body-position PROXY (static, low-motion only)",
    )
}

/**
 * Fixed [epochSec] buckets by wall-clock `tsMs`. Epochs that are turning or
 * too short are skipped — no fabricated posture.
 */
fun positionSeries(
    samples: List<AccelSample>,
    epochSec: Int = 30,
    maxRotationDeg: Double = defaultMaxRotationDeg,
    maxJitterG: Double = 0.05,
): List<Tilt> {
    if (samples.isEmpty()) return emptyList()
    val buckets = HashMap<Int, MutableList<AccelSample>>()
    val win = epochSec * 1000.0
    for (s in samples) {
        if (!s.valid) continue
        val k = floor(s.tsMs / win).toInt()
        buckets.getOrPut(k) { ArrayList() }.add(s)
    }
    val out = ArrayList<Tilt>()
    for (k in buckets.keys.sorted()) {
        val m = staticTilt(buckets.getValue(k), maxRotationDeg = maxRotationDeg, maxJitterG = maxJitterG)
        if (m.present) out.add(m.value!!)
    }
    return out
}

/** Dart `toStringAsFixed`: half away from zero, exactly [digits] fraction digits. */
private fun dartFixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x == Double.POSITIVE_INFINITY) return "Infinity"
    if (x == Double.NEGATIVE_INFINITY) return "-Infinity"
    var scale = 1.0
    repeat(digits) { scale *= 10.0 }
    val neg = x < 0.0
    val scaled = roundHalfAwayFromZero(abs(x) * scale).toLong()
    val pow = scale.toLong()
    val whole = scaled / pow
    val frac = (scaled % pow).toString().padStart(digits, '0')
    val sign = if (neg && scaled != 0L) "-" else ""
    return "$sign$whole.$frac"
}
