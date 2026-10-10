package app.circlet.analytics.strain

import app.circlet.analytics.support.Scored
// Ported from OpenStrap lib/src/onehz/motion/energy_fusion.dart.
// Brage et al. 2004 branched equation: heart rate and accelerometry are
// weighted by which regime the body is in. The output is a relative
// energy-expenditure index, not kilocalories, unless resting and max heart
// rate are supplied (then the heart-rate branch is percent of reserve, still
// an estimate).

internal data class EnergyPoint(
    val tsMs: Double,
    val index: Double,
    val accelComponent: Double,
    val hrComponent: Double,
    val accelWeight: Double,
    val branch: String,
)

internal data class EnergyFusionResult(
    val points: List<EnergyPoint>,
    val relativeLoad: Double,
    val calibrated: Boolean,
)

internal fun branchedEnergyFusion(
    tsMs: List<Double>,
    enmo: List<Double>,
    hr: List<Double>,
    restingHr: Double? = null,
    maxHr: Double? = null,
    accelSatG: Double = 0.5,
    accelHi: Double = 0.15,
    hrHi: Double = 0.4,
): Scored<EnergyFusionResult> {
    val n = tsMs.size
    if (n == 0 || enmo.size != n || hr.size != n) {
        return Scored.absent("HR/ENMO/ts must be equal-length and time-aligned")
    }
    val calibrated = restingHr != null && maxHr != null && maxHr > restingHr
    val pts = ArrayList<EnergyPoint>(n)
    var load = 0.0
    var usable = 0
    for (i in 0 until n) {
        val a = normAccel(enmo[i], accelSatG)
        val hValid = hr[i] > 0
        if (hValid) usable++
        val h = normHr(hr[i], restingHr, maxHr)
        val highA = a >= accelHi
        val highH = hValid && h >= hrHi
        val branch: String
        val wA: Double
        when {
            !hValid -> {
                branch = "accel_only"
                wA = 1.0
            }
            !highA && !highH -> {
                branch = "rest"
                wA = 0.85
            }
            !highA && highH -> {
                branch = "nonlocomotor"
                wA = 0.15
            }
            highA && !highH -> {
                branch = "transient"
                wA = 0.7
            }
            else -> {
                branch = "locomotor"
                wA = 0.4
            }
        }
        var idx = wA * a + (1 - wA) * (if (hValid) h else 0.0)
        if (branch == "transient") idx *= 0.7
        idx = idx.coerceIn(0.0, 1.0)
        load += idx
        pts.add(EnergyPoint(tsMs[i], idx, a, if (hValid) h else 0.0, wA, branch))
    }
    val cov = usable.toDouble() / n
    val conf = (cov * (if (calibrated) 0.7 else 0.5)).coerceIn(0.0, 0.7)
    val note = if (calibrated) {
        "Brage branched fusion w/ %HR-reserve; ESTIMATE EE, not kcal/METs"
    } else {
        "Brage branched fusion; RELATIVE EE index (no per-user HR calib)"
    }
    return Scored.of(EnergyFusionResult(pts, load, calibrated), conf, note)
}

private fun normAccel(enmo: Double, satG: Double): Double {
    if (enmo <= 0) return 0.0
    return (enmo / satG).coerceAtMost(1.0)
}

private fun normHr(hr: Double, restingHr: Double?, maxHr: Double?): Double {
    if (hr <= 0) return 0.0
    if (restingHr != null && maxHr != null && maxHr > restingHr) {
        return ((hr - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0)
    }
    return ((hr - 50.0) / (180.0 - 50.0)).coerceIn(0.0, 1.0)
}
