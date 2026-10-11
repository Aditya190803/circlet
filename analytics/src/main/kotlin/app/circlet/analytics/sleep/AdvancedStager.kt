// Ported from OpenStrap analytics/lib/src/onehz/sleep/advanced_stager.dart.
// Cole–Kripke session detection; cardio (default), v1 percentile bands, and v2 HMM staging.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.percentileSorted
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.foundations.sampleCadenceSeconds
import app.circlet.analytics.foundations.stddev
import app.circlet.analytics.foundations.stddevPop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-session staging engine. [cardio] is the production default. */
enum class StagingMethod { cardio, v1, v2 }

class HrTs(val ts: Int, val bpm: Double)

/** Gravity sample. [valid] false is an undecoded (0,0,0), not stillness. */
class GravTs(
    val ts: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean = true,
)

class RrTs(val ts: Int, val rrMs: Double)

class RespTs(val ts: Int, val raw: Double)

class StageSegment(val start: Int, val end: Int, val stage: String) {
    fun toJson(): Map<String, Any?> = linkedMapOf("start" to start, "end" to end, "stage" to stage)
}

class SleepSession(
    val start: Int,
    val end: Int,
    val efficiency: Double,
    val stages: List<StageSegment>,
    val restingHr: Int?,
    val avgHrv: Double?,
)

class HypnogramMetrics(
    val tibS: Int,
    val tstS: Int,
    val sptS: Int,
    val solS: Int?,
    val remLatencyS: Double,
    val wasoS: Int,
    val efficiency: Double,
    val disturbances: Int,
    val deepMin: Double,
    val remMin: Double,
    val lightMin: Double,
    val deepPct: Double,
    val remPct: Double,
    val lightPct: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "tib_s" to tibS,
        "tst_s" to tstS,
        "spt_s" to sptS,
        "sol_s" to solS,
        "rem_latency_s" to if (remLatencyS.isNaN()) null else remLatencyS,
        "waso_s" to wasoS,
        "efficiency" to efficiency,
        "disturbances" to disturbances,
        "deep_min" to deepMin,
        "rem_min" to remMin,
        "light_min" to lightMin,
        "deep_pct" to deepPct,
        "rem_pct" to remPct,
        "light_pct" to lightPct,
    )
}

object AdvancedSleepStager {
    const val gravityStillThresholdGPerS = 0.01
    const val stillWindowMin = 15
    const val stillFraction = 0.70
    const val maxGapMin = 20
    const val mergeMin = 15
    const val minSleepMin = 60
    const val secondsPerDay = 86400
    const val minWindowSamples = 3
    const val hrSleepBaselineMult = 1.05
    const val hrRefineMinSamples = 30
    const val onsetPersistEpochs = 3

    const val daytimeBandStartHour = 11
    const val daytimeBandEndHour = 20
    const val nightContinuationGapMin = 90
    const val daytimeMinSleepMin = 90
    const val daytimeRestingHRMult = 0.95

    const val maxMainSleepSpanS = 16 * 60 * 60

    const val morningStillnessWindowMin = 180
    const val morningReonsetRestingHRMult = 0.90
    const val bandStateAsleep = kBandStateSleep
    const val morningReonsetBandAsleepFrac = 0.6

    const val offWristHRGapMin = 20
    const val maxOffWristSleepFraction = 0.5
    const val hrDenseSpacingS = 600

    const val sparseGravitySpanFrac = 0.5
    const val hrSleepBandMult = hrSleepBaselineMult
    const val sparseBridgeGapMin = 90

    const val epochS = 30.0
    const val featureWindowS = 5 * 60.0
    const val ckCountDivisor = 100.0
    const val ckCountClip = 300.0
    const val moveDeltaThresholdGPerS = 0.01
    const val maxStillCadenceSec = epochS
    const val hrDogSigma1S = 120.0
    const val hrDogSigma2S = 600.0

    const val stageHRLowPct = 25.0
    const val stageHRHighPct = 70.0
    const val stageHRVHighPct = 70.0
    const val stageHRVarHighPct = 65.0
    const val stageRRVHighPct = 65.0
    const val stageRRVLowPct = 50.0
    const val stageWakeMoveFrac = 0.15
    const val stageStillMoveFrac = 0.10

    const val cardiacSparseEpochFrac = 0.5
    const val smoothEpochs = 5
    const val noREMAfterOnsetMin = 15.0
    const val deepFirstFraction = 1.0 / 3.0
    const val fragmentMergeEpochs = 6

    val ckWeights: List<Double> = listOf(106.0, 54.0, 58.0, 76.0, 230.0, 74.0, 67.0)
    const val ckScale = 0.001
    const val ckBack = 4
    const val ckFwd = 2

    const val maxAccelCarryForwardSec = 60

    fun detectSleep(
        gravity: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs> = emptyList(),
        resp: List<RespTs> = emptyList(),
        tzOffsetSec: Int = 0,
        tzOffsetResolver: ((Int) -> Int)? = null,
        method: StagingMethod = StagingMethod.cardio,
        wristOff: List<List<Int>> = emptyList(),
        bandSleepState: List<List<Int>> = emptyList(),
    ): List<SleepSession> {
        fun tzAt(ts: Int): Int = tzOffsetResolver?.invoke(ts) ?: tzOffsetSec
        // Day data arrives chronological; sorting it again is pure waste. The
        // check is one pass, and a chronological input sorts to itself.
        val grav = if (gravity.isTsOrdered { it.ts }) gravity else gravity.sortedWith(compareBy { it.ts })
        if (grav.size < 2) return emptyList()
        val hrS = if (hr.isTsOrdered { it.ts }) hr else hr.sortedWith(compareBy { it.ts })
        val rrS = if (rr.isTsOrdered { it.ts }) rr else rr.sortedWith(compareBy { it.ts })
        val respS = if (resp.isTsOrdered { it.ts }) resp else resp.sortedWith(compareBy { it.ts })

        val baseline = hrBaseline(hrS)
        val sparse = isGravitySparse(grav, hrS)
        val deltas = gravityDeltas(grav)
        val flags = classifyStill(grav, deltas)
        var runs = buildRuns(grav, flags, sparse, hrS, baseline)
        runs = mergePeriods(runs, mergeMin)
        runs = bridgeSparseSleep(runs, sparse, hrS, baseline)

        val minSleepS = minSleepMin * 60
        val continuationGapS = nightContinuationGapMin * 60
        var chainPrevEnd: Int? = null
        var chainFromOvernight = false
        var nightTailAccepted = false
        val sessions = ArrayList<SleepSession>()

        for (p in runs) {
            if (p.stage != "sleep") continue
            val continuesChain = if (chainPrevEnd != null) {
                p.start - chainPrevEnd <= continuationGapS
            } else {
                false
            }
            val isNightTail = continuesChain && chainFromOvernight && !nightTailAccepted
            if ((p.end - p.start) <= minSleepS && !isNightTail) continue
            if ((p.end - p.start) > maxMainSleepSpanS) continue
            if (!confirmSleepWithHR(p, hrS, baseline)) continue
            if (offWristFraction(p, hrS, wristOff) >= maxOffWristSleepFraction) continue

            val resting = sessionRestingHR(p.start, p.end, hrS)
            val morningWakeEnd = if (chainFromOvernight) chainPrevEnd else null
            if (isDaytimeCenter(p, ::tzAt) &&
                !passesMorningStillnessGuard(p, resting, baseline, morningWakeEnd, bandSleepState) &&
                !isNightTail
            ) {
                continue
            }

            val stages = when (method) {
                StagingMethod.cardio -> stageSessionCardio(p.start, p.end, grav, hrS, rrS)
                StagingMethod.v2 -> stageSessionV2(p.start, p.end, grav, hrS, rrS)
                StagingMethod.v1 -> stageSession(p.start, p.end, grav, hrS, rrS, respS)
            }
            sessions.add(
                SleepSession(
                    start = p.start,
                    end = p.end,
                    efficiency = efficiency(p.start, p.end, stages),
                    stages = stages,
                    restingHr = resting,
                    avgHrv = sessionAvgHrv(p.start, p.end, rrS),
                ),
            )
            if (!continuesChain) {
                chainFromOvernight = isOvernightOnset(p.start, ::tzAt)
                nightTailAccepted = false
            } else if (isNightTail && (p.end - p.start) <= minSleepS) {
                nightTailAccepted = true
            }
            chainPrevEnd = p.end
        }
        sessions.sortBy { it.start }
        return sessions
    }

    fun stageWindow(
        startSec: Int,
        endSec: Int,
        gravity: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs> = emptyList(),
        resp: List<RespTs> = emptyList(),
        method: StagingMethod = StagingMethod.cardio,
    ): SleepSession {
        val stages = when (method) {
            StagingMethod.cardio -> stageSessionCardio(startSec, endSec, gravity, hr, rr)
            StagingMethod.v2 -> stageSessionV2(startSec, endSec, gravity, hr, rr)
            StagingMethod.v1 -> stageSession(startSec, endSec, gravity, hr, rr, resp)
        }
        return SleepSession(
            start = startSec,
            end = endSec,
            efficiency = efficiency(startSec, endSec, stages),
            stages = stages,
            restingHr = sessionRestingHR(startSec, endSec, hr),
            avgHrv = sessionAvgHrv(startSec, endSec, rr),
        )
    }

    fun mainSleep(
        gravity: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs> = emptyList(),
        resp: List<RespTs> = emptyList(),
        tzOffsetSec: Int = 0,
        method: StagingMethod = StagingMethod.cardio,
    ): OsMetric<SleepSession> {
        val inputs = listOf("accel_1hz", "hr_1hz", "rr_ms")
        val sessions = detectSleep(
            gravity, hr, rr = rr, resp = resp, tzOffsetSec = tzOffsetSec, method = method,
        )
        if (sessions.isEmpty()) {
            return OsMetric.absent(
                tier = Tier.ESTIMATE,
                inputsUsed = inputs,
                note = "no qualifying sleep detected",
            )
        }
        var best = sessions.first()
        var bestTst = hypnogramMetrics(best).tstS
        for (s in sessions.drop(1)) {
            val t = hypnogramMetrics(s).tstS
            if (t > bestTst) {
                bestTst = t
                best = s
            }
        }
        return OsMetric(
            value = best,
            confidence = 0.5,
            tier = Tier.ESTIMATE,
            inputsUsed = inputs,
            note = "${method.name} 4-class sleep ESTIMATE " +
                "(wake/light/deep/rem); wrist autonomic, never PSG",
        )
    }

    fun hypnogramMetrics(session: SleepSession): HypnogramMetrics {
        val segs = session.stages.sortedBy { it.start }
        val tib = max(0, session.end - session.start)
        val sleepSegs = segs.filter { it.stage == "light" || it.stage == "deep" || it.stage == "rem" }
        var tst = 0
        var deepS = 0
        var remS = 0
        var lightS = 0
        for (s in sleepSegs) {
            val d = s.end - s.start
            tst += d
            if (s.stage == "deep") deepS += d
            if (s.stage == "rem") remS += d
            if (s.stage == "light") lightS += d
        }
        val onset: Int
        val sptEnd: Int
        val sol: Int?
        if (sleepSegs.isNotEmpty()) {
            onset = sleepSegs.first().start
            sptEnd = sleepSegs.last().end
            sol = max(0, onset - session.start)
        } else {
            onset = session.end
            sptEnd = session.end
            sol = null
        }
        val remSegs = sleepSegs.filter { it.stage == "rem" }
        val remLatency = if (remSegs.isNotEmpty()) {
            (remSegs.first().start - onset).toDouble()
        } else {
            Double.NaN
        }
        var waso = 0
        var disturbances = 0
        for (s in segs) {
            if (s.stage != "wake") continue
            val w0 = max(s.start, onset)
            val w1 = min(s.end, sptEnd)
            if (w1 > w0) {
                waso += w1 - w0
                disturbances++
            }
        }
        val se = if (tib > 0) tst.toDouble() / tib else 0.0
        fun pct(x: Int): Double = if (tst > 0) x.toDouble() / tst * 100.0 else 0.0
        return HypnogramMetrics(
            tibS = tib,
            tstS = tst,
            sptS = max(0, sptEnd - onset),
            solS = sol,
            remLatencyS = remLatency,
            wasoS = waso,
            efficiency = min(1.0, se),
            disturbances = disturbances,
            deepMin = deepS / 60.0,
            remMin = remS / 60.0,
            lightMin = lightS / 60.0,
            deepPct = pct(deepS),
            remPct = pct(remS),
            lightPct = pct(lightS),
        )
    }

    private fun gravityDeltas(g: List<GravTs>): DoubleArray {
        val n = g.size
        val out = DoubleArray(n)
        for (i in 1 until n) {
            if (!g[i].valid || !g[i - 1].valid) {
                out[i] = Double.NaN
                continue
            }
            val dx = g[i - 1].x - g[i].x
            val dy = g[i - 1].y - g[i].y
            val dz = g[i - 1].z - g[i].z
            out[i] = sqrt(dx * dx + dy * dy + dz * dz)
        }
        return out
    }

    private fun windowSize(interval: Double): Int =
        max(minWindowSamples, ((stillWindowMin * 60) / interval).toInt())

    private fun largestGapS(times: List<Int>): Double {
        if (times.size < 2) return 0.0
        var m = 0
        for (i in 0 until times.size - 1) {
            val g = times[i + 1] - times[i]
            if (g > m) m = g
        }
        return m.toDouble()
    }

    private fun isGravitySparse(grav: List<GravTs>, hr: List<HrTs>): Boolean {
        if (grav.size < 2 || hr.size < 2) return false
        val hrSpan = hr.last().ts - hr.first().ts
        if (hrSpan <= 0) return false
        val gravSpan = grav.last().ts - grav.first().ts
        if (gravSpan < sparseGravitySpanFrac * hrSpan) return true
        return largestGapS(grav.map { it.ts }) > maxGapMin * 60
    }

    private fun hrBaseline(hr: List<HrTs>): Double? {
        if (hr.isEmpty()) return null
        return median(hr.map { it.bpm })
    }

    /** True when no element precedes an earlier one; such an input sorts to itself. */
    private fun <T> List<T>.isTsOrdered(tsOf: (T) -> Int): Boolean {
        for (i in 1 until size) {
            if (tsOf(this[i]) < tsOf(this[i - 1])) return false
        }
        return true
    }

    private fun hrSleepBandAcross(a: Int, b: Int, hr: List<HrTs>, baseline: Double?): Boolean {
        if (baseline == null || b <= a || b - a > sparseBridgeGapMin * 60) return false
        val seg = ArrayList<Double>()
        for (h in hr) {
            if (h.ts in a..b) seg.add(h.bpm)
        }
        if (seg.isEmpty()) return false
        val meanHr = seg.sum() / seg.size
        return meanHr <= baseline * hrSleepBandMult
    }

    private fun classifyStill(grav: List<GravTs>, deltas: DoubleArray): BooleanArray {
        val n = grav.size
        if (n < 2) return BooleanArray(n)
        val cadence = sampleCadenceSeconds(grav.map { it.ts.toDouble() })
        if (cadence == null || cadence > maxStillCadenceSec) return BooleanArray(n)
        val window = windowSize(cadence)
        val half = window / 2
        val stillCut = gravityStillThresholdGPerS * cadence
        val stillPrefix = IntArray(n + 1)
        for (i in 0 until n) {
            stillPrefix[i + 1] = stillPrefix[i] + if (deltas[i] < stillCut) 1 else 0
        }
        val flags = BooleanArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n, i + half + 1)
            val stillCount = stillPrefix[hi] - stillPrefix[lo]
            flags[i] = stillCount.toDouble() / (hi - lo) >= stillFraction
        }
        return flags
    }

    private fun buildRuns(
        grav: List<GravTs>,
        flags: BooleanArray,
        sparse: Boolean,
        hr: List<HrTs>,
        baseline: Double?,
    ): List<Period> {
        val n = grav.size
        if (n == 0) return emptyList()
        val times = IntArray(n) { grav[it].ts }
        val maxGapS = maxGapMin * 60
        val periods = ArrayList<Period>()
        var runStart = 0
        for (i in 1..n) {
            val atEnd = i == n
            val close = if (atEnd) {
                true
            } else {
                val classChanged = flags[i] != flags[runStart]
                var gapExceeded = (times[i] - times[i - 1]) > maxGapS
                if (sparse && gapExceeded && !classChanged && flags[runStart] &&
                    hrSleepBandAcross(times[i - 1], times[i], hr, baseline)
                ) {
                    gapExceeded = false
                }
                classChanged || gapExceeded
            }
            if (close) {
                periods.add(Period(if (flags[runStart]) "sleep" else "active", times[runStart], times[i - 1]))
                runStart = i
            }
        }
        return periods
    }

    private fun mergePeriods(periods: List<Period>, mergeMinutes: Int): List<Period> {
        val thresholdS = mergeMinutes * 60
        val maxObservedGapS = maxGapMin * 60
        val pending = periods.toMutableList()
        val merged = ArrayList<Period>()
        var i = 0
        while (i < pending.size) {
            val current = pending[i]
            val tooShort = (current.end - current.start) < thresholdS
            if (!tooShort) {
                merged.add(current)
                i += 1
                continue
            }
            val hasPrev = merged.isNotEmpty() && current.start - merged.last().end <= maxObservedGapS
            val hasNext = i + 1 < pending.size && pending[i + 1].start - current.end <= maxObservedGapS
            val bridgesSame = hasPrev && hasNext && merged.last().stage == pending[i + 1].stage
            if (bridgesSame) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(Period(prev.stage, prev.start, pending[i + 1].end))
                i += 2
            } else if (hasNext) {
                pending[i + 1] = Period(pending[i + 1].stage, current.start, pending[i + 1].end)
                i += 1
            } else if (hasPrev) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(Period(prev.stage, prev.start, current.end))
                i += 1
            } else {
                merged.add(current)
                i += 1
            }
        }
        return merged
    }

    private fun bridgeSparseSleep(
        periods: List<Period>,
        sparse: Boolean,
        hr: List<HrTs>,
        baseline: Double?,
    ): List<Period> {
        if (!sparse || periods.isEmpty()) return periods
        val bridgeGapS = sparseBridgeGapMin * 60
        val out = ArrayList<Period>()
        for (p in periods) {
            if (out.isNotEmpty() && out.last().stage == "sleep" && p.stage == "sleep") {
                val gap = p.start - out.last().end
                if (gap >= 0 && gap <= bridgeGapS &&
                    hrSleepBandAcross(out.last().end, p.start, hr, baseline)
                ) {
                    out[out.lastIndex] = Period("sleep", out.last().start, p.end)
                    continue
                }
            }
            out.add(p)
        }
        return out
    }

    private fun rowsBetween(rows: List<HrTs>, start: Int, end: Int): List<HrTs> {
        val out = ArrayList<HrTs>()
        for (r in rows) if (r.ts in start..end) out.add(r)
        return out
    }

    private fun confirmSleepWithHR(p: Period, hr: List<HrTs>, baseline: Double?): Boolean {
        if (baseline == null) return true
        val seg = rowsBetween(hr, p.start, p.end)
        if (seg.size < hrRefineMinSamples) return true
        val meanHr = seg.sumOf { it.bpm } / seg.size
        return meanHr <= baseline * hrSleepBaselineMult
    }

    private fun secOfDay(local: Long): Int {
        val day = secondsPerDay.toLong()
        return (((local % day) + day) % day).toInt()
    }

    private fun isDaytimeCenter(p: Period, tzAt: (Int) -> Int): Boolean {
        val center = p.start + (p.end - p.start) / 2
        val hour = secOfDay(center.toLong() + tzAt(center)) / 3600
        return hour >= daytimeBandStartHour && hour < daytimeBandEndHour
    }

    private fun isOvernightOnset(start: Int, tzAt: (Int) -> Int): Boolean {
        val hour = secOfDay(start.toLong() + tzAt(start)) / 3600
        return !(hour >= daytimeBandStartHour && hour < daytimeBandEndHour)
    }

    private fun passesDaytimeGuard(p: Period, restingHR: Int?, baseline: Double?): Boolean {
        if ((p.end - p.start) < daytimeMinSleepMin * 60) return false
        if (baseline == null || restingHR == null) return false
        return restingHR <= baseline * daytimeRestingHRMult
    }

    private fun bandStateConfirmsAsleep(p: Period, bandSleepState: List<List<Int>>): Boolean {
        val inBlock = ArrayList<List<Int>>()
        for (b in bandSleepState) {
            if (b.size >= 2 && b[0] >= p.start && b[0] <= p.end) inBlock.add(b)
        }
        if (inBlock.isEmpty()) return false
        var asleep = 0
        for (b in inBlock) if (b[1] == bandStateAsleep) asleep++
        return asleep.toDouble() / inBlock.size >= morningReonsetBandAsleepFrac
    }

    private fun passesMorningStillnessGuard(
        p: Period,
        restingHR: Int?,
        baseline: Double?,
        morningWakeEnd: Int?,
        bandSleepState: List<List<Int>>,
    ): Boolean {
        if (morningWakeEnd == null ||
            p.start < morningWakeEnd ||
            (p.start - morningWakeEnd) > morningStillnessWindowMin * 60
        ) {
            return passesDaytimeGuard(p, restingHR, baseline)
        }
        if (!passesDaytimeGuard(p, restingHR, baseline)) return false
        if (bandStateConfirmsAsleep(p, bandSleepState)) return true
        if (baseline == null || restingHR == null) return false
        return restingHR <= baseline * morningReonsetRestingHRMult
    }

    private fun offWristHRGapSpans(p: Period, hr: List<HrTs>): List<IntArray> {
        if (hr.isEmpty() || p.end <= p.start) return emptyList()
        // The detector sorts its heart rates once; everything downstream stays
        // in that order, so this is usually a one-pass check, not a sort.
        val sortedAll = if (hr.isTsOrdered { it.ts }) hr else hr.sortedBy { it.ts }
        val streamSpan = sortedAll.last().ts - sortedAll.first().ts
        if (streamSpan >= hrDenseSpacingS && hr.size < streamSpan / hrDenseSpacingS) return emptyList()
        val gapS = offWristHRGapMin * 60
        val seg = rowsBetween(hr, p.start, p.end).let {
            if (it.isTsOrdered { r -> r.ts }) it else it.sortedBy { r -> r.ts }
        }
        if (seg.isEmpty()) {
            return if ((p.end - p.start) >= gapS) listOf(intArrayOf(p.start, p.end)) else emptyList()
        }
        val spans = ArrayList<IntArray>()
        if (seg.first().ts - p.start >= gapS) spans.add(intArrayOf(p.start, seg.first().ts))
        for (i in 1 until seg.size) {
            if (seg[i].ts - seg[i - 1].ts >= gapS) spans.add(intArrayOf(seg[i - 1].ts, seg[i].ts))
        }
        if (p.end - seg.last().ts >= gapS) spans.add(intArrayOf(seg.last().ts, p.end))
        return spans
    }

    private fun offWristFraction(p: Period, hr: List<HrTs>, wristOff: List<List<Int>>): Double {
        val dur = p.end - p.start
        if (dur <= 0) return 0.0
        val spans = ArrayList<IntArray>()
        spans.addAll(offWristHRGapSpans(p, hr))
        for (w in wristOff) {
            if (w.size < 2) continue
            val s = max(w[0], p.start)
            val e = min(w[1], p.end)
            if (e > s) spans.add(intArrayOf(s, e))
        }
        if (spans.isEmpty()) return 0.0
        spans.sortBy { it[0] }
        var covered = 0
        var curStart = spans[0][0]
        var curEnd = spans[0][1]
        for (sp in spans.drop(1)) {
            if (sp[0] <= curEnd) {
                curEnd = max(curEnd, sp[1])
            } else {
                covered += curEnd - curStart
                curStart = sp[0]
                curEnd = sp[1]
            }
        }
        covered += curEnd - curStart
        return covered.toDouble() / dur
    }

    private fun efficiency(start: Int, end: Int, stages: List<StageSegment>): Double {
        val inBed = end - start
        if (inBed <= 0) return 0.0
        var wake = 0
        for (s in stages) {
            if (s.stage == "wake" || s.stage == "unobserved") wake += s.end - s.start
        }
        val asleep = max(0, inBed - wake)
        return min(1.0, asleep.toDouble() / inBed)
    }

    private fun sessionRestingHR(start: Int, end: Int, hr: List<HrTs>): Int? {
        val seg = rowsBetween(hr, start, end)
        if (seg.isEmpty()) return null
        val windowS = 300
        var t = start
        val means = ArrayList<Double>()
        while (t < end) {
            var sum = 0.0
            var c = 0
            for (r in seg) {
                if (r.ts >= t && r.ts < t + windowS) {
                    sum += r.bpm
                    c++
                }
            }
            if (c > 0) means.add(sum / c)
            t += windowS
        }
        if (means.isNotEmpty()) {
            var m = means[0]
            for (v in means) if (v < m) m = v
            return roundHalfAwayFromZero(m).toInt()
        }
        return roundHalfAwayFromZero(seg.sumOf { it.bpm } / seg.size).toInt()
    }

    private fun sessionAvgHrv(start: Int, end: Int, rr: List<RrTs>): Double? {
        if (start <= 0 || end <= start || rr.isEmpty()) return null
        val rrMs = DoubleArray(rr.size) { rr[it].rrMs }
        val rrTsMs = DoubleArray(rr.size) { rr[it].ts * 1000.0 }
        return sleepSessionWindowedRmssd(rrMs, rrTsMs, start, end)
    }

    private fun stageSessionCardio(
        start: Int,
        end: Int,
        grav: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs>,
    ): List<StageSegment> {
        val span = end - start
        if (span <= 0) return emptyList()
        val epSec = roundHalfAwayFromZero(epochS).toInt()
        val minStageableSec = 3 * epSec
        if (span < minStageableSec) return listOf(StageSegment(start, end, "unobserved"))

        val gAt = arrayOfNulls<GravTs>(span)
        for (g in grav) {
            if (g.valid && g.ts >= start && g.ts < end) gAt[g.ts - start] = g
        }
        val hAt = DoubleArray(span)
        val hHas = BooleanArray(span)
        for (h in hr) {
            if (h.ts >= start && h.ts < end) {
                hAt[h.ts - start] = h.bpm
                hHas[h.ts - start] = true
            }
        }
        val accel = MutableList(span) { AccelSample(start * 1000.0, 0.0, 0.0, 1.0) }
        val hr1hz = DoubleArray(span)
        val usable = BooleanArray(span)
        var lastRealIdx = -1
        for (i in 0 until span) {
            val ts = start + i
            val g = gAt[i]
            if (g != null) {
                accel[i] = AccelSample(ts * 1000.0, g.x, g.y, g.z)
                lastRealIdx = i
                usable[i] = true
            } else if (lastRealIdx >= 0 && (i - lastRealIdx) <= maxAccelCarryForwardSec) {
                val p = accel[i - 1]
                accel[i] = AccelSample(ts * 1000.0, p.x, p.y, p.z)
                usable[i] = true
            }
            hr1hz[i] = if (hHas[i]) hAt[i] else 0.0
        }
        val rrMs = ArrayList<Double>()
        val rrTsMs = ArrayList<Double>()
        for (r in rr) {
            if (r.ts >= start && r.ts < end) {
                rrMs.add(r.rrMs)
                rrTsMs.add(r.ts * 1000.0)
            }
        }
        val perSec = MutableList(span) { "unobserved" }
        var i = 0
        while (i < span) {
            if (!usable[i]) {
                i++
                continue
            }
            var j = i
            while (j < span && usable[j]) j++
            if ((j - i) >= minStageableSec) {
                val result = cardioStager(
                    hr1hz.slice(i until j),
                    accel.subList(i, j),
                    rrMs = rrMs,
                    rrTsMs = rrTsMs,
                )
                val nEpoch = result.base.stages.size
                for (e in 0 until nEpoch) {
                    val label = when (result.base.stages[e]) {
                        OsStage.WAKE -> "wake"
                        OsStage.REM -> "rem"
                        OsStage.NREM ->
                            if (e < result.deepFlag.size && result.deepFlag[e]) "deep" else "light"
                    }
                    val lo = i + e * epSec
                    val hi = if (e == nEpoch - 1) j else min(j, lo + epSec)
                    for (k in lo until hi) perSec[k] = label
                }
            }
            i = j
        }
        val segments = ArrayList<StageSegment>()
        var k = 0
        while (k < span) {
            var m = k
            while (m < span && perSec[m] == perSec[k]) m++
            segments.add(StageSegment(start + k, start + m, perSec[k]))
            k = m
        }
        return segments
    }

    private fun stageSession(
        start: Int,
        end: Int,
        grav: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs>,
        resp: List<RespTs>,
    ): List<StageSegment> {
        val gSeg = grav.filter { it.valid && it.ts >= start && it.ts <= end }
        if (gSeg.size < 2) return listOf(StageSegment(start, end, "light"))
        val hSeg = rowsBetween(hr, start, end)
        val rSeg = rr.filter { it.ts in start..end }
        val respSeg = resp.filter { it.ts in start..end }
        val grid = buildEpochGrid(start, end, gSeg, hSeg, rSeg, respSeg)
        if (grid.nEpochs == 0) return listOf(StageSegment(start, end, "light"))
        val ow = onsetAndFinalWake(grid.ckFlags)
        val onsetIdx = ow[0]
        val finalWakeIdx = ow[1]
        val feats = extractFeatures(grid, onsetIdx, finalWakeIdx)
        var labels = classifyEpochs(feats)
        labels = smoothLabels(labels)
        labels = reimposePhysiology(labels, feats, onsetIdx, finalWakeIdx)
        labels = mergeFragments(labels)
        for (i in labels.indices) {
            if (i < onsetIdx || i > finalWakeIdx) labels[i] = "wake"
        }
        return buildSegments(labels, grid, end)
    }

    private fun buildEpochGrid(
        start: Int,
        end: Int,
        gSeg: List<GravTs>,
        hSeg: List<HrTs>,
        rSeg: List<RrTs>,
        respSeg: List<RespTs>,
    ): EpochGrid {
        if (end <= start) {
            return EpochGrid(doubleArrayOf(start.toDouble()), 0, DoubleArray(0), DoubleArray(0), DoubleArray(0), emptyList(), emptyList(), BooleanArray(0))
        }
        val nEpochs = max(1, ceil((end - start) / epochS).toInt())
        val edges = DoubleArray(nEpochs + 1) { i -> start + i * epochS }
        edges[nEpochs] = max(edges[nEpochs], end.toDouble())
        fun idx(ts: Int): Int? {
            if (ts < start || ts >= end) {
                if (ts == end) return nEpochs - 1
                return null
            }
            val i = ((ts - start) / epochS).toInt()
            return min(i, nEpochs - 1)
        }
        val counts = DoubleArray(nEpochs)
        val gravN = IntArray(nEpochs)
        val moveN = IntArray(nEpochs)
        val hrSum = DoubleArray(nEpochs)
        val hrCnt = IntArray(nEpochs)
        val rrBuckets = List(nEpochs) { ArrayList<Double>() }
        val respBuckets = List(nEpochs) { ArrayList<Double>() }
        val cadence = sampleCadenceSeconds(gSeg.map { it.ts.toDouble() })
        val cadenceOk = cadence != null && cadence <= maxStillCadenceSec
        if (cadenceOk) {
            val moveCut = moveDeltaThresholdGPerS * cadence!!
            val gDeltas = gravityDeltas(gSeg)
            for (k in gSeg.indices) {
                val i = idx(gSeg[k].ts) ?: continue
                counts[i] += gDeltas[k]
                gravN[i] += 1
                if (gDeltas[k] >= moveCut) moveN[i] += 1
            }
        }
        for (h in hSeg) {
            val i = idx(h.ts) ?: continue
            hrSum[i] += h.bpm
            hrCnt[i] += 1
        }
        for (r in rSeg) {
            val i = idx(r.ts) ?: continue
            rrBuckets[i].add(r.rrMs)
        }
        for (r in respSeg) {
            val i = idx(r.ts) ?: continue
            respBuckets[i].add(r.raw)
        }
        val hrArr = DoubleArray(nEpochs) { Double.NaN }
        val moveFrac = DoubleArray(nEpochs) { 1.0 }
        for (i in 0 until nEpochs) {
            if (hrCnt[i] > 0) hrArr[i] = hrSum[i] / hrCnt[i]
            moveFrac[i] = if (gravN[i] > 0) moveN[i].toDouble() / gravN[i] else 1.0
        }
        val ckFlags = if (!cadenceOk) BooleanArray(nEpochs) else coleKripke(rescaleCounts(counts))
        return EpochGrid(edges, nEpochs, counts, hrArr, moveFrac, rrBuckets, respBuckets, ckFlags)
    }

    private fun rescaleCounts(counts: DoubleArray): DoubleArray =
        DoubleArray(counts.size) { min(counts[it] / ckCountDivisor, ckCountClip) }

    private fun coleKripke(rescaled: DoubleArray): BooleanArray {
        val n = rescaled.size
        val flags = BooleanArray(n)
        for (i in 0 until n) {
            var si = 0.0
            for (k in ckWeights.indices) {
                val j = i - ckBack + k
                val a = if (j in 0 until n) rescaled[j] else 0.0
                si += ckWeights[k] * a
            }
            si *= ckScale
            flags[i] = si < 1.0
        }
        return flags
    }

    private fun onsetAndFinalWake(ckFlags: BooleanArray): IntArray {
        val n = ckFlags.size
        if (n == 0) return intArrayOf(0, 0)
        var run = 0
        var onset: Int? = null
        for (i in 0 until n) {
            run = if (ckFlags[i]) run + 1 else 0
            if (run >= onsetPersistEpochs) {
                onset = i - onsetPersistEpochs + 1
                break
            }
        }
        var finalWake: Int? = null
        for (i in n - 1 downTo 0) {
            if (ckFlags[i]) {
                finalWake = i
                break
            }
        }
        var o = onset ?: 0
        var f = finalWake ?: (n - 1)
        if (f < o) f = n - 1
        return intArrayOf(o, f)
    }

    private fun gaussianKernel(sigmaS: Double, dtS: Double = 30.0): DoubleArray {
        val sigma = max(sigmaS / dtS, 1e-6)
        val radius = max(1, ceil(3 * sigma).toInt())
        val k = DoubleArray(radius * 2 + 1) { i ->
            val x = i - radius
            exp(-0.5 * (x / sigma) * (x / sigma))
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    private fun convolveReflect(x: DoubleArray, kernel: DoubleArray): DoubleArray {
        val r = kernel.size / 2
        if (r == 0 || x.size <= r) return x
        val padded = DoubleArray(x.size + 2 * r)
        for (i in 0 until r) padded[i] = x[r - i]
        for (i in x.indices) padded[r + i] = x[i]
        for (i in 0 until r) padded[r + x.size + i] = x[x.size - 2 - i]
        val m = kernel.size
        val out = DoubleArray(x.size)
        var produced = 0
        var i = 0
        while (i <= padded.size - m && produced < x.size) {
            var acc = 0.0
            for (j in 0 until m) acc += padded[i + j] * kernel[m - 1 - j]
            out[produced] = acc
            produced++
            i++
        }
        return out
    }

    private fun dogHRVariability(hrPerEpoch: DoubleArray): DoubleArray {
        val n = hrPerEpoch.size
        if (n == 0) return DoubleArray(0)
        val maskIdx = ArrayList<Int>()
        for (i in 0 until n) if (!hrPerEpoch[i].isNaN()) maskIdx.add(i)
        if (maskIdx.isEmpty()) return DoubleArray(n)
        val filled = DoubleArray(n)
        for (i in 0 until n) {
            if (!hrPerEpoch[i].isNaN()) {
                filled[i] = hrPerEpoch[i]
            } else if (i <= maskIdx.first()) {
                filled[i] = hrPerEpoch[maskIdx.first()]
            } else if (i >= maskIdx.last()) {
                filled[i] = hrPerEpoch[maskIdx.last()]
            } else {
                var lo = maskIdx.first()
                var hi = maskIdx.last()
                for (m in maskIdx) {
                    if (m <= i) lo = m
                    if (m >= i) {
                        hi = m
                        break
                    }
                }
                filled[i] = if (hi == lo) {
                    hrPerEpoch[lo]
                } else {
                    hrPerEpoch[lo] + (i - lo).toDouble() / (hi - lo) * (hrPerEpoch[hi] - hrPerEpoch[lo])
                }
            }
        }
        val g1 = convolveReflect(filled, gaussianKernel(hrDogSigma1S))
        val g2 = convolveReflect(filled, gaussianKernel(hrDogSigma2S))
        return DoubleArray(n) { g1[it] - g2[it] }
    }

    private fun extractFeatures(grid: EpochGrid, onsetIdx: Int, finalWakeIdx: Int): List<EpochFeatures> {
        val n = grid.nEpochs
        val dogHR = dogHRVariability(grid.hr)
        val halfW = roundHalfAwayFromZero(featureWindowS / epochS / 2.0).toInt()
        val span = max(1, finalWakeIdx - onsetIdx)
        val out = ArrayList<EpochFeatures>(n)
        for (i in 0 until n) {
            val lo = max(0, i - halfW)
            val hi = min(n, i + halfW + 1)
            val winDog = if (dogHR.isEmpty()) doubleArrayOf(0.0) else dogHR.copyOfRange(lo, hi)
            val hrVar = if (winDog.size >= 2) stddevPop(winDog.toList())!! else Double.NaN
            val winRR = ArrayList<Double>()
            val winResp = ArrayList<Double>()
            for (j in lo until hi) {
                winRR.addAll(grid.rrBuckets[j])
                winResp.addAll(grid.respBuckets[j])
            }
            val filteredRR = rangeFilter(winRR)
            val rmssd = if (filteredRR.size >= 5) (rmssdRaw(filteredRR) ?: Double.NaN) else Double.NaN
            val sdnn = if (filteredRR.size >= 5) (stddev(filteredRR) ?: Double.NaN) else Double.NaN
            val rr = if (winResp.size >= 8) respRateAndRRV(winResp) else rrvFromRrSeries(filteredRR)
            val clock = clampD((i - onsetIdx).toDouble() / span, 0.0, 1.0)
            val ckSleep = if (i < grid.ckFlags.size) grid.ckFlags[i] else true
            out.add(
                EpochFeatures(
                    index = i,
                    midTs = (grid.edges[i] + grid.edges[i + 1]) / 2.0,
                    moveFrac = grid.moveFrac[i],
                    ckSleep = ckSleep,
                    hr = grid.hr[i],
                    hrVar = hrVar,
                    rmssd = rmssd,
                    sdnn = sdnn,
                    respRate = rr[0],
                    rrv = rr[1],
                    clock = clock,
                ),
            )
        }
        return out
    }

    private fun respRateAndRRV(respRaw: List<Double>, dtS: Double = 1.0): DoubleArray {
        if (respRaw.size < 8) return doubleArrayOf(Double.NaN, Double.NaN)
        val meanV = respRaw.sum() / respRaw.size
        val x = DoubleArray(respRaw.size) { respRaw[it] - meanV }
        if (x.all { abs(it) < 1e-12 }) return doubleArrayOf(Double.NaN, Double.NaN)
        val std = stddevPop(x.toList())!!
        if (std <= 0.0) return doubleArrayOf(Double.NaN, Double.NaN)
        val minDistance = max(2, roundHalfAwayFromZero(2.0 / dtS).toInt())
        val peaks = findPeaks(x, minDistance, 0.0)
        if (peaks.size < 3) return doubleArrayOf(Double.NaN, Double.NaN)
        val intervals = ArrayList<Double>()
        for (i in 1 until peaks.size) {
            val iv = (peaks[i] - peaks[i - 1]) * dtS
            if (iv >= 1.5 && iv <= 12.0) intervals.add(iv)
        }
        if (intervals.size < 2) return doubleArrayOf(Double.NaN, Double.NaN)
        val rate = 60.0 / median(intervals)!!
        val rrv = stddevPop(intervals)!!
        return doubleArrayOf(rate, rrv)
    }

    private fun rrvFromRrSeries(rrMs: List<Double>): DoubleArray {
        if (rrMs.size < 8) return doubleArrayOf(Double.NaN, Double.NaN)
        val times = DoubleArray(rrMs.size + 1)
        for (i in rrMs.indices) times[i + 1] = times[i] + rrMs[i]
        val meanV = rrMs.sum() / rrMs.size
        val x = DoubleArray(rrMs.size) { rrMs[it] - meanV }
        if (x.all { abs(it) < 1e-9 }) return doubleArrayOf(Double.NaN, Double.NaN)
        val std = stddevPop(x.toList())!!
        if (std <= 0.0) return doubleArrayOf(Double.NaN, Double.NaN)
        val peaks = findPeaks(x, 2, 0.0)
        if (peaks.size < 3) return doubleArrayOf(Double.NaN, Double.NaN)
        val intervalsS = ArrayList<Double>()
        for (i in 1 until peaks.size) {
            val iv = (times[peaks[i]] - times[peaks[i - 1]]) / 1000.0
            if (iv >= 1.5 && iv <= 12.0) intervalsS.add(iv)
        }
        if (intervalsS.size < 2) return doubleArrayOf(Double.NaN, Double.NaN)
        val rate = 60.0 / median(intervalsS)!!
        val rrv = stddevPop(intervalsS)!!
        return doubleArrayOf(rate, rrv)
    }

    private fun findPeaks(x: DoubleArray, distance: Int, height: Double): IntArray {
        val n = x.size
        if (n < 3) return IntArray(0)
        val candidates = ArrayList<Int>()
        var i = 1
        while (i < n - 1) {
            if (x[i] > x[i - 1] && x[i] >= height) {
                var j = i
                while (j + 1 < n && x[j + 1] == x[i]) j++
                if (j + 1 < n && x[j + 1] < x[i]) candidates.add((i + j) / 2)
                i = j + 1
            } else {
                i += 1
            }
        }
        if (distance <= 1 || candidates.isEmpty()) return candidates.toIntArray()
        val byHeight = candidates.sortedWith(compareByDescending<Int> { x[it] }.thenBy { it })
        val keep = HashMap<Int, Boolean>(candidates.size)
        for (c in candidates) keep[c] = true
        for (p in byHeight) {
            if (keep[p] != true) continue
            for (q in candidates) {
                if (q == p || keep[q] != true) continue
                if (abs(q - p) < distance) keep[q] = false
            }
        }
        return candidates.filter { keep[it] == true }.toIntArray()
    }

    private fun classifyEpochs(features: List<EpochFeatures>): MutableList<String> {
        val anyCk = features.any { it.ckSleep }
        val sleepFeats = if (anyCk) features.filter { it.ckSleep } else features
        val hrLo = pct(sleepFeats.map { it.hr }, stageHRLowPct)
        val hrHi = pct(sleepFeats.map { it.hr }, stageHRHighPct)
        val rmssdHi = pct(sleepFeats.map { it.rmssd }, stageHRVHighPct)
        val hrvarHi = pct(sleepFeats.map { it.hrVar }, stageHRVarHighPct)
        val rrvHi = pct(sleepFeats.map { it.rrv }, stageRRVHighPct)
        val rrvLo = pct(sleepFeats.map { it.rrv }, stageRRVLowPct)
        val cardiacSparse = isCardiacSparse(sleepFeats)
        return features.map {
            classifyOne(it, hrLo, hrHi, rmssdHi, hrvarHi, rrvHi, rrvLo, cardiacSparse)
        }.toMutableList()
    }

    private fun pct(values: List<Double>, pct: Double): Double? {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return null
        return percentileSorted(finite.sorted(), pct)
    }

    private fun isCardiacSparse(sleepFeats: List<EpochFeatures>): Boolean {
        if (sleepFeats.isEmpty()) return false
        var missing = 0
        for (f in sleepFeats) if (!f.rmssd.isFinite()) missing++
        return missing >= cardiacSparseEpochFrac * sleepFeats.size
    }

    private fun classifyOne(
        f: EpochFeatures,
        hrLo: Double?,
        hrHi: Double?,
        rmssdHi: Double?,
        hrvarHi: Double?,
        rrvHi: Double?,
        rrvLo: Double?,
        cardiacSparse: Boolean,
    ): String {
        val hasHR = f.hr.isFinite()
        val hrLow = hasHR && hrLo != null && f.hr <= hrLo
        val hrHigh = hasHR && hrHi != null && f.hr >= hrHi
        val parasympOK = !f.rmssd.isFinite() || (rmssdHi != null && f.rmssd >= rmssdHi)
        val hrvarHigh = f.hrVar.isFinite() && hrvarHi != null && f.hrVar >= hrvarHi
        val cardiacActivated = hrHigh || hrvarHigh
        val cardiacActivatedForWake = if (cardiacSparse) hrHigh else cardiacActivated
        val rrvIrregular = f.rrv.isFinite() && rrvHi != null && f.rrv >= rrvHi
        val rrvRegular = !f.rrv.isFinite() || (rrvLo != null && f.rrv <= rrvLo)
        val still = f.moveFrac <= stageStillMoveFrac
        val moving = f.moveFrac >= stageWakeMoveFrac
        if (moving && (cardiacActivatedForWake || !hasHR)) return "wake"
        if (still && parasympOK && hrLow && rrvRegular) return "deep"
        if (still && cardiacActivated && rrvIrregular) return "rem"
        if (still && cardiacActivated && !f.rrv.isFinite()) return "rem"
        return "light"
    }

    private fun smoothLabels(labels: MutableList<String>, window: Int = smoothEpochs): MutableList<String> {
        val n = labels.size
        if (n == 0 || window <= 1) return labels
        var w = window
        if (w % 2 == 0) w += 1
        val half = w / 2
        val out = labels.toMutableList()
        for (i in 0 until n) {
            val lo = max(0, i - half)
            val hi = min(n, i + half + 1)
            val counts = LinkedHashMap<String, Int>()
            val order = ArrayList<String>()
            for (k in lo until hi) {
                val s = labels[k]
                if (!counts.containsKey(s)) order.add(s)
                counts[s] = (counts[s] ?: 0) + 1
            }
            var best = 0
            for (v in counts.values) if (v > best) best = v
            val winners = order.filter { counts[it] == best }
            out[i] = if (labels[i] in winners) labels[i] else winners.first()
        }
        return out
    }

    private fun reimposePhysiology(
        labels: MutableList<String>,
        features: List<EpochFeatures>,
        onsetIdx: Int,
        finalWakeIdx: Int,
    ): MutableList<String> {
        val noREMEpochs = roundHalfAwayFromZero(noREMAfterOnsetMin * 60.0 / epochS).toInt()
        var hasEarlyDeep = false
        for (i in labels.indices) {
            if (labels[i] == "deep" && features[i].clock <= deepFirstFraction) {
                hasEarlyDeep = true
                break
            }
        }
        val out = labels.toMutableList()
        for (i in labels.indices) {
            if (i < onsetIdx || i > finalWakeIdx) continue
            val f = features[i]
            if (out[i] == "rem" && (i - onsetIdx) < noREMEpochs) out[i] = "light"
            if (out[i] == "deep" && f.clock > deepFirstFraction && hasEarlyDeep) out[i] = "light"
        }
        return out
    }

    private fun mergeFragments(
        labels: MutableList<String>,
        thresholdEpochs: Int = fragmentMergeEpochs,
    ): MutableList<String> {
        fun depth(s: String): Int = when (s) {
            "deep" -> 3
            "rem" -> 2
            "light" -> 1
            else -> 0
        }
        val n = labels.size
        if (n == 0 || thresholdEpochs <= 1) return labels
        val runs = ArrayList<RunLen>()
        var i0 = 0
        while (i0 < n) {
            var j = i0
            while (j < n && labels[j] == labels[i0]) j++
            runs.add(RunLen(labels[i0], j - i0))
            i0 = j
        }
        if (runs.size < 2) return labels
        val merged = ArrayList<RunLen>()
        var i = 0
        while (i < runs.size) {
            val cur = runs[i]
            val curLen = cur.len
            if (curLen >= thresholdEpochs) {
                merged.add(RunLen(cur.stage, curLen))
                i += 1
                continue
            }
            val hasPrev = merged.isNotEmpty()
            val hasNext = i + 1 < runs.size
            if (hasPrev && hasNext && merged.last().stage == runs[i + 1].stage) {
                merged.last().len = merged.last().len + curLen + runs[i + 1].len
                i += 2
            } else if (hasPrev && hasNext) {
                val prev = merged.last()
                val next = runs[i + 1]
                val prevLen = prev.len
                val nextLen = next.len
                val winner = when {
                    prevLen > nextLen -> prev.stage
                    nextLen > prevLen -> next.stage
                    else -> if (depth(prev.stage) <= depth(next.stage)) prev.stage else next.stage
                }
                if (winner == prev.stage) {
                    merged.last().len = prevLen + curLen
                    i += 1
                } else {
                    runs[i + 1] = RunLen(next.stage, nextLen + curLen)
                    i += 1
                }
            } else if (hasNext) {
                runs[i + 1] = RunLen(runs[i + 1].stage, runs[i + 1].len + curLen)
                i += 1
            } else if (hasPrev) {
                merged.last().len = merged.last().len + curLen
                i += 1
            } else {
                merged.add(RunLen(cur.stage, curLen))
                i += 1
            }
        }
        val out = ArrayList<String>(n)
        for (r in merged) repeat(r.len) { out.add(r.stage) }
        return out
    }

    private fun buildSegments(labels: List<String>, grid: EpochGrid, end: Int): List<StageSegment> {
        val segments = ArrayList<StageSegment>()
        for (i in labels.indices) {
            val stage = labels[i]
            val segStart = roundHalfAwayFromZero(grid.edges[i]).toInt()
            val segEnd = roundHalfAwayFromZero(grid.edges[i + 1]).toInt()
            if (segments.isNotEmpty() && segments.last().stage == stage) {
                segments[segments.lastIndex] = StageSegment(segments.last().start, segEnd, stage)
            } else {
                segments.add(StageSegment(segStart, segEnd, stage))
            }
        }
        if (segments.isNotEmpty()) {
            segments[segments.lastIndex] = StageSegment(segments.last().start, end, segments.last().stage)
        }
        return segments
    }

    private val v2StageNames = listOf("deep", "rem", "light", "awake")
    private const val v2PadLo = 330
    private const val v2PadHi = 390
    private const val v2DeepGateThresh = 0.20
    private const val v2DeepGateSlope = 5.0
    private const val v2JerkFloorMoveMult = 38.0
    private const val v2JerkFloorGateMult = 55.0
    private const val v2MotionGateBoost = 2.0
    private const val v2RespWeight = 0.6

    private fun v2BaseLogPrior(): Map<String, Double> = mapOf(
        "light" to ln(0.50),
        "deep" to ln(0.18),
        "rem" to ln(0.22),
        "awake" to ln(0.10),
    )

    private val v2Transition: Map<String, Map<String, Double>> = mapOf(
        "deep" to mapOf("deep" to 0.90, "rem" to 0.005, "light" to 0.09, "awake" to 0.005),
        "rem" to mapOf("deep" to 0.005, "rem" to 0.88, "light" to 0.10, "awake" to 0.015),
        "light" to mapOf("deep" to 0.06, "rem" to 0.06, "light" to 0.85, "awake" to 0.03),
        "awake" to mapOf("deep" to 0.01, "rem" to 0.02, "light" to 0.27, "awake" to 0.70),
    )

    private fun v2CyclePrior(c: Double): Map<String, Double> = mapOf(
        "deep" to 1.2 * max(0.0, 1.0 - c / 0.55),
        "rem" to 1.0 * c - (if (c < 0.12) 3.0 else 0.0),
        "light" to 0.0,
        "awake" to 0.0,
    )

    private fun stageSessionV2(
        start: Int,
        end: Int,
        grav: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs>,
    ): List<StageSegment> {
        val lo = start - v2PadLo
        val hi = end + v2PadHi
        val gravW = grav.filter { it.valid && it.ts >= lo && it.ts < hi }
        val hrW = hr.filter { it.ts >= lo && it.ts < hi }
        val rrW = rr.filter { it.ts >= lo && it.ts < hi }
        val feats = v2Features(start, end, gravW, hrW, rrW)
        if (feats.isEmpty()) return listOf(StageSegment(start, end, "light"))
        val labels = v2StageEpochs(feats)
        val segments = ArrayList<StageSegment>()
        for (i in feats.indices) {
            val stage = if (labels[i] == "awake") "wake" else labels[i]
            val segStart = if (i == 0) start else feats[i].start
            val segEnd = if (i == feats.lastIndex) end else feats[i + 1].start
            if (segments.isNotEmpty() && segments.last().stage == stage) {
                segments[segments.lastIndex] = StageSegment(segments.last().start, segEnd, stage)
            } else {
                segments.add(StageSegment(segStart, segEnd, stage))
            }
        }
        return segments
    }

    private fun v2Features(
        start: Int,
        end: Int,
        grav: List<GravTs>,
        hr: List<HrTs>,
        rr: List<RrTs>,
    ): List<V2Epoch> {
        if (end <= start) return emptyList()
        val span = max(1, end - start).toDouble()
        val secHRsum = HashMap<Int, Double>()
        val secHRcnt = HashMap<Int, Int>()
        val secGx = HashMap<Int, Double>()
        val secGy = HashMap<Int, Double>()
        val secGz = HashMap<Int, Double>()
        val secGcnt = HashMap<Int, Int>()
        val rrBy = HashMap<Int, ArrayList<Double>>()
        for (h in hr) {
            secHRsum[h.ts] = (secHRsum[h.ts] ?: 0.0) + h.bpm
            secHRcnt[h.ts] = (secHRcnt[h.ts] ?: 0) + 1
        }
        for (g in grav) {
            secGx[g.ts] = (secGx[g.ts] ?: 0.0) + g.x
            secGy[g.ts] = (secGy[g.ts] ?: 0.0) + g.y
            secGz[g.ts] = (secGz[g.ts] ?: 0.0) + g.z
            secGcnt[g.ts] = (secGcnt[g.ts] ?: 0) + 1
        }
        for (r in rr) {
            val list = rrBy.getOrPut(r.ts) { ArrayList() }
            list.add(r.rrMs)
        }
        fun secHR(s: Int): Double? {
            val c = secHRcnt[s] ?: return null
            return secHRsum[s]!! / c
        }
        fun secG(s: Int): DoubleArray? {
            val c = secGcnt[s] ?: return null
            val cd = c.toDouble()
            return doubleArrayOf(secGx[s]!! / cd, secGy[s]!! / cd, secGz[s]!! / cd)
        }
        val hrKeys = secHRcnt.keys.toList()
        val gridLo = if (hrKeys.isEmpty()) 0 else hrKeys.min()
        val gridHi = if (hrKeys.isEmpty()) -1 else hrKeys.max()
        val size = if (gridHi >= gridLo) (gridHi - gridLo + 2) else 1
        val pCnt = IntArray(size)
        val pSum = DoubleArray(size)
        val pSq = DoubleArray(size)
        if (gridHi >= gridLo) {
            for (i in gridLo..gridHi) {
                val idx = i - gridLo
                val v = secHR(i)
                pCnt[idx + 1] = pCnt[idx] + if (v != null) 1 else 0
                pSum[idx + 1] = pSum[idx] + (v ?: 0.0)
                pSq[idx + 1] = pSq[idx] + if (v != null) v * v else 0.0
            }
        }
        fun stdOfSeconds(loS: Int, hiS: Int): Double? {
            if (gridHi < gridLo) return null
            val a = max(loS, gridLo) - gridLo
            val b = min(hiS, gridHi + 1) - gridLo
            if (b <= a) return null
            val cnt = pCnt[b] - pCnt[a]
            if (cnt < 2) return null
            val n = cnt.toDouble()
            val sv = pSum[b] - pSum[a]
            val sq = pSq[b] - pSq[a]
            val m = sv / n
            val variance = (sq - 2 * m * sv + n * m * m) / n
            return sqrt(max(variance, 0.0))
        }
        val firstE = ((start + 29) / 30) * 30
        val raws = ArrayList<V2Raw>()
        val allJerks = ArrayList<Double>()
        var e = firstE
        while (e < end) {
            val hrs = ArrayList<Double>()
            val gseq = ArrayList<DoubleArray>()
            for (s in e until e + 30) {
                val v = secHR(s)
                if (v != null) hrs.add(v)
                val g = secG(s)
                if (g != null) gseq.add(g)
            }
            if (hrs.isEmpty() && gseq.isEmpty()) {
                e += 30
                continue
            }
            val jerks = ArrayList<Double>()
            for (i in 1 until gseq.size) {
                val dx = gseq[i - 1][0] - gseq[i][0]
                val dy = gseq[i - 1][1] - gseq[i][1]
                val dz = gseq[i - 1][2] - gseq[i][2]
                jerks.add(sqrt(dx * dx + dy * dy + dz * dz))
            }
            allJerks.addAll(jerks)
            var jerkMax = 0.0
            for (j in jerks) if (j > jerkMax) jerkMax = j
            val hrMean = if (hrs.isEmpty()) null else hrs.sum() / hrs.size
            raws.add(
                V2Raw(
                    start = e,
                    hr = hrMean,
                    hrVar = stdOfSeconds(e - 150, e + 30 + 150),
                    hrFlat11 = stdOfSeconds(e - 330, e + 30 + 360),
                    jerks = jerks,
                    gapSec = max(1, gseq.size - 1),
                    jerkMax = jerkMax,
                    respReg = v2RespRegularity(beatsAround(rrBy, e)),
                    clock = (e + 15 - start) / span,
                ),
            )
            e += 30
        }
        val jerkScale = if (allJerks.isEmpty()) {
            1e-6
        } else {
            val s = allJerks.sorted()
            val nn = s.size
            if (nn % 2 == 1) s[nn / 2] else 0.5 * (s[nn / 2 - 1] + s[nn / 2])
        }
        val moveThr = jerkScale * v2JerkFloorMoveMult
        val feats = ArrayList<V2Epoch>(raws.size)
        for (r in raws) {
            var moves = 0
            for (j in r.jerks) if (j > moveThr) moves++
            feats.add(
                V2Epoch(
                    start = r.start,
                    hr = r.hr,
                    hrVar = r.hrVar,
                    hrFlat11 = r.hrFlat11,
                    moveFrac = moves.toDouble() / r.gapSec,
                    jerkMax = r.jerkMax,
                    respReg = r.respReg,
                    clock = r.clock,
                    jerkScale = jerkScale,
                ),
            )
        }
        return feats
    }

    private fun beatsAround(rrBy: Map<Int, List<Double>>, e: Int): List<DoubleArray> {
        val beats = ArrayList<DoubleArray>()
        for (s in (e - 90) until (e + 120)) {
            val list = rrBy[s] ?: continue
            for (v in list) beats.add(doubleArrayOf(s.toDouble(), clampD(v, 300.0, 2000.0)))
        }
        beats.sortWith(compareBy<DoubleArray> { it[0] }.thenBy { it[1] })
        return beats
    }

    private fun v2RespRegularity(beats: List<DoubleArray>): Double? {
        if (beats.size < 12) return null
        val t0 = beats.first()[0]
        val tN = beats.last()[0]
        if (tN <= t0) return null
        val n = ceil((tN - t0) / 0.25 - 1e-9).toInt()
        if (n < 16) return null
        val y = DoubleArray(n)
        var seg = 0
        for (i in 0 until n) {
            val t = t0 + 0.25 * i
            while (seg < beats.size - 2 && beats[seg + 1][0] < t) seg++
            val ta = beats[seg][0]
            val tb = beats[seg + 1][0]
            val va = beats[seg][1]
            val vb = beats[seg + 1][1]
            y[i] = if (tb <= ta) va else va + clampD((t - ta) / (tb - ta), 0.0, 1.0) * (vb - va)
        }
        val meanV = y.sum() / n
        for (i in 0 until n) y[i] -= meanV
        val kLo = ceil(0.15 * 0.25 * n).toInt()
        val kHi = floor(0.40 * 0.25 * n).toInt()
        if (kHi < kLo || kLo < 0) return null
        var maxP = 0.0
        var sumP = 0.0
        for (k in kLo..kHi) {
            val w = -2 * PI * k / n
            var re = 0.0
            var im = 0.0
            for (j in 0 until n) {
                re += y[j] * cos(w * j)
                im += y[j] * sin(w * j)
            }
            val p = re * re + im * im
            sumP += p
            if (p > maxP) maxP = p
        }
        if (sumP == 0.0) return null
        return maxP / sumP
    }

    private fun v2StageEpochs(feats: List<V2Epoch>): List<String> {
        if (feats.isEmpty()) return emptyList()
        fun zfun(vals: List<Double?>): (Double?) -> Double {
            val present = vals.filterNotNull()
            if (present.isEmpty()) return { _ -> 0.0 }
            val m = mean(present)!!
            val sd0 = stddevPop(present)!!
            val sd = if (sd0 == 0.0) 1.0 else sd0
            return { v -> if (v == null) 0.0 else (v - m) / sd }
        }
        val zhr = zfun(feats.map { it.hr })
        val zhv = zfun(feats.map { it.hrVar })
        val zmv = zfun(feats.map { it.moveFrac })
        val zrg = zfun(feats.map { it.respReg })
        val fsorted = feats.mapNotNull { it.hrFlat11 }.sorted()
        fun fpct(v: Double?): Double {
            if (v == null || fsorted.isEmpty()) return 0.5
            var loi = 0
            var hii = fsorted.size
            while (loi < hii) {
                val mid = (loi + hii) / 2
                if (fsorted[mid] <= v) loi = mid + 1 else hii = mid
            }
            return loi.toDouble() / fsorted.size
        }
        val base = v2BaseLogPrior()
        val seq = ArrayList<Map<String, Double>>(feats.size)
        for (f in feats) {
            val zhrv = zhr(f.hr)
            val zhvv = zhv(f.hrVar)
            val zmvv = zmv(f.moveFrac)
            val gate = v2DeepGateSlope * max(0.0, fpct(f.hrFlat11) - v2DeepGateThresh)
            val em = HashMap<String, Double>()
            em["deep"] = -1.4 * zhvv - 0.2 * zhrv - 0.3 * zmvv - gate + base.getValue("deep")
            em["rem"] = 0.6 * zhvv - 0.6 * zmvv + 0.4 * zhrv + base.getValue("rem")
            em["light"] = base.getValue("light")
            em["awake"] = 1.0 * zmvv + 0.8 * zhvv + 0.4 * zhrv + base.getValue("awake")
            val pr = v2CyclePrior(f.clock)
            for (s in v2StageNames) em[s] = em.getValue(s) + pr.getValue(s)
            if (f.jerkMax > f.jerkScale * v2JerkFloorGateMult) {
                em["awake"] = em.getValue("awake") + v2MotionGateBoost
            }
            if (f.respReg != null) {
                val z = zrg(f.respReg)
                em["deep"] = em.getValue("deep") + v2RespWeight * z
                em["rem"] = em.getValue("rem") - v2RespWeight * z
            }
            seq.add(em)
        }
        return v2Viterbi(seq)
    }

    private fun v2Viterbi(emSeq: List<Map<String, Double>>): List<String> {
        if (emSeq.isEmpty()) return emptyList()
        val logT = HashMap<String, Map<String, Double>>()
        for (from in v2StageNames) {
            val row = HashMap<String, Double>()
            for (to in v2StageNames) row[to] = ln(v2Transition.getValue(from).getValue(to))
            logT[from] = row
        }
        var v = HashMap(emSeq[0])
        val back = ArrayList<Map<String, String>>()
        for (t in 1 until emSeq.size) {
            val newV = HashMap<String, Double>()
            val bp = HashMap<String, String>()
            for (s in v2StageNames) {
                var bestPrev = v2StageNames[0]
                var bestVal = v.getValue(bestPrev) + logT.getValue(bestPrev).getValue(s)
                for (p in v2StageNames.drop(1)) {
                    val `val` = v.getValue(p) + logT.getValue(p).getValue(s)
                    if (`val` > bestVal) {
                        bestVal = `val`
                        bestPrev = p
                    }
                }
                newV[s] = bestVal + emSeq[t].getValue(s)
                bp[s] = bestPrev
            }
            v = newV
            back.add(bp)
        }
        var last = v2StageNames[0]
        var lastV = v.getValue(last)
        for (s in v2StageNames.drop(1)) {
            if (v.getValue(s) > lastV) {
                lastV = v.getValue(s)
                last = s
            }
        }
        val path = ArrayList<String>()
        path.add(last)
        for (bp in back.asReversed()) {
            last = bp.getValue(last)
            path.add(last)
        }
        path.reverse()
        return path
    }

    private const val RR_MIN_MS = 300.0
    private const val RR_MAX_MS = 2000.0

    private fun rangeFilter(rr: List<Double>): List<Double> {
        val out = ArrayList<Double>()
        for (v in rr) if (v >= RR_MIN_MS && v <= RR_MAX_MS) out.add(v)
        return out
    }

    private fun rmssdRaw(nn: List<Double>): Double? {
        if (nn.size < 2) return null
        var sumSq = 0.0
        for (i in 1 until nn.size) {
            val d = nn[i] - nn[i - 1]
            sumSq += d * d
        }
        return sqrt(sumSq / (nn.size - 1))
    }

    private fun clampD(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))
}

private class Period(val stage: String, val start: Int, val end: Int)

private class EpochGrid(
    val edges: DoubleArray,
    val nEpochs: Int,
    val counts: DoubleArray,
    val hr: DoubleArray,
    val moveFrac: DoubleArray,
    val rrBuckets: List<List<Double>>,
    val respBuckets: List<List<Double>>,
    val ckFlags: BooleanArray,
)

private class EpochFeatures(
    val index: Int,
    val midTs: Double,
    val moveFrac: Double,
    val ckSleep: Boolean,
    val hr: Double,
    val hrVar: Double,
    val rmssd: Double,
    val sdnn: Double,
    val respRate: Double,
    val rrv: Double,
    val clock: Double,
)

private class RunLen(val stage: String, var len: Int)

private class V2Raw(
    val start: Int,
    val hr: Double?,
    val hrVar: Double?,
    val hrFlat11: Double?,
    val jerks: List<Double>,
    val gapSec: Int,
    val jerkMax: Double,
    val respReg: Double?,
    val clock: Double,
)

private class V2Epoch(
    val start: Int,
    val hr: Double?,
    val hrVar: Double?,
    val hrFlat11: Double?,
    val moveFrac: Double,
    val jerkMax: Double,
    val respReg: Double?,
    val clock: Double,
    val jerkScale: Double,
)

/**
 * Sleep-session windowed RMSSD used only as [SleepSession.avgHrv].
 * Ported from the call inside advanced_stager (hrv_time.dart's
 * sleepSessionWindowedRmssd): range + Malik runs, 5-min mean, ACF1 jitter gate.
 * Returns null when the headline is refused.
 */
private fun sleepSessionWindowedRmssd(
    rrMs: DoubleArray,
    rrTsMs: DoubleArray,
    startSec: Int,
    endSec: Int,
    windowSec: Int = 300,
    minDiffsPerWindow: Int = 20,
): Double? {
    if (startSec <= 0 || endSec <= startSec || rrMs.isEmpty() || rrMs.size != rrTsMs.size) return null
    val buckets = HashMap<Int, ArrayList<Double>>()
    val bucketsTs = HashMap<Int, ArrayList<Double>>()
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
    if (buckets.isEmpty()) return null
    if (rrOverCounted(inRr, inTs)) return null
    val rmssds = ArrayList<Double>()
    val runs = ArrayList<List<Double>>()
    val indices = buckets.keys.sorted()
    for (idx in indices) {
        val winRr = buckets.getValue(idx)
        val winTs = bucketsTs.getValue(idx)
        val winStart = startSec + idx * windowSec
        val winSec = min(winStart + windowSec, endSec) - winStart
        var bankedMs = 0.0
        var lo = 0
        for (i in winRr.indices) {
            if (winTs[i] < winTs[lo]) lo = i
            if (rrCoverageCounts(winRr[i])) bankedMs += winRr[i]
        }
        val overhangMs = if (rrCoverageCounts(winRr[lo])) winRr[lo] else 0.0
        if (bankedMs > 1.10 * (winSec * 1000.0 + overhangMs)) continue
        val rrRuns = cleanWindowRuns(winRr, winTs).filter { it.size >= 2 }
        val diffRuns = rrRuns.map { r -> List(r.size - 1) { i -> r[i + 1] - r[i] } }
        var ssd = 0.0
        var nd = 0
        for (r in diffRuns) {
            for (d in r) {
                ssd += d * d
                nd++
            }
        }
        if (nd < minDiffsPerWindow) continue
        runs.addAll(diffRuns)
        rmssds.add(sqrt(ssd / nd))
    }
    if (rmssds.isEmpty()) return null
    val acf1 = nnDiffAcf1(runs)
    if (acf1 != null && acf1 < -0.35) return null
    return mean(rmssds)
}

private fun rrCoverageCounts(rrMs: Double): Boolean = rrMs in 300.0..2400.0

private fun rrOverCounted(rrMs: List<Double>, rrTsMs: List<Double>): Boolean {
    if (rrMs.size < 2 || rrMs.size != rrTsMs.size) return false
    var lo = 0
    var hi = 0
    for (i in 1 until rrTsMs.size) {
        if (rrTsMs[i] < rrTsMs[lo]) lo = i
        if (rrTsMs[i] > rrTsMs[hi]) hi = i
    }
    val first = rrMs[lo]
    val spanSec = (rrTsMs[hi] - rrTsMs[lo] + (if (rrCoverageCounts(first)) first else 0.0)) / 1000.0
    if (!spanSec.isFinite() || spanSec < 600.0) return false
    var sum = 0.0
    for (v in rrMs) if (rrCoverageCounts(v)) sum += v
    return (sum / 1000.0) / spanSec > 1.10
}

private fun nnDiffAcf1(diffRuns: List<List<Double>>): Double? {
    var n = 0
    var sum = 0.0
    for (r in diffRuns) {
        for (d in r) {
            sum += d
            n++
        }
    }
    if (n < 30) return null
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

private fun cleanWindowRuns(rr: List<Double>, ts: List<Double>): List<List<Double>> {
    val radius = 2
    val threshold = 0.20
    val nn = ArrayList<Double>()
    val at = ArrayList<Int>()
    val nnTs = ArrayList<Double>()
    for (i in rr.indices) {
        if (rr[i] in 300.0..2000.0) {
            nn.add(rr[i])
            at.add(i)
            nnTs.add(ts[i])
        }
    }
    val runs = ArrayList<ArrayList<Double>>()
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
