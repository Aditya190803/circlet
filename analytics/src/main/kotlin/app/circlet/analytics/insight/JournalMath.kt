package app.circlet.analytics.insight

import app.circlet.analytics.support.DartRandom
import app.circlet.analytics.support.benjaminiHochberg
import app.circlet.analytics.support.confidenceTier
import app.circlet.analytics.support.dartShuffle
import app.circlet.core.AbsentReason
import app.circlet.core.CorrelationMetric
import app.circlet.core.DaySummary
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.Metric
import app.circlet.core.TagCorrelation
import kotlin.math.sqrt

// Ported from OpenStrap lib/src/onehz/human/coaching.dart `journalCorrelations`
// (the tag mean-difference the core contract names) and the lag table beside
// it. associations.dart is the stricter moving-block scan over arbitrary
// daily series; its 84-day floor would hide every effect the core model
// publishes once each side has two days, so the published number here is the
// mean difference and the permutation/FDR gate only moves confidence.
// Behaviour tags land on the following night (lag +1). Retrospective tags
// stay on the same label.

internal val journalTagLagDays: Map<String, Int> = mapOf(
    "caffeine" to 1,
    "alcohol" to 1,
    "late meal" to 1,
    "screens late" to 1,
    "sauna" to 1,
    "cold plunge" to 1,
    "workout" to 1,
    "social" to 1,
    "rest day" to 1,
    "stress" to 0,
    "poor sleep" to 0,
    "sick" to 0,
)

private const val MIN_EFFECT_PCT = 3.0
private const val MIN_COHENS_D = 0.5
private const val FDR_ALPHA = 0.10
private const val PERMUTATIONS = 999
private const val PERMUTATION_SEED = 20260817

fun tagCorrelations(
    days: List<DaySummary>,
    entries: List<JournalEntry>,
    catalogue: List<JournalTag> = emptyList(),
): List<TagCorrelation> {
    if (days.isEmpty()) return emptyList()
    if (entries.isEmpty() && catalogue.isEmpty()) return emptyList()
    val dates = days.map { it.date }
    val outcomes = mapOf(
        CorrelationMetric.RECOVERY to days.map { it.recovery.score.value?.toDouble() },
        CorrelationMetric.HRV to days.map { it.heart.hrv.rmssdMs.value },
        CorrelationMetric.SLEEP_PERFORMANCE to days.map { it.sleepPerformance.percent.value?.toDouble() },
    )
    val tagsByDate = entries.associate { it.date to it.tagIds }
    val tags = if (catalogue.isEmpty()) {
        entries.flatMap { it.tagIds }.distinct().sorted().map { id -> id to id.lowercase() }
    } else {
        catalogue.map { tag -> tag.id to tag.name.lowercase() }
    }
    data class Row(
        val tag: String,
        val metric: CorrelationMetric,
        val delta: Double?,
        val nWith: Int,
        val nWithout: Int,
        val floorOk: Boolean,
        val p: Double?,
    )
    val rows = ArrayList<Row>()
    for ((tag, lagKey) in tags) {
        val lag = journalTagLagDays[lagKey] ?: journalTagLagDays[tag.lowercase()] ?: 0
        for ((metric, series) in outcomes) {
            val tagged = ArrayList<Double>()
            val untagged = ArrayList<Double>()
            val vals = ArrayList<Double>()
            val inGroup = ArrayList<Boolean>()
            for (i in dates.indices) {
                val v = series[i] ?: continue
                val src = if (lag == 0) dates[i] else dates[i].minusDays(lag.toLong())
                val logged = tagsByDate[src] ?: continue
                val has = tag in logged
                (if (has) tagged else untagged).add(v)
                vals.add(v)
                inGroup.add(has)
            }
            if (tagged.size < 2 || untagged.size < 2) {
                rows.add(Row(tag, metric, null, tagged.size, untagged.size, false, null))
                continue
            }
            val taggedMean = tagged.average()
            val untaggedMean = untagged.average()
            val delta = taggedMean - untaggedMean
            val pct = if (kotlin.math.abs(untaggedMean) < 1e-9) null else (delta / kotlin.math.abs(untaggedMean)) * 100.0
            val st = sampleVar(tagged, taggedMean)
            val su = sampleVar(untagged, untaggedMean)
            val dof = tagged.size + untagged.size - 2
            val pooledVar = if (dof > 0) ((tagged.size - 1) * st + (untagged.size - 1) * su) / dof else 0.0
            val pooledSd = if (pooledVar > 0) sqrt(pooledVar) else 0.0
            val d = if (pooledSd > 0) delta / pooledSd else null
            val bigEnough = pct != null && kotlin.math.abs(pct) >= MIN_EFFECT_PCT
            val separated = if (d != null) {
                kotlin.math.abs(d) >= MIN_COHENS_D
            } else {
                kotlin.math.abs(delta) > 0 && tagged.size >= 3 && untagged.size >= 3
            }
            rows.add(
                Row(
                    tag, metric, delta, tagged.size, untagged.size,
                    bigEnough && separated,
                    permTwoSampleP(vals, inGroup, PERMUTATIONS, PERMUTATION_SEED),
                ),
            )
        }
    }
    val qs = benjaminiHochberg(rows.map { it.p })
    return rows.mapIndexed { i, row ->
        val q = qs[i]
        val meaningful = row.floorOk && q != null && q <= FDR_ALPHA
        val effect = if (row.delta == null) {
            Metric.absent<Double>(AbsentReason.NOT_ENOUGH_DATA)
        } else {
            Metric.of(row.delta, if (meaningful) confidenceTier(0.6) else confidenceTier(0.3))
        }
        TagCorrelation(row.tag, row.metric, effect, row.nWith, row.nWithout)
    }
}

private fun sampleVar(xs: List<Double>, m: Double): Double {
    if (xs.size < 2) return 0.0
    var s = 0.0
    for (x in xs) {
        val d = x - m
        s += d * d
    }
    return s / (xs.size - 1)
}

private fun permTwoSampleP(ys: List<Double>, inGroup: List<Boolean>, b: Int, seed: Int): Double {
    fun diff(g: List<Boolean>): Double {
        var s1 = 0.0
        var s0 = 0.0
        var n1 = 0
        var n0 = 0
        for (i in ys.indices) {
            if (g[i]) {
                s1 += ys[i]
                n1++
            } else {
                s0 += ys[i]
                n0++
            }
        }
        if (n1 == 0 || n0 == 0) return 0.0
        return s1 / n1 - s0 / n0
    }
    val obs = kotlin.math.abs(diff(inGroup))
    val rng = DartRandom(seed)
    val shuffled = inGroup.toMutableList()
    var ge = 0
    repeat(b) {
        shuffled.dartShuffle(rng)
        if (kotlin.math.abs(diff(shuffled)) >= obs - 1e-12) ge++
    }
    return (ge + 1.0) / (b + 1)
}
