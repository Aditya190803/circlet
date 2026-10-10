package app.circlet.analytics.fitness

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.round6
import app.circlet.core.AbsentReason
import app.circlet.core.Metric
import app.circlet.core.Vo2Max
import kotlin.math.abs
import kotlin.math.roundToInt

// Ported from OpenStrap lib/src/onehz/clinical/vo2max.dart.
// Submaximal VO2max: ACSM speed equations plus Swain & Leutholtz 1997
// (%HRR equivalent to %VO2 reserve). A 1 Hz sample has no speed, so a day
// with no recorded bout abstains.

internal const val VO2MAX_MIN_BOUT_SEC = 300
internal const val VO2MAX_MIN_HRR = 0.40
internal const val VO2MAX_MAX_HRR = 0.90
internal const val VO2MAX_RUN_THRESHOLD_MPS = 2.0

internal fun vo2maxSubmaxEstimate(
    speedMps: Double,
    avgHrBpm: Double,
    boutDurationSec: Int,
    restingHrBpm: Double,
    hrMaxBpm: Double,
    gradePercent: Double? = null,
): Scored<Double> {
    if (boutDurationSec < VO2MAX_MIN_BOUT_SEC) {
        return Scored.absent("bout too short for steady-state HR (${boutDurationSec}s < ${VO2MAX_MIN_BOUT_SEC}s)")
    }
    if (speedMps <= 0 || !speedMps.isFinite()) return Scored.absent("no steady-state pace for this bout")
    val hrReserve = hrMaxBpm - restingHrBpm
    if (hrReserve < 20) return Scored.absent("hr_max too close to resting_hr — unreliable %HRR")
    val hrr = (avgHrBpm - restingHrBpm) / hrReserve
    if (!hrr.isFinite() || hrr < VO2MAX_MIN_HRR || hrr > VO2MAX_MAX_HRR) {
        return Scored.absent(
            "bout intensity outside the submaximal %HRR band " +
                "(${(hrr * 100).roundToInt()}%, need " +
                "${(VO2MAX_MIN_HRR * 100).roundToInt()}-${(VO2MAX_MAX_HRR * 100).roundToInt()}%)",
        )
    }
    val speedMMin = speedMps * 60.0
    val grade = (gradePercent ?: 0.0) / 100.0
    val vo2Submax = if (speedMps >= VO2MAX_RUN_THRESHOLD_MPS) {
        0.2 * speedMMin + 0.9 * speedMMin * grade + 3.5
    } else {
        0.1 * speedMMin + 1.8 * speedMMin * grade + 3.5
    }
    val vo2max = 3.5 + (vo2Submax - 3.5) / hrr
    if (!vo2max.isFinite() || vo2max <= 3.5 || vo2max > 95) {
        return Scored.absent("extrapolated VO2max outside a plausible human range")
    }
    val bandCenter = (VO2MAX_MIN_HRR + VO2MAX_MAX_HRR) / 2
    val bandHalf = (VO2MAX_MAX_HRR - VO2MAX_MIN_HRR) / 2
    val centerDist = abs(hrr - bandCenter) / bandHalf
    val conf = (0.6 - 0.25 * centerDist).coerceIn(0.25, 0.6)
    return Scored.of(round6(vo2max)!!, conf, "submax VO2max via ACSM speed->VO2 + Swain %HRR~%VO2R")
}

fun vo2Max(speedMps: Double?, avgHrBpm: Double?, boutDurationSec: Int?, restingHrBpm: Double?, hrMaxBpm: Double?, gradePercent: Double? = null): Vo2Max {
    if (speedMps == null || avgHrBpm == null || boutDurationSec == null || restingHrBpm == null || hrMaxBpm == null) {
        return Vo2Max(Metric.absent(AbsentReason.NOT_ENOUGH_DATA))
    }
    val scored = vo2maxSubmaxEstimate(speedMps, avgHrBpm, boutDurationSec, restingHrBpm, hrMaxBpm, gradePercent)
    return Vo2Max(scored.toCore())
}
