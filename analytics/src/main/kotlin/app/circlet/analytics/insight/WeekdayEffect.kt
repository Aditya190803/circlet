package app.circlet.analytics.insight

import app.circlet.analytics.support.DartRandom
import app.circlet.analytics.support.Scored
import app.circlet.analytics.support.averageRanks
import app.circlet.analytics.support.calendarDays
import app.circlet.analytics.support.dartShuffle
import app.circlet.analytics.support.median
import java.time.LocalDate

// Ported from OpenStrap lib/src/onehz/human/weekday_effect.dart.
// Kruskal-Wallis omnibus, then a permutation test on the largest weekday
// deviation. Under eight weeks it is absent. A weekday is a container, not a cause.

internal data class WeekdayEffectValue(
    val nByWeekday: Map<Int, Int>,
    val medianByWeekday: Map<Int, Double>,
    val overallMedian: Double,
    val kruskalH: Double,
    val omnibusP: Double,
    val peakWeekday: Int,
    val peakDelta: Double,
    val peakP: Double,
    val meaningful: Boolean,
)

internal fun weekdayEffect(
    dates: List<String>,
    values: List<Double?>,
    minPerWeekday: Int = 5,
    minSpanDays: Int = 56,
    alpha: Double = 0.05,
    permutations: Int = 999,
    seed: Int = 20260817,
): Scored<WeekdayEffectValue> {
    if (dates.size != values.size) return Scored.absent("misaligned_series")
    val xs = ArrayList<Double>()
    val group = ArrayList<Int>()
    val days = ArrayList<Int>()
    val cal = calendarDays(dates)
    for (i in dates.indices) {
        val v = values[i]
        if (v == null || !v.isFinite()) continue
        val parsed = runCatching { LocalDate.parse(dates[i]) }.getOrNull() ?: continue
        xs.add(v)
        group.add(parsed.dayOfWeek.value - 1)
        days.add(cal[i])
    }
    val counts = IntArray(7)
    for (g in group) counts[g]++
    val span = if (days.isEmpty()) 0 else days.max() - days.min() + 1
    val thin = counts.indexOfFirst { it < minPerWeekday }
    if (span < minSpanDays || thin >= 0) {
        return Scored.absent(
            "need_history:span=${span}d/${minSpanDays}d,min_per_weekday=${counts.min()}/$minPerWeekday",
        )
    }
    val overall = median(xs)!!
    val ranks = averageRanks(xs)
    val n = xs.size
    val tieCounts = HashMap<Double, Int>()
    for (x in xs) tieCounts[x] = (tieCounts[x] ?: 0) + 1
    var tieSum = 0.0
    for (t in tieCounts.values) tieSum += t * t * t - t
    val tieDiv = 1.0 - tieSum / (n * n * n - n)
    val hObs = kruskalH(ranks, group, 7)
    val devObs = maxDeviation(xs, group, 7, overall)
    val rng = DartRandom(seed)
    val shuffled = group.toMutableList()
    var hGe = 0
    var dGe = 0
    repeat(permutations) {
        shuffled.dartShuffle(rng)
        if (kruskalH(ranks, shuffled, 7) >= hObs - 1e-12) hGe++
        if (kotlin.math.abs(maxDeviation(xs, shuffled, 7, overall).first) >= kotlin.math.abs(devObs.first) - 1e-12) dGe++
    }
    val omnibusP = (hGe + 1.0) / (permutations + 1)
    val peakP = (dGe + 1.0) / (permutations + 1)
    val medians = (0 until 7).associate { g ->
        val bucket = xs.filterIndexed { i, _ -> group[i] == g }
        (g + 1) to median(bucket)!!
    }
    return Scored.of(
        WeekdayEffectValue(
            nByWeekday = counts.mapIndexed { i, c -> (i + 1) to c }.toMap(),
            medianByWeekday = medians,
            overallMedian = overall,
            kruskalH = if (tieDiv > 0) hObs / tieDiv else hObs,
            omnibusP = omnibusP,
            peakWeekday = devObs.second + 1,
            peakDelta = devObs.first,
            peakP = peakP,
            meaningful = omnibusP <= alpha && peakP <= alpha,
        ),
        (0.25 + 0.25 * (span / 365.0)).coerceIn(0.25, 0.5),
        "descriptive weekday split",
    )
}

private fun kruskalH(ranks: List<Double>, group: List<Int>, k: Int): Double {
    val n = ranks.size
    val sums = DoubleArray(k)
    val counts = IntArray(k)
    for (i in 0 until n) {
        sums[group[i]] += ranks[i]
        counts[group[i]]++
    }
    var acc = 0.0
    for (g in 0 until k) {
        if (counts[g] == 0) continue
        acc += sums[g] * sums[g] / counts[g]
    }
    return 12.0 / (n * (n + 1)) * acc - 3.0 * (n + 1)
}

private fun maxDeviation(values: List<Double>, group: List<Int>, k: Int, overall: Double): Pair<Double, Int> {
    val buckets = List(k) { ArrayList<Double>() }
    for (i in values.indices) buckets[group[i]].add(values[i])
    var best = 0.0
    var bestG = 0
    for (g in 0 until k) {
        if (buckets[g].isEmpty()) continue
        val d = median(buckets[g])!! - overall
        if (kotlin.math.abs(d) > kotlin.math.abs(best)) {
            best = d
            bestG = g
        }
    }
    return best to bestG
}
