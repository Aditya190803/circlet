package app.circlet.analytics.wellness

import app.circlet.analytics.support.calendarDays
import app.circlet.analytics.support.dispersionBelowQuantum
import app.circlet.analytics.support.mad
import app.circlet.analytics.support.mean
import app.circlet.analytics.support.median
import app.circlet.analytics.support.needBaselineNote
import app.circlet.analytics.support.stddev
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

// Ported from OpenStrap lib/src/onehz/wellness/anomaly.dart.
// Robust Mahalanobis distance of a night's (RHR, HRV, temperature, respiration)
// vector against the trailing personal cloud (Mahalanobis 1936), gated by a
// sample-size-widened chi-square 0.999 quantile and two consecutive nights.
// Temperature is accepted by the port so the vectors match; Circlet never
// supplies it, because the strap has no usable skin temperature.

internal const val MULTIVARIATE_ANOMALY_MIN_BASELINE = 10

internal data class AnomalyFeatures(
    val rhr: Double? = null,
    val hrv: Double? = null,
    val temp: Double? = null,
    val resp: Double? = null,
)

internal data class AnomalyDriver(val label: String, val contribution: Double)

internal data class AnomalyDay(
    val date: String,
    val mahalanobis: Double?,
    val flagged: Boolean,
    val candidate: Boolean,
    val drivers: List<AnomalyDriver>,
    val need: String? = null,
)

private val featLabels = arrayOf("RHR", "HRV(↓)", "temp", "resp")
private val featQuantum = doubleArrayOf(1.0, 0.0, 1.0, 0.0)
private val madInflationTable = listOf(
    10 to 13.23, 14 to 5.65, 20 to 3.17, 28 to 2.24, 45 to 1.65, 90 to 1.25, 200 to 1.0,
)

internal fun multivariateAnomaly(
    dates: List<String>,
    feats: List<AnomalyFeatures>,
    baselineDays: Int = 28,
    minBaseline: Int = MULTIVARIATE_ANOMALY_MIN_BASELINE,
    chiSqGate: Double? = null,
    persistDays: Int = 2,
    ridge: Double = 0.1,
): List<AnomalyDay> {
    val day = calendarDays(dates)
    val out = ArrayList<AnomalyDay>(feats.size)
    var run = 0
    var lastScored = Int.MIN_VALUE / 4
    for (i in feats.indices) {
        val cur = orient(feats[i])
        val cols = List(4) { ArrayList<Double>() }
        val baseRows = ArrayList<List<Double?>>()
        var j = i - 1
        while (j >= 0 && day[i] - day[j] <= baselineDays) {
            val o = orient(feats[j])
            for (f in 0 until 4) o[f]?.let { cols[f].add(it) }
            baseRows.add(o)
            j--
        }
        val idx = (0 until 4).filter { cur[it] != null && cols[it].size >= minBaseline }
        if (idx.size < 2) {
            var best = -1
            for (f in 0 until 4) if (cur[f] != null && cols[f].size > best) best = cols[f].size
            val need = if (best >= 0) needBaselineNote(best, minBaseline) else null
            out.add(AnomalyDay(dates[i], null, false, false, emptyList(), need))
            run = 0
            continue
        }
        val keep = ArrayList<Int>()
        val center = ArrayList<Double>()
        val scale = ArrayList<Double>()
        for (f in idx) {
            val m = mad(cols[f]) ?: 0.0
            val sc = if (m > 0) m else (stddev(cols[f]) ?: 0.0)
            if (!sc.isFinite() || sc <= 0) continue
            if (dispersionBelowQuantum(cols[f], featQuantum[f])) continue
            keep.add(f)
            center.add(median(cols[f])!!)
            scale.add(sc)
        }
        if (keep.size < 2) {
            out.add(AnomalyDay(dates[i], null, false, false, emptyList(), "degenerate_baseline:no_dispersion"))
            run = 0
            continue
        }
        val zc = DoubleArray(keep.size) { a -> (cur[keep[a]]!! - center[a]) / scale[a] }
        val rows = baseRows.filter { row -> keep.all { row[it] != null } }
        val cov = robustCorr(rows, keep, center, scale, ridge)
        val inv = invert(cov)
        var d2 = 0.0
        if (inv == null) {
            for (v in zc) d2 += v * v
        } else {
            for (a in zc.indices) for (b in zc.indices) d2 += zc[a] * inv[a][b] * zc[b]
        }
        if (d2 < 0) d2 = 0.0
        val drivers = keep.indices.map { a -> AnomalyDriver(featLabels[keep[a]], zc[a]) }
            .sortedByDescending { abs(it.contribution) }
        if (day[i] - lastScored > 1) run = 0
        lastScored = day[i]
        var baseN = Int.MAX_VALUE
        for (f in keep) if (cols[f].size < baseN) baseN = cols[f].size
        val gate = chiSqGate ?: (chiSq999(keep.size) * madInflation(baseN))
        val candidate = d2 > gate
        if (candidate) run++ else run = 0
        out.add(AnomalyDay(dates[i], sqrt(d2), candidate && run >= persistDays, candidate, drivers))
    }
    return out
}

private fun orient(f: AnomalyFeatures): List<Double?> =
    listOf(f.rhr, f.hrv?.let { -it }, f.temp, f.resp)

private fun madInflation(n: Int): Double {
    if (n <= madInflationTable.first().first) return madInflationTable.first().second
    if (n >= madInflationTable.last().first) return 1.0
    for (i in 1 until madInflationTable.size) {
        val (n1, f1) = madInflationTable[i]
        if (n > n1) continue
        val (n0, f0) = madInflationTable[i - 1]
        val t = (ln(n.toDouble()) - ln(n0.toDouble())) / (ln(n1.toDouble()) - ln(n0.toDouble()))
        return exp(ln(f0) + t * (ln(f1) - ln(f0)))
    }
    return 1.0
}

private fun chiSq999(dof: Int): Double = when (dof) {
    1 -> 10.83
    2 -> 13.82
    3 -> 16.27
    else -> 18.47
}

private fun robustCorr(
    rows: List<List<Double?>>,
    idx: List<Int>,
    center: List<Double>,
    scale: List<Double>,
    ridge: Double,
): Array<DoubleArray> {
    val k = idx.size
    val m = Array(k) { DoubleArray(k) }
    for (i in 0 until k) m[i][i] = 1.0
    if (rows.size >= 4) {
        val std = rows.map { r -> DoubleArray(k) { a -> (r[idx[a]]!! - center[a]) / scale[a] } }
        for (a in 0 until k) {
            for (b in a + 1 until k) {
                val r = corr(std.map { it[a] }, std.map { it[b] }) ?: 0.0
                val rc = r.coerceIn(-0.95, 0.95)
                m[a][b] = rc
                m[b][a] = rc
            }
        }
    }
    for (a in 0 until k) for (b in 0 until k) {
        m[a][b] = (1 - ridge) * m[a][b] + if (a == b) ridge else 0.0
    }
    return m
}

private fun corr(a: List<Double>, b: List<Double>): Double? {
    if (a.size < 2) return null
    val ma = mean(a)!!
    val mb = mean(b)!!
    var sab = 0.0
    var saa = 0.0
    var sbb = 0.0
    for (i in a.indices) {
        val da = a[i] - ma
        val db = b[i] - mb
        sab += da * db
        saa += da * da
        sbb += db * db
    }
    if (saa <= 0 || sbb <= 0) return null
    return sab / sqrt(saa * sbb)
}

private fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
    val n = a.size
    val m = Array(n) { DoubleArray(2 * n) }
    for (i in 0 until n) {
        for (j in 0 until n) m[i][j] = a[i][j]
        m[i][n + i] = 1.0
    }
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
        if (abs(m[piv][col]) < 1e-12) return null
        val tmp = m[col]
        m[col] = m[piv]
        m[piv] = tmp
        val d = m[col][col]
        for (j in 0 until 2 * n) m[col][j] /= d
        for (r in 0 until n) {
            if (r == col) continue
            val f = m[r][col]
            for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
        }
    }
    return Array(n) { i -> m[i].copyOfRange(n, 2 * n) }
}
