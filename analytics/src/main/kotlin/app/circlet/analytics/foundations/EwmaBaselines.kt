// Ported from OpenStrap analytics/lib/src/onehz/foundations/ewma_baselines.dart
// (itself from Baselines.swift / baselines.py).
// Winsorized exponentially-weighted personal baseline: recency-weighted centre,
// EWMA of absolute deviation, cold-start gating, and hard-outlier rejection.
// No usable night means no state. The engine does not invent a centre from the metric's bounds.

package app.circlet.analytics.foundations

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class MetricCfg(
    val minVal: Double,
    val maxVal: Double,
    /** Floor on absolute-deviation spread, not on σ. */
    val floorSpread: Double,
    val halfLifeB: Double,
    val halfLifeS: Double,
)

enum class BaselineStatus {
    CALIBRATING,
    PROVISIONAL,
    TRUSTED,
    STALE,
}

data class BaselineState(
    val baseline: Double,
    val spread: Double,
    val nValid: Int,
    val nightsSinceUpdate: Int,
    val status: BaselineStatus,
) {
    val trusted: Boolean get() = status == BaselineStatus.TRUSTED
    val usable: Boolean
        get() = status == BaselineStatus.PROVISIONAL || status == BaselineStatus.TRUSTED
}

data class Deviation(
    /** (value − baseline) / (1.253 × spread). */
    val z: Double,
    val delta: Double,
    val ratio: Double,
    val inNormalRange: Boolean,
)

/**
 * Winsorized-EWMA baseline engine.
 * Spread is an EWMA of absolute deviation from the baseline the night arrived at,
 * not from the baseline the night has already pulled. Measuring against the updated
 * centre shrinks every in-band deviation by (1 − λ) and inflates later z-scores.
 */
object Baselines {
    const val WINSOR_K = 3.0
    const val HARD_OUTLIER_K = 5.0
    const val MIN_NIGHTS_SEED = 4
    const val MIN_NIGHTS_TRUST = 14
    const val STALE_DAYS = 14
    const val EARLY_ADAPT_NIGHTS = 8
    const val EARLY_HALF_LIFE_B = 3.0
    const val EARLY_SPREAD_INFLATE = 2.5

    val hrvCfg = MetricCfg(
        minVal = 5.0,
        maxVal = 250.0,
        floorSpread = 5.0,
        halfLifeB = 14.0,
        halfLifeS = 21.0,
    )
    val restingHrCfg = MetricCfg(
        minVal = 30.0,
        maxVal = 120.0,
        floorSpread = 2.0,
        halfLifeB = 14.0,
        halfLifeS = 21.0,
    )
    val respCfg = MetricCfg(
        minVal = 4.0,
        maxVal = 40.0,
        floorSpread = 0.5,
        halfLifeB = 14.0,
        halfLifeS = 21.0,
    )
    val skinTempCfg = MetricCfg(
        minVal = 20.0,
        maxVal = 42.0,
        floorSpread = 0.3,
        halfLifeB = 14.0,
        halfLifeS = 21.0,
    )

    fun lambda(halfLife: Double): Double = 1.0 - 0.5.pow(1.0 / halfLife)

    fun computeStatus(nValid: Int, nightsSinceUpdate: Int): BaselineStatus {
        if (nightsSinceUpdate > STALE_DAYS && nValid >= MIN_NIGHTS_SEED) {
            return BaselineStatus.STALE
        }
        if (nValid < MIN_NIGHTS_SEED) return BaselineStatus.CALIBRATING
        if (nValid < MIN_NIGHTS_TRUST) return BaselineStatus.PROVISIONAL
        return BaselineStatus.TRUSTED
    }

    /**
     * Fold one night. A null or out-of-range value holds the previous state and
     * counts as a night the baseline did not move. The first unusable night returns null.
     */
    fun update(state: BaselineState?, value: Double?, cfg: MetricCfg): BaselineState? {
        val lb = lambda(cfg.halfLifeB)
        val ls = lambda(cfg.halfLifeS)

        if (state == null) {
            if (value != null && cfg.minVal <= value && value <= cfg.maxVal) {
                return BaselineState(
                    baseline = value,
                    spread = cfg.floorSpread,
                    nValid = 1,
                    nightsSinceUpdate = 0,
                    status = BaselineStatus.CALIBRATING,
                )
            }
            return null
        }

        if (value == null || !(cfg.minVal <= value && value <= cfg.maxVal)) {
            val m = state.nightsSinceUpdate + 1
            return state.copy(
                nightsSinceUpdate = m,
                status = computeStatus(state.nValid, m),
            )
        }

        val isYoung = state.nValid < EARLY_ADAPT_NIGHTS
        if (state.nValid >= MIN_NIGHTS_SEED && !isYoung) {
            val dev = abs(value - state.baseline)
            if (dev > HARD_OUTLIER_K * state.spread) {
                val m = state.nightsSinceUpdate + 1
                return state.copy(
                    nightsSinceUpdate = m,
                    status = computeStatus(state.nValid, m),
                )
            }
        }

        val effSpread = if (isYoung) state.spread * EARLY_SPREAD_INFLATE else state.spread
        val effLb = if (isYoung) lambda(EARLY_HALF_LIFE_B) else lb
        val lo = state.baseline - WINSOR_K * effSpread
        val hi = state.baseline + WINSOR_K * effSpread
        val clamped = max(lo, min(hi, value))
        val newBaseline = effLb * clamped + (1.0 - effLb) * state.baseline
        val absDev = abs(value - state.baseline)
        val newSpread = max(cfg.floorSpread, ls * absDev + (1.0 - ls) * state.spread)
        val newN = state.nValid + 1
        return BaselineState(
            baseline = newBaseline,
            spread = newSpread,
            nValid = newN,
            nightsSinceUpdate = 0,
            status = computeStatus(newN, 0),
        )
    }

    /** Replay oldest-first. Null when no night was usable. */
    fun foldHistory(values: List<Double?>, cfg: MetricCfg): BaselineState? {
        var state: BaselineState? = null
        for (v in values) {
            state = update(state, v, cfg)
        }
        return state
    }

    /**
     * z, delta, ratio, and whether |z| ≤ 1.
     * Null when the state rests on zero valid nights. A zero baseline yields ratio 0;
     * every shipped config has a positive minimum, so update cannot seed a zero centre.
     */
    fun deviation(value: Double, state: BaselineState): Deviation? {
        if (state.nValid == 0) return null
        val sigma = max(1.253 * state.spread, 1e-9)
        val z = (value - state.baseline) / sigma
        val delta = value - state.baseline
        val ratio = if (state.baseline != 0.0) value / state.baseline - 1.0 else 0.0
        return Deviation(
            z = z,
            delta = delta,
            ratio = ratio,
            inNormalRange = abs(z) <= 1.0,
        )
    }
}
