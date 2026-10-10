package app.circlet.analytics.strain

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.calibrationFor
import app.circlet.analytics.support.round6
import app.circlet.analytics.support.unknownFamilyNote
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

// Ported from OpenStrap lib/src/onehz/workout/observed_max_hr.dart.
// The observed ceiling is the highest heart rate held for 15 continuous
// seconds while the wrist was moving. It is not a physiological HRmax.
// Motion gates are per device family (gen4 0.10 g, gen5 0.04 g of |‖a‖−1|);
// an unstamped family is a refusal.

internal data class HrCeiling(
    val bpm: Double,
    val tsMs: Double,
    val heldSeconds: Int,
    val motionG: Double,
)

internal data class AccelPoint(
    val tsMs: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean = true,
)

private val motionGateG = mapOf("gen4" to 0.10, "gen5" to 0.04)
private const val ABSURD_BPM = 240.0

internal fun sessionHrCeiling(
    hr: List<HrPoint>,
    accel: List<AccelPoint>,
    deviceFamily: String?,
    holdSeconds: Int = 15,
    maxGapSeconds: Double = 2.0,
): Scored<HrCeiling> {
    val gate = calibrationFor(motionGateG, deviceFamily)
        ?: return Scored.absent(unknownFamilyNote(deviceFamily))
    if (accel.size != hr.size) {
        return Scored.absent("hr and accel must be index-aligned samples of one session")
    }
    data class Row(val ts: Double, val hr: Double, val motion: Double)
    val rows = ArrayList<Row>()
    for (i in hr.indices) {
        val h = hr[i]
        val a = accel[i]
        if (!h.valid || !a.valid) continue
        if (h.hr >= ABSURD_BPM) continue
        val mag = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        rows.add(Row(h.tsMs, h.hr, abs(mag - 1.0)))
    }
    rows.sortBy { it.ts }
    val holdMs = holdSeconds * 1000.0
    val gapMs = maxGapSeconds * 1000.0
    val maxSpanMs = holdMs * 4
    val corrobMs = 3000.0
    var best: HrCeiling? = null
    for (i in rows.indices) {
        var lo = rows[i].hr
        var motionSum = 0.0
        var count = 0
        var trailStart = i
        var trailSum = 0.0
        var trailCount = 0
        var bestTrail = 0.0
        for (j in i until rows.size) {
            if (j > i && rows[j].ts - rows[j - 1].ts > gapMs) break
            lo = min(lo, rows[j].hr)
            motionSum += rows[j].motion
            count++
            trailSum += rows[j].motion
            trailCount++
            while (rows[j].ts - rows[trailStart].ts > corrobMs) {
                trailSum -= rows[trailStart].motion
                trailCount--
                trailStart++
            }
            val trailAvg = trailSum / trailCount
            if (rows[j].ts - rows[i].ts >= corrobMs && trailAvg > bestTrail) bestTrail = trailAvg
            val span = rows[j].ts - rows[i].ts
            if (span < holdMs) continue
            if (span > maxSpanMs) break
            if (bestTrail >= gate) {
                if (best == null || lo > best.bpm) {
                    best = HrCeiling(lo, rows[i].ts, (span / 1000).let { kotlin.math.round(it).toInt() }, motionSum / count)
                }
                break
            }
        }
    }
    val found = best ?: return Scored.absent("no_held_ceiling:hold=${holdSeconds}s,motion_gate_g=$gate")
    val conf = (found.heldSeconds / (holdSeconds * 2.0)).coerceIn(0.3, 0.8)
    return Scored.of(
        found,
        conf,
        "OBSERVED ceiling, not physiological HRmax. motion_g=${round6(found.motionG)}",
    )
}
