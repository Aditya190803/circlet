package app.circlet.analytics.strain

import kotlin.math.max
import kotlin.math.min

// Ported from OpenStrap lib/src/onehz/workout/calories.dart.
// Keytel et al. 2005 active energy plus Mifflin–St Jeor 1990 basal rate,
// split at the HR-flex point (Spurr 1988 / Ceesay 1989) of 40% heart-rate
// reserve — the same ACSM moderate floor the strain exercise gate uses.
// Walking METs below that gate follow Tudor-Locke et al. 2019 (CADENCE-Adults).
// The nonbinary coefficient block is the element-wise mean of the two published
// sex sets, not a third published equation.

internal data class CalorieProfile(
    val weightKg: Double,
    val heightCm: Double,
    val age: Double,
    val sex: String,
)

internal data class DailyEnergy(
    val total: Double,
    val active: Double,
    val basal: Double,
    val walking: Double,
)

internal data class BoutCalories(
    val kcal: Double,
    val kj: Double,
    val usedDefaultAnchors: Boolean,
)

private data class CalorieCoeffs(
    val restingAlpha: Double,
    val restingWeight: Double,
    val restingHeight: Double,
    val restingAge: Double,
    val workoutHr: Double,
    val workoutWeight: Double,
    val workoutAge: Double,
    val workoutAlpha: Double,
)

internal object Calories {
    private val male = CalorieCoeffs(88.362, 13.397, 479.9, 5.677, 0.6309, 0.1988, 0.2017, -55.0969)
    private val female = CalorieCoeffs(447.593, 9.247, 309.8, 4.33, 0.4472, -0.1263, 0.0740, -20.4022)
    private val nonbinary = CalorieCoeffs(
        267.9775, 11.322, 394.85, 5.0035, 0.53905, 0.03625, 0.13785, -37.74955,
    )

    const val ACTIVE_HRR_FRACTION = 0.40
    const val WORKOUT_DIVISOR = 251.04
    const val DEFAULT_MERGE_GAP_CAP_S = 150.0

    fun activeGateHr(hrmax: Double, restingHr: Double): Double? =
        if (hrmax.isFinite() && restingHr.isFinite() && restingHr > 0 && restingHr < hrmax) {
            restingHr + ACTIVE_HRR_FRACTION * (hrmax - restingHr)
        } else {
            null
        }

    fun metFromCadenceSpm(cadenceSpm: Double): Double? {
        if (!cadenceSpm.isFinite() || cadenceSpm < 100.0) return null
        val met = 3.0 + (cadenceSpm - 100.0) * 0.1
        return if (met > 6.0) 6.0 else met
    }

    fun mifflinBmrKcalDay(weightKg: Double, heightCm: Double, age: Double, sex: String): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age
        val sexConst = when (sex.lowercase()) {
            "male" -> 5.0
            "female" -> -161.0
            else -> -78.0
        }
        return max(0.0, base + sexConst)
    }

    fun dailyEnergy(
        hrPerMin: List<Double>,
        profile: CalorieProfile,
        hrmax: Double,
        restingHr: Double,
        dayMinutes: Int = 1440,
        cadenceSpmPerMin: List<Double?>? = null,
    ): DailyEnergy? {
        if (cadenceSpmPerMin != null && cadenceSpmPerMin.size != hrPerMin.size) {
            throw IllegalArgumentException("cadenceSpmPerMin must align with hrPerMin")
        }
        val weightKg = if (profile.weightKg > 0) profile.weightKg else 70.0
        val heightCm = if (profile.heightCm > 0) profile.heightCm else 170.0
        val age = if (profile.age > 0) profile.age else 30.0
        val coeffs = resolve(profile.sex)
        val flexHr = activeGateHr(hrmax, restingHr) ?: return null
        val bmrDay = mifflinBmrKcalDay(weightKg, heightCm, age, profile.sex)
        val basalPerMin = bmrDay / 1440.0
        var active = 0.0
        var walking = 0.0
        for (i in hrPerMin.indices) {
            val hr = hrPerMin[i]
            if (hr.isFinite() && hr >= flexHr) {
                val activePerMin = activeKcalPerS(coeffs, hr, hrmax, weightKg, age) * 60.0
                val surplus = activePerMin - basalPerMin
                if (surplus > 0) active += surplus
                continue
            }
            val cad = cadenceSpmPerMin?.get(i) ?: continue
            val met = metFromCadenceSpm(cad) ?: continue
            walking += (met - 1.0) * basalPerMin
        }
        active += walking
        val basal = basalPerMin * dayMinutes
        return DailyEnergy(basal + active, active, basal, walking)
    }

    fun estimateBoutCalories(
        hrTsSec: List<Int>,
        hrBpm: List<Double>,
        profile: CalorieProfile,
        hrmax: Double?,
        restingHr: Double?,
        mergeGapCapS: Double = DEFAULT_MERGE_GAP_CAP_S,
    ): BoutCalories {
        val weightKg = if (profile.weightKg > 0) profile.weightKg else 70.0
        val heightCm = if (profile.heightCm > 0) profile.heightCm else 170.0
        val age = if (profile.age > 0) profile.age else 30.0
        val coeffs = resolve(profile.sex)
        val usedDefault = hrmax == null || restingHr == null || activeGateHr(hrmax, restingHr) == null
        val effMax = if (usedDefault) 220.0 else hrmax!!
        val effRest = if (usedDefault) 60.0 else restingHr!!
        val threshold = activeGateHr(effMax, effRest)!!
        val restingRate = restingKcalPerS(coeffs, weightKg, heightCm, age)
        // Bout samples arrive chronological; the permutation is the identity then.
        val order = if (isAscending(hrTsSec)) IntArray(hrTsSec.size) { it } else {
            val sorted = hrTsSec.indices.sortedBy { hrTsSec[it] }
            IntArray(hrTsSec.size) { sorted[it] }
        }
        var total = 0.0
        for (k in order.indices) {
            val b = hrBpm[order[k]]
            if (!b.isFinite()) continue
            val dur = if (k < order.size - 1) {
                val gap = (hrTsSec[order[k + 1]] - hrTsSec[order[k]]).toDouble()
                if (gap > 0) min(gap, mergeGapCapS) else 1.0
            } else {
                1.0
            }
            total += if (b < threshold) restingRate * dur else activeKcalPerS(coeffs, b, effMax, weightKg, age) * dur
        }
        return BoutCalories(total, total * 4.184, usedDefault)
    }

    private fun resolve(sex: String): CalorieCoeffs = when (sex.lowercase()) {
        "male" -> male
        "female" -> female
        else -> nonbinary
    }

    /** True when no element precedes an earlier one; such an input sorts to itself. */
    private fun isAscending(xs: List<Int>): Boolean {
        for (i in 1 until xs.size) {
            if (xs[i] < xs[i - 1]) return false
        }
        return true
    }

    private fun restingKcalPerS(c: CalorieCoeffs, weightKg: Double, heightCm: Double, age: Double): Double {
        val bmr = c.restingAlpha + c.restingWeight * weightKg + c.restingHeight * (heightCm / 100.0) - c.restingAge * age
        return max(0.0, bmr) / 86400.0
    }

    private fun activeKcalPerS(
        c: CalorieCoeffs,
        hr: Double,
        hrmax: Double,
        weightKg: Double,
        age: Double,
    ): Double {
        val ee = c.workoutHr * min(hr, hrmax) + c.workoutWeight * weightKg + c.workoutAge * age + c.workoutAlpha
        return max(0.0, ee) / WORKOUT_DIVISOR
    }
}
