package app.circlet.analytics.strain

import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.finite
import app.circlet.analytics.support.mean
import app.circlet.analytics.support.median
import app.circlet.analytics.support.needBaselineNote
import app.circlet.analytics.support.sampleCadenceSeconds
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

// Ported from OpenStrap lib/src/onehz/clinical/load_trimp.dart.
// Banister exponential TRIMP (Banister 1991; Morton 1990):
//   TRIMP = Σ Δt(min) · ΔHRr · y,  y = c·e^(b·ΔHRr)
//   men c=0.64 b=1.92, women c=0.86 b=1.67.
// Headline strain is that TRIMP above the user's own quiet-waking heart-rate
// reserve, log-mapped onto 0–21. CTL/ATL/TSB is the Banister impulse-response model.

internal enum class BanisterSex { MALE, FEMALE }

/** Population quiet-waking HRR the anchor table was generated at. Not a user default. */
internal const val QUIET_WAKING_HRR = 0.20

/** ACSM moderate-intensity floor. A quiet level above this is not quiet waking. */
internal const val MAX_QUIET_HRR = 0.40

internal const val EXERCISE_FLOOR_HRR = MAX_QUIET_HRR

internal const val MAXIMAL_NET_TRIMP = 400.0

internal const val STRAIN_CURVATURE = 15.0

internal const val QUIET_HRR_MIN_DAYS = 3

internal const val QUIET_HRR_SETTLED_DAYS = 7

internal const val QUIET_HRR_WINDOW_DAYS = 28

internal const val QUIET_HRR_TRAIT_MIN_MINUTES = 360

internal const val CTL_ATL_MIN_DAYS = 14

internal data class QuietLevel(val hrr: Double, val days: Int) {
    val settled: Boolean get() = days >= QUIET_HRR_SETTLED_DAYS
}

internal data class NetTrimp(val exercise: Double, val living: Double, val minutes: Int) {
    val net: Double get() = exercise + max(0.0, living)
}

internal data class LoadState(val ctl: Double, val atl: Double, val tsb: Double)

internal object StrainScorer {
    const val MIN_READINGS = 600
    const val MIN_SPARSE_READINGS = 20
    const val MIN_SPAN_SECONDS = 600
    const val MAX_STRAIN = 100.0
    const val STRAIN_DENOMINATOR = 7201.0
    const val FALLBACK_SAMPLE_MIN = 1.0 / 60.0
    const val HRMAX_MIN_SAMPLES = 600
    const val HRMAX_PERCENTILE = 99.5
    const val BANISTER_SCALE_MEN = 0.64
    const val BANISTER_SCALE_WOMEN = 0.86
    const val BANISTER_B_MEN = 1.92
    const val BANISTER_B_WOMEN = 1.67

    fun banisterY(x: Double, female: Boolean): Double {
        val scale = if (female) BANISTER_SCALE_WOMEN else BANISTER_SCALE_MEN
        val b = if (female) BANISTER_B_WOMEN else BANISTER_B_MEN
        return scale * exp(b * x)
    }

    fun tanakaHrMax(age: Double): Double = 208.0 - 0.7 * age

    fun pctHrr(bpm: Double, restingHr: Double, hrReserve: Double): Double {
        val pct = (bpm - restingHr) / hrReserve * 100.0
        return pct.coerceIn(0.0, 100.0)
    }

    fun sampleDurationsMinutes(tsSec: List<Double>): List<Double> {
        val n = tsSec.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(FALLBACK_SAMPLE_MIN)
        val capSec = sampleCadenceSeconds(tsSec) ?: return emptyList()
        val out = DoubleArray(n) { capSec / 60.0 }
        for (i in 0 until n - 1) {
            val g = tsSec[i + 1] - tsSec[i]
            out[i] = (if (g > 0) min(g, capSec) else capSec) / 60.0
        }
        return out.toList()
    }

    fun banisterTrimp(
        bpm: List<Double>,
        restingHr: Double,
        hrReserve: Double,
        durationsMin: List<Double>,
        female: Boolean = false,
    ): Double {
        var acc = 0.0
        for (i in bpm.indices) {
            val dur = when {
                i < durationsMin.size -> durationsMin[i]
                durationsMin.isEmpty() -> FALLBACK_SAMPLE_MIN
                else -> durationsMin.last()
            }
            val x = pctHrr(bpm[i], restingHr, hrReserve) / 100.0
            if (x > 0) acc += dur * x * banisterY(x, female)
        }
        return acc
    }

    fun trimpToStrain(trimp: Double, denominator: Double = STRAIN_DENOMINATOR): Double {
        if (trimp <= 0) return 0.0
        val value = MAX_STRAIN * ln(trimp + 1.0) / ln(denominator)
        val clamped = value.coerceIn(0.0, MAX_STRAIN)
        return round(clamped * 100.0) / 100.0
    }

    fun strain(
        bpm: List<Double>,
        tsSec: List<Double>,
        maxHr: Double?,
        restingHr: Double,
        female: Boolean = false,
        denominator: Double = STRAIN_DENOMINATOR,
    ): Double? {
        if (maxHr == null) return null
        val enough = when {
            bpm.size >= MIN_READINGS -> true
            bpm.size >= MIN_SPARSE_READINGS && tsSec.isNotEmpty() -> {
                var mn = tsSec[0]
                var mx = tsSec[0]
                for (t in tsSec) {
                    if (t < mn) mn = t
                    if (t > mx) mx = t
                }
                (mx - mn) >= MIN_SPAN_SECONDS
            }
            else -> false
        }
        if (!enough || maxHr <= restingHr) return null
        val durations = sampleDurationsMinutes(tsSec)
        if (durations.isEmpty()) return null
        val trimp = banisterTrimp(bpm, restingHr, maxHr - restingHr, durations, female)
        return trimpToStrain(trimp, denominator)
    }
}

internal fun banisterTrimp(
    hrPerMin: List<Double>,
    restingHr: Double?,
    maxHr: Double?,
    sex: BanisterSex,
): Scored<Double> {
    val noteMissing = "Banister TRIMP needs finite measured RHR and HRmax (HRmax>RHR)"
    if (!finite(restingHr) || !finite(maxHr) || maxHr!! <= restingHr!! || hrPerMin.isEmpty()) {
        return Scored.absent(noteMissing)
    }
    val reserve = maxHr - restingHr
    var trimp = 0.0
    val female = sex == BanisterSex.FEMALE
    for (hr in hrPerMin) {
        if (!hr.isFinite() || hr <= 0) continue
        var hrr = (hr - restingHr) / reserve
        if (hrr < 0) hrr = 0.0
        if (hrr > 1) hrr = 1.0
        trimp += hrr * StrainScorer.banisterY(hrr, female)
    }
    return Scored.of(trimp, 0.6, "Banister exponential TRIMP (wrist HR estimate)")
}

internal fun dailyQuietWakingHrr(
    hrPerMin: List<Double>,
    restingHr: Double?,
    maxHr: Double?,
    minMinutes: Int = 60,
): Double? {
    if (!finite(restingHr) || !finite(maxHr) || maxHr!! <= restingHr!!) return null
    val reserve = maxHr - restingHr
    val hrr = ArrayList<Double>()
    for (hr in hrPerMin) {
        if (hr.isFinite() && hr > 0) {
            hrr.add(((hr - restingHr) / reserve).coerceIn(0.0, 1.0))
        }
    }
    if (hrr.size < minMinutes) return null
    val q = median(hrr) ?: return null
    if (q <= 0 || q > MAX_QUIET_HRR) return null
    return q
}

internal fun baselineTrimp(wakeMinutes: Double, quietHrr: Double, female: Boolean = false): Double {
    val q = quietHrr.coerceIn(0.0, MAX_QUIET_HRR)
    return wakeMinutes * q * StrainScorer.banisterY(q, female)
}

internal fun strainFromNetTrimp(net: Double): Double {
    if (!net.isFinite() || net <= 0) return 0.0
    val u = min(1.0, net / MAXIMAL_NET_TRIMP)
    val s = 21.0 * ln(1 + u * (STRAIN_CURVATURE - 1)) / ln(STRAIN_CURVATURE)
    return s.coerceIn(0.0, 21.0)
}

internal fun strainScore(
    trimp: Double,
    wakeMinutes: Double,
    quietHrr: Double,
    female: Boolean = false,
): Double = strainFromNetTrimp(trimp - baselineTrimp(wakeMinutes, quietHrr, female))

internal fun strainScoreMetric(
    trimp: Double?,
    wakeMinutes: Double?,
    quietHrr: Double?,
    quietSettled: Boolean,
    female: Boolean = false,
): Scored<Double> {
    if (trimp == null || !trimp.isFinite() || trimp < 0) {
        return Scored.absent("strain needs a finite TRIMP >= 0")
    }
    if (wakeMinutes == null || !wakeMinutes.isFinite() || wakeMinutes <= 0) {
        return Scored.absent("strain needs a finite wake window (minutes > 0)")
    }
    if (quietHrr == null || !quietHrr.isFinite() || quietHrr <= 0) {
        return Scored.absent("strain needs this user's own quiet-waking HRR")
    }
    if (quietHrr > MAX_QUIET_HRR) {
        return Scored.absent("quiet-waking HRR above $MAX_QUIET_HRR is not quiet waking")
    }
    val note = if (quietSettled) {
        "headline 0–21 strain = log map of TRIMP earned above the quiet-waking baseline"
    } else {
        "calibrating: quiet-waking level from fewer than $QUIET_HRR_SETTLED_DAYS days"
    }
    return Scored.of(
        strainScore(trimp, wakeMinutes, quietHrr, female),
        if (quietSettled) 0.6 else 0.45,
        note,
    )
}

private class NetAccumulator(
    restingHr: Double,
    maxHr: Double,
    quietHrr: Double,
    sex: BanisterSex,
) {
    private val resting = restingHr
    private val reserve = maxHr - restingHr
    private val female = sex == BanisterSex.FEMALE
    private val quietRate = quietHrr * StrainScorer.banisterY(quietHrr, female)
    var exercise = 0.0
        private set
    var living = 0.0
        private set
    var minutes = 0
        private set

    fun add(hr: Double): Boolean {
        if (!hr.isFinite() || hr <= 0) return false
        val x = ((hr - resting) / reserve).coerceIn(0.0, 1.0)
        val e = x * StrainScorer.banisterY(x, female) - quietRate
        if (x >= EXERCISE_FLOOR_HRR) exercise += e else living += e
        minutes++
        return true
    }

    fun result(): NetTrimp = NetTrimp(exercise, living, minutes)
}

private fun netAnchorsValid(restingHr: Double?, maxHr: Double?, quietHrr: Double?): Boolean =
    finite(restingHr) && finite(maxHr) && finite(quietHrr) &&
        maxHr!! > restingHr!! && quietHrr!! > 0 && quietHrr <= MAX_QUIET_HRR

internal fun netTrimpAboveQuiet(
    hrPerMin: List<Double>,
    restingHr: Double?,
    maxHr: Double?,
    quietHrr: Double?,
    sex: BanisterSex,
): NetTrimp? {
    if (!netAnchorsValid(restingHr, maxHr, quietHrr)) return null
    val acc = NetAccumulator(restingHr!!, maxHr!!, quietHrr!!, sex)
    for (hr in hrPerMin) acc.add(hr)
    return if (acc.minutes == 0) null else acc.result()
}

internal fun strainScoreFromSeries(
    hrPerMin: List<Double>,
    restingHr: Double?,
    maxHr: Double?,
    quietHrr: Double?,
    sex: BanisterSex,
    quietSettled: Boolean,
): Scored<Double> {
    val n = netTrimpAboveQuiet(hrPerMin, restingHr, maxHr, quietHrr, sex)
        ?: return Scored.absent(
            "strain needs finite RHR < HRmax, quiet-waking HRR in (0, $MAX_QUIET_HRR], and one worn minute",
        )
    val note = if (quietSettled) {
        "Banister TRIMP above your quiet-waking level, log-mapped to 0–21"
    } else {
        "calibrating: quiet-waking level from fewer than $QUIET_HRR_SETTLED_DAYS days"
    }
    return Scored.of(strainFromNetTrimp(n.net), if (quietSettled) 0.6 else 0.45, note)
}

internal fun strainCurveFromSeries(
    hrPerMin: List<Double>,
    restingHr: Double?,
    maxHr: Double?,
    quietHrr: Double?,
    sex: BanisterSex,
): List<Double>? {
    if (!netAnchorsValid(restingHr, maxHr, quietHrr)) return null
    val acc = NetAccumulator(restingHr!!, maxHr!!, quietHrr!!, sex)
    val out = DoubleArray(hrPerMin.size)
    var last = 0.0
    for (i in hrPerMin.indices) {
        if (acc.add(hrPerMin[i])) last = strainFromNetTrimp(acc.result().net)
        out[i] = last
    }
    return if (acc.minutes == 0) null else out.toList()
}

internal fun personalQuietWakingHrr(priorDailyLevels: List<Double>): Scored<QuietLevel> {
    val valid = priorDailyLevels.filter { it.isFinite() && it > 0 && it <= MAX_QUIET_HRR }
    val w = if (valid.size > QUIET_HRR_WINDOW_DAYS) valid.takeLast(QUIET_HRR_WINDOW_DAYS) else valid
    if (w.size < QUIET_HRR_MIN_DAYS) {
        return Scored.absent(needBaselineNote(w.size, QUIET_HRR_MIN_DAYS))
    }
    val level = QuietLevel(median(w)!!, w.size)
    return Scored.of(
        level,
        if (level.settled) 0.7 else 0.4,
        "median of ${w.size} prior days of quiet waking",
    )
}

internal fun trimpStrain(
    bpm: List<Double>,
    tsSec: List<Double>,
    maxHr: Double?,
    restingHr: Double?,
    sex: BanisterSex = BanisterSex.MALE,
): Scored<Double> {
    if (maxHr == null || restingHr == null) {
        return Scored.absent("needs a real maxHr and restingHr, not fabricated defaults")
    }
    val s = StrainScorer.strain(
        bpm,
        tsSec,
        maxHr,
        restingHr,
        female = sex == BanisterSex.FEMALE,
    ) ?: return Scored.absent("strain needs ≥600 HR samples (or ≥20 spanning ≥600 s) and HRmax>RHR")
    return Scored.of(s, 0.6, "Banister TRIMP → 0–100 strain")
}

internal fun ctlAtlTsb(
    dailyTrimp: List<Double>,
    ctlDays: Double = 42.0,
    atlDays: Double = 7.0,
    minDays: Int = CTL_ATL_MIN_DAYS,
    primeDays: Int = 7,
): Scored<LoadState> {
    if (dailyTrimp.size < minDays) {
        return Scored.absent(needBaselineNote(dailyTrimp.size, minDays))
    }
    val lc = 1 - exp(-1 / ctlDays)
    val la = 1 - exp(-1 / atlDays)
    val prime = min(max(primeDays, 1), dailyTrimp.size)
    val seed = mean(dailyTrimp.subList(0, prime))!!
    var ctl = seed
    var atl = seed
    for (i in prime until dailyTrimp.size) {
        ctl += lc * (dailyTrimp[i] - ctl)
        atl += la * (dailyTrimp[i] - atl)
    }
    val conf = (dailyTrimp.size / 42.0).coerceIn(0.3, 0.85)
    return Scored.of(
        LoadState(ctl, atl, ctl - atl),
        conf,
        "Banister CTL(42d)/ATL(7d)/TSB, primed from the first $prime observed days",
    )
}
