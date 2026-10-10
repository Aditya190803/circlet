package app.circlet.analytics.workout

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Ported from OpenStrap lib/src/onehz/workout/auto_detect.dart and sport.dart.
// A suggestion, not a saved workout: heart rate at or above
// max(RHR+40, RHR+0.45·HRR) for at least 12 minutes, dips and gaps of at most
// 90 seconds bridged, windows under 5 minutes apart merged, nothing longer
// than 120 minutes. The 1 Hz bundle cannot type a sport (Mannini HAR needs
// ~100 Hz), so the default label is "detected".

internal const val DEFAULT_SPORT_LABEL = "detected"

internal data class DetectedWorkout(
    val startSec: Int,
    val endSec: Int,
    val avgBpm: Int,
    val peakBpm: Int,
    val durationMin: Int,
    val sport: String = DEFAULT_SPORT_LABEL,
)

internal data class SavedSpan(val startSec: Int, val endSec: Int)

internal data class MotionPoint(val ts: Int, val intensity: Double)

internal data class WorkoutBout(
    val startSec: Int,
    val endSec: Int,
    val avgBpm: Double,
    val peakBpm: Double,
    val durationS: Double,
)

internal data class MotionFeatures(
    val meanIntensity: Double,
    val cadenceHz: Double? = null,
    val enmo: Double? = null,
)

internal typealias SportClassifier = (WorkoutBout, MotionFeatures?) -> String

internal fun defaultSportClassifier(bout: WorkoutBout, feats: MotionFeatures?): String = DEFAULT_SPORT_LABEL

internal object AutoWorkoutDetector {
    const val ELEVATED_MARGIN_BPM = 40
    const val HRR_FLOOR_FRACTION = 0.45
    const val DEFAULT_MAX_HR = 190
    const val MAX_WORKOUT_MIN = 120
    const val MIN_SUSTAINED_MIN = 12.0
    const val MAX_DIP_S = 90
    const val MAX_GAP_S = 90
    const val MERGE_GAP_S = 5 * 60
    const val MOTION_CONFIRM_MEAN = 0.15
    const val ONSET_LOOKBACK_S = 180
    const val ONSET_WINDOW_S = 180
    const val ONSET_RISE_BPM = 25.0
    const val DEFAULT_RESTING_HR = 60

    fun motionPoints(gravTs: List<Int>, gx: List<Double>, gy: List<Double>, gz: List<Double>): List<MotionPoint> {
        val n = gravTs.size
        if (n == 0) return emptyList()
        val idx = gravTs.indices.sortedBy { gravTs[it] }
        val out = ArrayList<MotionPoint>(n)
        var px: Double? = null
        var py = 0.0
        var pz = 0.0
        for (k in 0 until n) {
            val i = idx[k]
            val intensity = if (k == 0 || px == null) {
                0.0
            } else {
                val dx = gx[i] - px
                val dy = gy[i] - py
                val dz = gz[i] - pz
                sqrt(dx * dx + dy * dy + dz * dz)
            }
            out.add(MotionPoint(gravTs[i], intensity))
            px = gx[i]
            py = gy[i]
            pz = gz[i]
        }
        return out
    }

    fun detect(
        hrTs: List<Int>,
        hrBpm: List<Int>,
        restingBpm: Int? = null,
        maxBpm: Int? = null,
        motion: List<MotionPoint>? = null,
        savedSpans: List<SavedSpan> = emptyList(),
        classify: SportClassifier = ::defaultSportClassifier,
    ): List<DetectedWorkout> {
        val n = hrTs.size
        if (n == 0) return emptyList()
        val order = hrTs.indices.sortedBy { hrTs[it] }
        val ts = IntArray(n) { hrTs[order[it]] }
        val bpm = IntArray(n) { hrBpm[order[it]] }
        val rhr = restingBpm ?: DEFAULT_RESTING_HR
        val hrMax = maxBpm ?: DEFAULT_MAX_HR
        val hrrFloor = (rhr + HRR_FLOOR_FRACTION * (hrMax - rhr)).roundToInt()
        val floor = max(rhr + ELEVATED_MARGIN_BPM, hrrFloor)

        val spans = ArrayList<IntArray>()
        var spanStart: Int? = null
        var spanEnd = 0
        var dipStart: Int? = null
        fun closeSpan() {
            val start = spanStart
            if (start != null && (spanEnd - start).toDouble() >= MIN_SUSTAINED_MIN * 60.0) {
                spans.add(intArrayOf(start, spanEnd))
            }
            spanStart = null
            dipStart = null
        }
        for (k in 0 until n) {
            if (k > 0 && spanStart != null && ts[k] - ts[k - 1] > MAX_GAP_S) closeSpan()
            if (bpm[k] >= floor) {
                if (spanStart == null) spanStart = ts[k]
                spanEnd = ts[k]
                dipStart = null
            } else if (spanStart != null) {
                if (dipStart == null) dipStart = ts[k]
                if (ts[k] - dipStart!! > MAX_DIP_S) closeSpan()
            }
        }
        closeSpan()
        if (spans.isEmpty()) return emptyList()

        val merged = ArrayList<IntArray>()
        var curStart = spans[0][0]
        var curEnd = spans[0][1]
        for (k in 1 until spans.size) {
            val next = spans[k]
            if (next[0] - curEnd < MERGE_GAP_S) {
                curEnd = max(curEnd, next[1])
            } else {
                merged.add(intArrayOf(curStart, curEnd))
                curStart = next[0]
                curEnd = next[1]
            }
        }
        merged.add(intArrayOf(curStart, curEnd))

        val motionSeries = if (motion.isNullOrEmpty()) null else motion
        val results = ArrayList<DetectedWorkout>()
        for (span in merged) {
            val start = span[0]
            val end = span[1]
            if ((end - start) > MAX_WORKOUT_MIN * 60) continue
            if (savedSpans.any { overlaps(start, end, it.startSec, it.endSec) }) continue
            val win = ArrayList<Int>()
            for (k in 0 until n) {
                if (ts[k] in start..end) win.add(bpm[k])
            }
            if (win.isEmpty()) continue
            val winMean = win.sum().toDouble() / win.size
            if (winMean < floor) continue
            var meanMotion: Double? = null
            if (motionSeries != null) {
                var sum = 0.0
                var cnt = 0
                for (p in motionSeries) {
                    if (p.ts in start..end) {
                        sum += p.intensity
                        cnt++
                    }
                }
                meanMotion = if (cnt == 0) 0.0 else sum / cnt
                if (meanMotion < MOTION_CONFIRM_MEAN) {
                    val pre = meanBpmInRange(ts, bpm, start - ONSET_LOOKBACK_S, start)
                    val early = meanBpmInRange(ts, bpm, start, minOf(end + 1, start + ONSET_WINDOW_S))
                    if (pre == null || early == null || (early - pre) < ONSET_RISE_BPM) continue
                }
            }
            var sum = 0
            var peak = win.first()
            for (v in win) {
                sum += v
                if (v > peak) peak = v
            }
            val avg = (sum.toDouble() / win.size).roundToInt()
            val bout = WorkoutBout(start, end, avg.toDouble(), peak.toDouble(), (end - start).toDouble())
            val feats = meanMotion?.let { MotionFeatures(it) }
            results.add(
                DetectedWorkout(start, end, avg, peak, (end - start) / 60, classify(bout, feats)),
            )
        }
        return results
    }

    private fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart <= bEnd && bStart <= aEnd

    private fun meanBpmInRange(ts: IntArray, bpm: IntArray, lo: Int, hi: Int): Double? {
        var sum = 0
        var cnt = 0
        for (k in ts.indices) {
            if (ts[k] >= lo && ts[k] < hi) {
                sum += bpm[k]
                cnt++
            }
        }
        return if (cnt == 0) null else sum.toDouble() / cnt
    }
}
