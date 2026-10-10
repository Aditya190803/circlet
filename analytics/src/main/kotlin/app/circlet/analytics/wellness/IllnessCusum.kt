package app.circlet.analytics.wellness

import app.circlet.analytics.support.calendarDays
import app.circlet.analytics.support.dispersionBelowQuantum
import app.circlet.analytics.support.mad
import app.circlet.analytics.support.median
import app.circlet.analytics.support.needBaselineNote
import app.circlet.analytics.support.stddev
import kotlin.math.max

// Ported from OpenStrap lib/src/onehz/clinical/illness_cusum.dart.
// One-sided Page CUSUM (Biometrika 1954) on the standardized nightly resting
// heart rate, with a recovery clause that clears the accumulator after two
// nights back inside the band. This is not NightSignal.

internal enum class IllnessState { GREEN, YELLOW, RED }

internal const val ILLNESS_CUSUM_MIN_BASELINE = 7
internal const val DEGENERATE_BASELINE_NOTE = "degenerate_baseline:scale=0"
internal const val BELOW_QUANTUM_NOTE = "baseline_dispersion_below_quantum"

internal data class IllnessDay(
    val date: String,
    val state: IllnessState,
    val cusum: Double?,
    val z: Double?,
    val need: String? = null,
)

internal fun illnessCusum(
    dates: List<String>,
    rhr: List<Double?>,
    baselineDays: Int = 28,
    k: Double = 0.5,
    h: Double = 4.0,
    persistDays: Int = 2,
    minBaseline: Int = ILLNESS_CUSUM_MIN_BASELINE,
    returnZ: Double = 0.5,
    recoverDays: Int = 2,
): List<IllnessDay> {
    val day = calendarDays(dates)
    val out = ArrayList<IllnessDay>(rhr.size)
    var cusum = 0.0
    var yellowRun = 0
    var normalRun = 0
    var lastScored = Int.MIN_VALUE / 4
    for (i in rhr.indices) {
        val r = rhr[i]
        val window = ArrayList<Double>()
        var j = i - 1
        while (j >= 0 && day[i] - day[j] <= baselineDays) {
            rhr[j]?.let { window.add(it) }
            j--
        }
        if (r == null || window.size < minBaseline) {
            val need = if (r != null) needBaselineNote(window.size, minBaseline) else null
            out.add(IllnessDay(dates[i], IllnessState.GREEN, null, null, need))
            continue
        }
        val med = median(window)!!
        var scale = mad(window) ?: 0.0
        if (scale <= 0) scale = stddev(window) ?: 0.0
        if (scale <= 0 || !scale.isFinite()) {
            out.add(IllnessDay(dates[i], IllnessState.GREEN, null, null, DEGENERATE_BASELINE_NOTE))
            continue
        }
        if (dispersionBelowQuantum(window, 1.0)) {
            out.add(IllnessDay(dates[i], IllnessState.GREEN, null, null, BELOW_QUANTUM_NOTE))
            continue
        }
        if (day[i] - lastScored > 1) {
            yellowRun = 0
            normalRun = 0
            cusum = 0.0
        }
        lastScored = day[i]
        val z = (r - med) / scale
        cusum = max(0.0, cusum + (z - k))
        if (z < returnZ) {
            normalRun++
            if (normalRun >= recoverDays) cusum = 0.0
        } else {
            normalRun = 0
        }
        val state = if (cusum > h) {
            yellowRun++
            if (yellowRun >= persistDays) IllnessState.RED else IllnessState.YELLOW
        } else {
            yellowRun = 0
            IllnessState.GREEN
        }
        out.add(IllnessDay(dates[i], state, cusum, z))
    }
    return out
}
