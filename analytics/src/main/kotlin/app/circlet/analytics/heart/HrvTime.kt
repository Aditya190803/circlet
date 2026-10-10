// Ported from OpenStrap analytics/lib/src/onehz/clinical/hrv_time.dart.
// Time-domain pulse-rate variability: Task Force 1996 RMSSD, SDNN, SDANN, pNN50,
// with the OpenStrap NN-difference jitter floor and the RR over-count refusal.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.foundations.stddev
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Lag-1 ACF floor below which RMSSD and pNN50 are refused. SDNN and SDANN survive it. */
const val kNnDiffAcf1Floor = -0.35

private const val ACF1_MIN_DIFFS = 30

/**
 * Lag-1 autocorrelation of successive differences, pooled over contiguous runs.
 * Null when there are fewer than 30 differences or the series is constant.
 */
fun nnDiffAcf1(diffRuns: List<List<Double>>): Double? {
    var n = 0
    var sum = 0.0
    for (r in diffRuns) {
        for (d in r) {
            sum += d
            n++
        }
    }
    if (n < ACF1_MIN_DIFFS) return null
    val m = sum / n
    var cov = 0.0
    var varSum = 0.0
    for (r in diffRuns) {
        for (i in r.indices) {
            val a = r[i] - m
            varSum += a * a
            if (i > 0) cov += (r[i - 1] - m) * a
        }
    }
    return if (varSum > 0.0) cov / varSum else null
}

/** White-noise share of the mean squared successive difference that keeps a failed ACF1 refused. */
const val kNnDiffNoiseShareCeiling = 0.7

/**
 * Share of the mean squared successive difference that a flat RR noise floor accounts for.
 * Null when the spectrum cannot give a verdict (too few segments, a Nyquist peak,
 * no respiratory line, or differences that sit on a coarse beat-time lattice).
 */
fun nnDiffNoiseShare(diffRuns: List<List<Double>>, minSegments: Int = 20): Double? {
    val n = 64
    val kLo = 10
    val kHi = n / 2
    val w = DoubleArray(n) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)) }
    var w2 = 0.0
    for (x in w) w2 += x * x
    val nFreq = kHi - kLo + 1
    val cosT = Array(nFreq) { k ->
        val kk = k + kLo
        DoubleArray(n) { i -> cos(2.0 * PI * kk * i / n) }
    }
    val sinT = Array(nFreq) { k ->
        val kk = k + kLo
        DoubleArray(n) { i -> sin(2.0 * PI * kk * i / n) }
    }
    val psd = DoubleArray(kHi + 1)
    var segs = 0
    var nd = 0
    var ssd = 0.0
    var wsd = 0.0
    var wsum = 0.0
    var pSum = 0.0
    var p2Sum = 0.0
    for (r in diffRuns) {
        for (d in r) {
            ssd += d * d
            nd++
        }
        val x = DoubleArray(r.size + 1)
        for (i in r.indices) x[i + 1] = x[i] + r[i]
        val cov = DoubleArray(x.size)
        var s = 0
        while (s + n <= x.size) {
            var meanX = 0.0
            for (i in 0 until n) {
                meanX += x[s + i]
                cov[s + i] += w[i] * w[i]
            }
            meanX /= n
            val v = DoubleArray(n) { i -> (x[s + i] - meanX) * w[i] }
            var p = 0.0
            for (k in kLo..kHi) {
                val c = cosT[k - kLo]
                val sn = sinT[k - kLo]
                var re = 0.0
                var im = 0.0
                for (i in 0 until n) {
                    re += v[i] * c[i]
                    im -= v[i] * sn[i]
                }
                val power = re * re + im * im
                psd[k] += power
                p += power
            }
            pSum += p
            p2Sum += p * p
            segs++
            s += n / 2
        }
        for (i in r.indices) {
            val c = (cov[i] + cov[i + 1]) / 2.0
            wsd += c * r[i] * r[i]
            wsum += c
        }
    }
    if (segs < minSegments || ssd == 0.0 || wsd == 0.0) return null
    if (onCoarseLattice(diffRuns, ssd / nd)) return null
    if (pSum * pSum / p2Sum < 0.75 * minSegments) return null
    val band = psd.copyOfRange(kLo, psd.size)
    var peak = 0
    for (k in 1 until band.size) {
        if (band[k] > band[peak]) peak = k
    }
    if (peak + kLo >= kHi - 1) return null
    if (band.last() > 0.2 * band[peak]) return null
    val med = median(band.toList()) ?: return null
    if (band[peak] < 4.5 * med) return null
    val floorPower = med / segs / w2
    val vetted = wsd / wsum
    val all = ssd / nd
    return 1.0 - (vetted - 2.0 * floorPower) / max(vetted, all)
}

private fun onCoarseLattice(diffRuns: List<List<Double>>, msd: Double): Boolean {
    var q = Double.POSITIVE_INFINITY
    for (r in diffRuns) {
        for (d in r) {
            val a = abs(d)
            if (a > 1e-6 && a < q) q = a
        }
    }
    if (q.isInfinite() || msd >= 2.0 * q * q) return false
    for (r in diffRuns) {
        for (d in r) {
            val k = abs(d) / q
            if (abs(k - roundHalfAwayFromZero(k)) > 1e-3) return false
        }
    }
    return true
}

private fun jitterRefused(
    acf1: Double?,
    runs: List<List<Double>>,
    minSegments: Int = 20,
): Boolean {
    if (acf1 == null || acf1 >= kNnDiffAcf1Floor) return false
    val share = nnDiffNoiseShare(runs, minSegments)
    return share == null || share >= kNnDiffNoiseShareCeiling
}

private fun windowClears(runs: List<List<Double>>): Boolean {
    val a = nnDiffAcf1(runs)
    return a != null && !jitterRefused(a, runs, minSegments = 4)
}

private fun clearedWindows(
    nightAcf1: Double?,
    rmssds: List<Double>,
    winRuns: List<List<List<Double>>>,
): List<Double> {
    if (nightAcf1 == null || nightAcf1 >= kNnDiffAcf1Floor) return rmssds
    val out = ArrayList<Double>()
    for (i in rmssds.indices) {
        if (windowClears(winRuns[i])) out.add(rmssds[i])
    }
    return out
}

private val hann128: DoubleArray = DoubleArray(128) { i ->
    0.5 - 0.5 * cos(2.0 * PI * i / 127.0)
}

private val hann128Sq: Double = hann128.sumOf { it * it }

private fun windowPsd(diffRuns: List<List<Double>>, f: DoubleArray): DoubleArray? {
    val n = 128
    val p = DoubleArray(f.size)
    var segs = 0
    for (r in diffRuns) {
        val x = DoubleArray(r.size + 1)
        for (i in r.indices) x[i + 1] = x[i] + r[i]
        var s = 0
        while (s + n <= x.size) {
            var meanX = 0.0
            for (i in 0 until n) meanX += x[s + i]
            meanX /= n
            for (j in f.indices) {
                val c = cos(2.0 * PI * f[j])
                val sn = sin(2.0 * PI * f[j])
                var cr = 1.0
                var ci = 0.0
                var re = 0.0
                var im = 0.0
                for (i in 0 until n) {
                    val v = (x[s + i] - meanX) * hann128[i]
                    re += v * cr
                    im += v * ci
                    val t = cr * c - ci * sn
                    ci = cr * sn + ci * c
                    cr = t
                }
                p[j] += re * re + im * im
            }
            segs++
            s += n / 2
        }
    }
    if (segs == 0) return null
    for (j in p.indices) p[j] /= segs
    return p
}

/**
 * Windows whose breathing line holds still in Hz while heart rate moves.
 * Null when the night does not clear that last-resort gate.
 */
private fun steadyBreathingWindows(
    winRuns: List<List<List<Double>>>,
    meanRrMs: List<Double>,
): List<Int>? {
    var ssd = 0.0
    var nd = 0
    val all = ArrayList<List<Double>>()
    for (w in winRuns) all.addAll(w)
    for (r in all) {
        for (d in r) {
            ssd += d * d
            nd++
        }
    }
    if (nd == 0 || onCoarseLattice(all, ssd / nd)) return null
    val positive = ArrayList<Double>()
    for (m in meanRrMs) if (m > 0.0) positive.add(m)
    val ref = median(positive) ?: return null
    val fc = DoubleArray(128 - 38 + 1) { j -> (j + 38) / 256.0 }
    val byBeat = DoubleArray(fc.size)
    val byHz = DoubleArray(fc.size)
    val cover = IntArray(fc.size)
    val peakHz = LinkedHashMap<Int, Double>()
    val psd = LinkedHashMap<Int, DoubleArray>()
    for (w in winRuns.indices) {
        if (meanRrMs[w] <= 0.0) continue
        val pb = windowPsd(winRuns[w], fc) ?: continue
        val norm = median(pb.toList()) ?: continue
        if (norm <= 0.0) continue
        val scale = meanRrMs[w] / ref
        val fh = DoubleArray(fc.size) { j -> fc[j] * scale }
        val ph = windowPsd(winRuns[w], fh) ?: continue
        var best = -1
        for (j in fc.indices) {
            byBeat[j] += pb[j] / norm
            if (fh[j] > 0.5) continue
            byHz[j] += ph[j] / norm
            cover[j]++
            if (best < 0 || ph[j] > ph[best]) best = j
        }
        if (best >= 0) peakHz[w] = fc[best] / ref
        psd[w] = pb
    }
    val nw = peakHz.size
    if (nw < 12) return null
    val bins = ArrayList<Int>()
    for (j in fc.indices) if (cover[j] == nw) bins.add(j)
    if (bins.size < 20) return null
    val hz = DoubleArray(bins.size) { i -> byHz[bins[i]] }
    val bt = DoubleArray(bins.size) { i -> byBeat[bins[i]] }
    var pk = 0
    for (i in 1 until hz.size) if (hz[i] > hz[pk]) pk = i
    if (pk == 0 || pk == hz.size - 1) return null
    val f0 = fc[bins[pk]] / ref
    val brpm = f0 * 60000.0
    if (brpm < 8.0 || brpm > 30.0) return null
    var near = 0.0
    val loI = max(0, pk - 12)
    val hiI = min(bt.size - 1, pk + 12)
    for (i in loI..hiI) near = max(near, bt[i])
    val medHz = median(hz.toList()) ?: return null
    val promHz = hz[pk] / medHz
    if (promHz < max(2.5, 1.5 + 7.0 / sqrt(nw.toDouble()))) return null
    val medBt = median(bt.toList()) ?: return null
    if (promHz < 1.1 * near / medBt) return null
    if (byBeat.last() / medBt >= 0.5 * promHz) return null
    val keep = ArrayList<Int>()
    for ((key, value) in peakHz) {
        if (abs(ln(value / f0)) <= 0.15) keep.add(key)
    }
    if (keep.size < 6) return null
    var loF = Double.POSITIVE_INFINITY
    var hiF = 0.0
    for (w in keep) {
        val peak = peakHz[w]!!
        loF = min(loF, peak)
        hiF = max(hiF, peak)
    }
    var sq = 0.0
    var nAll = 0
    val acc = DoubleArray(fc.size)
    val wt = DoubleArray(fc.size)
    for (w in keep) {
        var count = 0
        for (r in winRuns[w]) {
            for (d in r) {
                sq += d * d
                count++
            }
        }
        nAll += count
        val a = loF * meanRrMs[w] - 3.0 / 128.0
        val b = hiF * meanRrMs[w] + 3.0 / 128.0
        val pb = psd[w]!!
        for (j in fc.indices) {
            if (fc[j] > a && fc[j] < b) continue
            acc[j] += count * pb[j] / (2.0 - 2.0 * cos(2.0 * PI * fc[j]))
            wt[j] += count.toDouble()
        }
    }
    var floorPower = Double.POSITIVE_INFINITY
    for (j in 8 until fc.size - 8) {
        var s = 0.0
        var m = 0
        for (i in j - 8..j + 8) {
            if (wt[i] < nAll / 2.0) break
            s += acc[i] / wt[i]
            m++
        }
        if (m == 17) floorPower = min(floorPower, s / 17.0)
    }
    if (floorPower == Double.POSITIVE_INFINITY) return null
    val noise = 1.1 * 6.0 * floorPower / hann128Sq * nAll
    return if (noise < kNnDiffNoiseShareCeiling * sq) keep else null
}

private data class KeptWindows(
    val rmssds: List<Double>,
    val byBreathing: Boolean,
    val note: String,
)

private fun keptWindows(
    acf1: Double?,
    refused: Boolean,
    rmssds: List<Double>,
    winRuns: List<List<List<Double>>>,
    meanRrMs: List<Double>,
): KeptWindows? {
    val kept = if (refused) emptyList() else clearedWindows(acf1, rmssds, winRuns)
    if (kept.isNotEmpty()) return KeptWindows(kept, byBreathing = false, note = "")
    if (acf1 == null || acf1 >= kNnDiffAcf1Floor) return null
    val idx = steadyBreathingWindows(winRuns, meanRrMs) ?: return null
    val chosen = ArrayList<Double>(idx.size)
    for (i in idx) chosen.add(rmssds[i])
    return KeptWindows(
        rmssds = chosen,
        byBreathing = true,
        note = " Jitter gate failed; kept the ${idx.size} windows on a breathing " +
            "line steady in Hz while heart rate drifted, at floor confidence.",
    )
}

private fun acf1Quality(acf1: Double?): Double =
    if (acf1 == null) 1.0 else (1.0 - acf1 / kNnDiffAcf1Floor).coerceIn(0.0, 1.0)

private fun fixed3(x: Double): String {
    val scaled = roundHalfAwayFromZero(x * 1000.0)
    val sign = if (scaled < 0.0) "-" else ""
    val absScaled = abs(scaled).toLong()
    val whole = absScaled / 1000
    val frac = (absScaled % 1000).toString().padStart(3, '0')
    return "$sign$whole.$frac"
}

private fun jitterNote(acf1: Double): String =
    "rmssd_refused:acf1=${fixed3(acf1)} — the NN successive " +
        "differences are essentially differenced white noise (−0.5 = pure, floor " +
        "$kNnDiffAcf1Floor), so RMSSD/pNN50 would measure beat-timing jitter, not " +
        "vagal tone"

/** Σ reported RR ÷ elapsed wall time above which the stream cannot be one heart. */
const val kRrCoverageCeiling = 1.10

/** Shortest wall span [rrCoverage] will judge, in seconds. */
const val kRrCoverageMinSpanSec = 600.0

private const val RR_COVERAGE_MIN_MS = 300.0
private const val RR_COVERAGE_MAX_MS = 2400.0

private fun rrCoverageCounts(rrMs: Double): Boolean =
    rrMs >= RR_COVERAGE_MIN_MS && rrMs <= RR_COVERAGE_MAX_MS

/** How much beat-time an RR stream banks against the wall clock it spans. */
data class RrCoverage(
    val coverage: Double,
    val sumRrSec: Double,
    val spanSec: Double,
    val beats: Int,
    val implausibleBeats: Int,
    val duplicateBeats: Int,
) {
    val overCounted: Boolean get() = coverage > kRrCoverageCeiling

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "rr_coverage" to round6(coverage),
        "sum_rr_sec" to round6(sumRrSec),
        "span_sec" to round6(spanSec),
        "beats" to beats,
        "implausible_beats" to implausibleBeats,
        "duplicate_beats" to duplicateBeats,
    )
}

private data class BeatStamp(val tsMs: Double, val rrMs: Double)

/**
 * Coverage of raw [rrMs] against beat-END times [rrTsMs].
 * Null when fewer than 2 beats, the lengths differ, or the span is under
 * [kRrCoverageMinSpanSec].
 */
fun rrCoverage(rrMs: List<Double>, rrTsMs: List<Double>): RrCoverage? {
    if (rrMs.size < 2 || rrMs.size != rrTsMs.size) return null
    var lo = 0
    var hi = 0
    for (i in 1 until rrTsMs.size) {
        if (rrTsMs[i] < rrTsMs[lo]) lo = i
        if (rrTsMs[i] > rrTsMs[hi]) hi = i
    }
    val first = rrMs[lo]
    val spanSec = (rrTsMs[hi] - rrTsMs[lo] + (if (rrCoverageCounts(first)) first else 0.0)) / 1000.0
    if (!spanSec.isFinite() || spanSec < kRrCoverageMinSpanSec) return null
    var sum = 0.0
    var implausible = 0
    var dup = 0
    val seen = HashSet<BeatStamp>()
    for (i in rrMs.indices) {
        val v = rrMs[i]
        if (rrCoverageCounts(v)) sum += v else implausible++
        if (!seen.add(BeatStamp(rrTsMs[i], v))) dup++
    }
    val sumSec = sum / 1000.0
    return RrCoverage(
        coverage = sumSec / spanSec,
        sumRrSec = sumSec,
        spanSec = spanSec,
        beats = rrMs.size,
        implausibleBeats = implausible,
        duplicateBeats = dup,
    )
}

/** Refusal note every RMSSD-family estimator gives an over-counted stream. */
fun rrOvercountNote(c: RrCoverage): String = overcountNote(c)

private fun overcountNote(c: RrCoverage): String =
    "rr_overcount:coverage=${round6(c.coverage)} — more beat-time than " +
        "elapsed time; the RR stream holds duplicated or interleaved beats"

/** Time-domain HRV on a cleaned NN series. Null fields were refused, not estimated. */
data class HrvTime(
    val rmssd: Double?,
    val sdnn: Double?,
    val sdann: Double?,
    val sdnnIndex: Double?,
    val pnn50: Double?,
    val nBeats: Int,
    val diffAcf1: Double?,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (rmssd != null) m["rmssd_ms"] = round6(rmssd)
        if (sdnn != null) m["sdnn_ms"] = round6(sdnn)
        if (sdann != null) m["sdann_ms"] = round6(sdann)
        if (sdnnIndex != null) m["sdnn_index_ms"] = round6(sdnnIndex)
        if (pnn50 != null) m["pnn50_pct"] = round6(pnn50)
        m["n_beats"] = nBeats
        if (diffAcf1 != null) m["diff_acf1"] = round6(diffAcf1)
        return m
    }
}

/**
 * Short-window time-domain HRV. RMSSD and pNN50 are null when successive
 * differences fail [kNnDiffAcf1Floor] and the breathing-line fallback does not
 * clear, or when [coverage] is over-counted. SDNN survives both gates.
 */
fun hrvTime(
    nnMs: List<Double>,
    nnTimesMs: List<Double>? = null,
    artifactFraction: Double = 0.0,
    coverage: RrCoverage? = null,
): OsMetric<HrvTime> {
    val inputs = listOf("rr_cleaned")
    if (nnMs.size < 2) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few NN intervals",
        )
    }
    val gapAware = nnTimesMs != null && nnTimesMs.size == nnMs.size
    val runs = ArrayList<List<Double>>()
    var run = ArrayList<Double>()
    for (i in 1 until nnMs.size) {
        if (gapAware && nnTimesMs[i] - nnTimesMs[i - 1] > nnMs[i] + 0.5) {
            if (run.isNotEmpty()) {
                runs.add(run)
                run = ArrayList()
            }
            continue
        }
        run.add(nnMs[i] - nnMs[i - 1])
    }
    if (run.isNotEmpty()) runs.add(run)

    var ssd = 0.0
    var nn50 = 0
    var pairs = 0
    for (r in runs) {
        for (d in r) {
            ssd += d * d
            if (abs(d) > 50.0) nn50++
            pairs++
        }
    }
    val acf1 = nnDiffAcf1(runs)
    val jittery = jitterRefused(acf1, runs)
    val overCounted = coverage?.overCounted == true
    val usable = pairs > 0 && !jittery && !overCounted
    var rmssd: Double? = if (usable) sqrt(ssd / pairs) else null
    val pnn50 = if (usable) 100.0 * nn50 / pairs else null
    if (jittery && gapAware && !overCounted) {
        rmssd = breathingRmssd(nnMs, nnTimesMs)
    }
    val byBreathing = jittery && rmssd != null
    val sdnn = stddev(nnMs)
    var sdann: Double? = null
    var sdnnIndex: Double? = null
    if (gapAware) {
        val seg = fiveMinSegments(nnMs, nnTimesMs)
        if (seg.size >= 2) {
            val means = ArrayList<Double>(seg.size)
            for (s in seg) means.add(mean(s)!!)
            sdann = stddev(means)
            val sds = ArrayList<Double>()
            for (s in seg) {
                val sd = stddev(s)
                if (sd != null) sds.add(sd)
            }
            sdnnIndex = if (sds.isEmpty()) null else mean(sds)
        }
    }
    val conf = if (byBreathing || overCounted) {
        0.3
    } else {
        ((nnMs.size / 250.0).coerceIn(0.0, 1.0) * acf1Quality(acf1) * (1.0 - artifactFraction))
            .coerceIn(0.3, 0.95)
    }
    val note = when {
        overCounted -> "${overcountNote(coverage!!)}. SDNN/SDANN survive it and are the " +
            "lead here. PRV not ECG-HRV."
        jittery -> "${jitterNote(acf1!!)}. SDNN/SDANN survive it and are the lead here. " +
            (if (byBreathing) {
                "RMSSD only from 5-min windows on a breathing line steady in Hz, at floor confidence. "
            } else {
                ""
            }) +
            "PRV not ECG-HRV."
        else -> "PRV not ECG-HRV; RMSSD/pNN50 are quantization-sensitive at 1 Hz " +
            "— lead with SDNN/SDANN"
    }
    return OsMetric(
        value = HrvTime(
            rmssd = rmssd,
            sdnn = sdnn,
            sdann = sdann,
            sdnnIndex = sdnnIndex,
            pnn50 = pnn50,
            nBeats = nnMs.size,
            diffAcf1 = acf1,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = note,
    )
}

private fun breathingRmssd(nn: List<Double>, times: List<Double>): Double? {
    val wins = LinkedHashMap<Int, MutableList<MutableList<Double>>>()
    val rrSum = HashMap<Int, Double>()
    val rrN = HashMap<Int, Int>()
    var prevWin = -1
    val t0 = times.first()
    for (i in nn.indices) {
        val w = floor((times[i] - t0) / 300000.0).toInt()
        rrSum[w] = (rrSum[w] ?: 0.0) + nn[i]
        rrN[w] = (rrN[w] ?: 0) + 1
        val runs = wins.getOrPut(w) { ArrayList() }
        if (i > 0 && w == prevWin && times[i] - times[i - 1] <= nn[i] + 0.5) {
            runs.last().add(nn[i] - nn[i - 1])
        } else {
            runs.add(ArrayList())
        }
        prevWin = w
    }
    val keys = wins.keys.sorted()
    val idx = steadyBreathingWindows(
        keys.map { wins[it]!! },
        keys.map { rrSum[it]!! / rrN[it]!! },
    ) ?: return null
    var ss = 0.0
    var n = 0
    for (i in idx) {
        for (r in wins[keys[i]]!!) {
            for (d in r) {
                ss += d * d
                n++
            }
        }
    }
    return if (n == 0) null else sqrt(ss / n)
}

/**
 * Median of per-window RMSSD. Absent when the night fails the jitter floor
 * or [coverage] is over-counted.
 */
fun nocturnalRmssd(
    nnMs: List<Double>,
    nnTimesMs: List<Double>,
    windowMs: Double = 300000.0,
    minBeatsPerWindow: Int = 5,
    stageMaskPerSec: List<Boolean>? = null,
    coverage: RrCoverage? = null,
): OsMetric<Double> {
    val inputs = listOf("rr_cleaned", "beat_times")
    if (coverage != null && coverage.overCounted) {
        return OsMetric.absent(tier = Tier.HIGH, inputsUsed = inputs, note = overcountNote(coverage))
    }
    if (nnMs.size != nnTimesMs.size || nnMs.size < minBeatsPerWindow + 1) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "too few NN intervals for windowed nocturnal RMSSD",
        )
    }
    val t0 = nnTimesMs.first()
    val buckets = LinkedHashMap<Int, MutableList<Int>>()
    for (i in nnMs.indices) {
        val idx = floor((nnTimesMs[i] - t0) / windowMs).toInt()
        buckets.getOrPut(idx) { ArrayList() }.add(i)
    }
    val rmssds = ArrayList<Double>()
    val runs = ArrayList<List<Double>>()
    val perWindow = ArrayList<List<List<Double>>>()
    val meanRr = ArrayList<Double>()
    for (idx in buckets.keys.sorted()) {
        if (stageMaskPerSec != null) {
            val midSec = floor((idx + 0.5) * windowMs / 1000.0).toInt()
            val keep = midSec >= 0 && midSec < stageMaskPerSec.size && stageMaskPerSec[midSec]
            if (!keep) continue
        }
        val seg = buckets[idx]!!
        if (seg.size < minBeatsPerWindow + 1) continue
        val winRuns = ArrayList<MutableList<Double>>()
        var run = ArrayList<Double>()
        for (k in 1 until seg.size) {
            val i = seg[k]
            val p = seg[k - 1]
            if (nnTimesMs[i] - nnTimesMs[p] > nnMs[i] + 0.5) {
                if (run.isNotEmpty()) {
                    winRuns.add(run)
                    run = ArrayList()
                }
                continue
            }
            run.add(nnMs[i] - nnMs[p])
        }
        if (run.isNotEmpty()) winRuns.add(run)
        var ssd = 0.0
        var nd = 0
        for (r in winRuns) {
            for (d in r) {
                ssd += d * d
                nd++
            }
        }
        if (nd < minBeatsPerWindow) continue
        runs.addAll(winRuns)
        perWindow.add(winRuns)
        val levels = ArrayList<Double>(seg.size)
        for (i in seg) levels.add(nnMs[i])
        meanRr.add(mean(levels)!!)
        rmssds.add(sqrt(ssd / nd))
    }
    val acf1 = nnDiffAcf1(runs)
    val refused = jitterRefused(acf1, runs)
    if (rmssds.isEmpty() && !refused) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no usable 5-min windows for nocturnal RMSSD",
        )
    }
    val kept = keptWindows(acf1, refused, rmssds, perWindow, meanRr)
    if (kept == null) {
        val note = if (refused) {
            jitterNote(acf1!!)
        } else {
            "${jitterNote(acf1!!)}; no 5-min window clears it on its own"
        }
        return OsMetric.absent(tier = Tier.HIGH, inputsUsed = inputs, note = note)
    }
    val robust = median(kept.rmssds)!!
    val conf = if (kept.byBreathing) {
        0.3
    } else {
        ((kept.rmssds.size / 12.0).coerceIn(0.0, 1.0) * acf1Quality(acf1)).coerceIn(0.3, 0.95)
    }
    return OsMetric(
        value = robust,
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "robust nocturnal RMSSD = MEDIAN of ${kept.rmssds.size} consecutive " +
            "5-min-window RMSSDs (REM/arousal-robust). PRV not ECG-HRV; " +
            "RMSSD is quantization-sensitive at 1 Hz.${kept.note}",
    )
}

/** Fewest successive differences a 5-min window needs before its RMSSD joins the nightly mean. */
const val kMinDiffsPerRmssdWindow = 20

/** Nightly headline RMSSD plus the diagnostics a bare double cannot carry. */
data class SessionRmssd(
    val rmssd: Double,
    val windows: Int,
    val overCountedWindows: Int = 0,
    val thinWindows: Int = 0,
    val minDiffsPerWindow: Int = kMinDiffsPerRmssdWindow,
    val diffAcf1: Double? = null,
    val rrCoverage: Double? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "rmssd_ms" to round6(rmssd),
            "windows" to windows,
            "overcounted_windows" to overCountedWindows,
            "thin_windows" to thinWindows,
            "min_diffs_per_window" to minDiffsPerWindow,
        )
        if (diffAcf1 != null) m["diff_acf1"] = round6(diffAcf1)
        if (rrCoverage != null) m["rr_coverage"] = round6(rrCoverage)
        return m
    }
}

/**
 * Sleep-session nightly RMSSD: the mean of cleaned 5-minute window RMSSDs.
 * Absent when the jitter gate fails or the session's beats over-count time.
 */
fun sleepSessionWindowedRmssd(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    startSec: Int,
    endSec: Int,
    windowSec: Int = 300,
    minDiffsPerWindow: Int = kMinDiffsPerRmssdWindow,
): OsMetric<Double> {
    val m = sleepSessionRmssdDetail(
        rrMs,
        rrTsMs,
        startSec = startSec,
        endSec = endSec,
        windowSec = windowSec,
        minDiffsPerWindow = minDiffsPerWindow,
    )
    return if (m.present) {
        OsMetric(
            value = m.value!!.rmssd,
            confidence = m.confidence,
            tier = m.tier,
            inputsUsed = m.inputsUsed,
            note = m.note,
        )
    } else {
        OsMetric.absent(tier = m.tier, inputsUsed = m.inputsUsed, note = m.note)
    }
}

/** [sleepSessionWindowedRmssd] with its diagnostics. This is the computation. */
fun sleepSessionRmssdDetail(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    startSec: Int,
    endSec: Int,
    windowSec: Int = 300,
    minDiffsPerWindow: Int = kMinDiffsPerRmssdWindow,
): OsMetric<SessionRmssd> {
    val inputs = listOf("rr_sleep_window")
    if (startSec <= 0 || endSec <= startSec || rrMs.isEmpty() || rrTsMs.isEmpty() || rrMs.size != rrTsMs.size) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "invalid or empty RR session window",
        )
    }
    val buckets = LinkedHashMap<Int, MutableList<Double>>()
    val bucketsTs = LinkedHashMap<Int, MutableList<Double>>()
    val inRr = ArrayList<Double>()
    val inTs = ArrayList<Double>()
    for (i in rrMs.indices) {
        val tsSec = roundHalfAwayFromZero(rrTsMs[i] / 1000.0).toInt()
        if (tsSec < startSec || tsSec >= endSec) continue
        val idx = (tsSec - startSec) / windowSec
        buckets.getOrPut(idx) { ArrayList() }.add(rrMs[i])
        bucketsTs.getOrPut(idx) { ArrayList() }.add(rrTsMs[i])
        inRr.add(rrMs[i])
        inTs.add(rrTsMs[i])
    }
    if (buckets.isEmpty()) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no RR beats inside the session window",
        )
    }
    val cov = rrCoverage(inRr, inTs)
    if (cov != null && cov.overCounted) {
        return OsMetric.absent(tier = Tier.HIGH, inputsUsed = inputs, note = overcountNote(cov))
    }
    val rmssds = ArrayList<Double>()
    val runs = ArrayList<List<Double>>()
    val perWindow = ArrayList<List<List<Double>>>()
    val meanRr = ArrayList<Double>()
    var overCountedWindows = 0
    var thinWindows = 0
    for (idx in buckets.keys.sorted()) {
        val winRr = buckets[idx]!!
        val winTs = bucketsTs[idx]!!
        val winStart = startSec + idx * windowSec
        val winSec = min(winStart + windowSec, endSec) - winStart
        var bankedMs = 0.0
        var lo = 0
        for (i in winRr.indices) {
            if (winTs[i] < winTs[lo]) lo = i
            if (rrCoverageCounts(winRr[i])) bankedMs += winRr[i]
        }
        val overhangMs = if (rrCoverageCounts(winRr[lo])) winRr[lo] else 0.0
        if (bankedMs > kRrCoverageCeiling * (winSec * 1000.0 + overhangMs)) {
            overCountedWindows++
            continue
        }
        val rrRuns = ArrayList<List<Double>>()
        for (r in cleanWindowRuns(winRr, winTs)) {
            if (r.size >= 2) rrRuns.add(r)
        }
        val diffRuns = ArrayList<List<Double>>()
        for (r in rrRuns) {
            val diffs = ArrayList<Double>(r.size - 1)
            for (i in 1 until r.size) diffs.add(r[i] - r[i - 1])
            diffRuns.add(diffs)
        }
        var ssd = 0.0
        var nd = 0
        for (r in diffRuns) {
            for (d in r) {
                ssd += d * d
                nd++
            }
        }
        if (nd < minDiffsPerWindow) {
            thinWindows++
            continue
        }
        runs.addAll(diffRuns)
        perWindow.add(diffRuns)
        val flat = ArrayList<Double>()
        for (r in rrRuns) flat.addAll(r)
        meanRr.add(mean(flat)!!)
        rmssds.add(sqrt(ssd / nd))
    }
    val droppedWhy = ArrayList<String>()
    if (thinWindows > 0) {
        droppedWhy.add(
            "$thinWindows window(s) had fewer than $minDiffsPerWindow clean successive differences",
        )
    }
    if (overCountedWindows > 0) {
        droppedWhy.add("$overCountedWindows window(s) held more beat-time than elapsed time")
    }
    val dropped = if (droppedWhy.isEmpty()) "" else " (${droppedWhy.joinToString("; ")})"
    val acf1 = nnDiffAcf1(runs)
    val refused = jitterRefused(acf1, runs)
    if (rmssds.isEmpty() && !refused) {
        return OsMetric.absent(
            tier = Tier.HIGH,
            inputsUsed = inputs,
            note = "no valid 5-min windows for sleep-session RMSSD$dropped",
        )
    }
    val kept = keptWindows(acf1, refused, rmssds, perWindow, meanRr)
    if (kept == null) {
        val note = if (refused) {
            "${jitterNote(acf1!!)}$dropped"
        } else {
            "${jitterNote(acf1!!)}; no 5-min window clears it on its own$dropped"
        }
        return OsMetric.absent(tier = Tier.HIGH, inputsUsed = inputs, note = note)
    }
    val meanRmssd = mean(kept.rmssds)!!
    val conf = if (kept.byBreathing) {
        0.3
    } else {
        ((kept.rmssds.size / 12.0).coerceIn(0.0, 1.0) * acf1Quality(acf1)).coerceIn(0.3, 0.95)
    }
    return OsMetric(
        value = SessionRmssd(
            rmssd = meanRmssd,
            windows = kept.rmssds.size,
            overCountedWindows = overCountedWindows,
            thinWindows = thinWindows,
            minDiffsPerWindow = minDiffsPerWindow,
            diffAcf1 = acf1,
            rrCoverage = cov?.coverage,
        ),
        confidence = conf,
        tier = Tier.HIGH,
        inputsUsed = inputs,
        note = "sleep-session HRV: mean RMSSD over cleaned 5-min windows$dropped.${kept.note}",
    )
}

private fun fiveMinSegments(nn: List<Double>, times: List<Double>): List<List<Double>> {
    val segMs = 300000.0
    val out = ArrayList<List<Double>>()
    if (nn.isEmpty()) return out
    val t0 = times.first()
    var curIdx = 0
    var cur = ArrayList<Double>()
    for (i in nn.indices) {
        val idx = floor((times[i] - t0) / segMs).toInt()
        if (idx != curIdx) {
            if (cur.size >= 2) out.add(cur)
            cur = ArrayList()
            curIdx = idx
        }
        cur.add(nn[i])
    }
    if (cur.size >= 2) out.add(cur)
    return out
}

private fun cleanWindowRuns(rr: List<Double>, ts: List<Double>): List<List<Double>> {
    val radius = 2
    val threshold = 0.20
    val nn = ArrayList<Double>()
    val at = ArrayList<Int>()
    val nnTs = ArrayList<Double>()
    for (i in rr.indices) {
        if (rr[i] >= 300.0 && rr[i] <= 2000.0) {
            nn.add(rr[i])
            at.add(i)
            nnTs.add(ts[i])
        }
    }
    val runs = ArrayList<List<Double>>()
    var run = ArrayList<Double>()
    var lastKept = -2
    var lastTs = 0.0
    for (i in nn.indices) {
        var keep = true
        if (nn.size > radius) {
            val lo = max(0, i - radius)
            val hi = min(nn.size - 1, i + radius)
            val neighbors = ArrayList<Double>()
            for (j in lo..hi) if (j != i) neighbors.add(nn[j])
            val med = if (neighbors.size < 2) null else median(neighbors)
            if (med != null && med > 0.0) keep = abs(nn[i] - med) / med <= threshold
        }
        if (!keep) {
            if (run.isNotEmpty()) {
                runs.add(run)
                run = ArrayList()
            }
            continue
        }
        if (run.isNotEmpty() && (at[i] != lastKept + 1 || nnTs[i] - lastTs > nn[i] + 1000.0)) {
            runs.add(run)
            run = ArrayList()
        }
        run.add(nn[i])
        lastKept = at[i]
        lastTs = nnTs[i]
    }
    if (run.isNotEmpty()) runs.add(run)
    return runs
}
