// Ported from OpenStrap analytics/lib/src/onehz/motion/steps.dart — pedometer and StepParams.
package app.circlet.analytics.motion

import kotlin.math.min

/**
 * Step count over one contiguous accelerometer-magnitude signal (g, gravity included).
 *
 * This is OpenStrap's hybrid detector, not Analog Devices AN-2554 verbatim: a trailing
 * moving-average low-pass, centred-window max/min extrema judged against a dynamic threshold
 * with a [SENS] dead zone, AN-2554's 0.2–2.0 s step-interval bounds, and Zhao's
 * [CONFIRM]-steps regularity gate. It reads 0 at rest and rejects irregular handling, but not
 * rhythmic arm work such as stirring. OpenStrap reports 33 % participant error on wrist
 * walking data at 100 Hz (OxWalk), so it is an experimental count, not a validated one.
 *
 * Every constant is in samples except the interval bounds, which use [sampleRateHz].
 */
fun bandPedometer(signal: DoubleArray, sampleRateHz: Double = SAMPLE_RATE_HZ): Int {
    val n = signal.size
    if (n < WINDOW || sampleRateHz <= 0) return 0
    val minGap = MIN_STEP_INTERVAL_S * sampleRateHz
    val maxGap = MAX_STEP_INTERVAL_S * sampleRateHz

    val lp = DoubleArray(n)
    var acc = 0.0
    for (i in 0 until n) {
        acc += signal[i]
        if (i >= FILTER) acc -= signal[i - FILTER]
        lp[i] = acc / min(i + 1, FILTER)
    }

    val half = WINDOW shr 1
    val candIndex = ArrayList<Int>()
    val candIsMax = ArrayList<Boolean>()
    val candValue = ArrayList<Double>()
    for (i in half until n - half) {
        var isMax = true
        var isMin = true
        val v = lp[i]
        for (j in i - half..i + half) {
            if (lp[j] > v) isMax = false
            if (lp[j] < v) isMin = false
            if (!isMax && !isMin) break
        }
        if (isMax) {
            candIndex += i; candIsMax += true; candValue += v
        } else if (isMin) {
            candIndex += i; candIsMax += false; candValue += v
        }
    }

    val dyn = ArrayDeque<Double>()
    var dynVal = signal.average()
    var steps = 0
    var possible = 0
    var regulation = false
    var lookingForMax = true
    var curMax = 0.0
    var curMaxIndex = -1
    var lastStepIndex = -1
    for (k in candIndex.indices) {
        val ci = candIndex[k]
        val cv = candValue[k]
        if (lookingForMax) {
            if (candIsMax[k]) {
                curMax = cv
                curMaxIndex = ci
                lookingForMax = false
            }
            continue
        }
        if (candIsMax[k]) {
            if (cv > curMax) {
                curMax = cv
                curMaxIndex = ci
            }
            continue
        }
        if (ci - curMaxIndex > MAX_MIN_TIMEOUT) {
            lookingForMax = true
            possible = 0
            regulation = false
            lastStepIndex = -1
            continue
        }
        val mx = curMax
        val mn = cv
        if (mx > dynVal + SENS / 2 && mn < dynVal - SENS / 2) {
            val gap = if (lastStepIndex < 0) null else curMaxIndex - lastStepIndex
            lastStepIndex = curMaxIndex
            if (gap != null && (gap < minGap || gap > maxGap)) {
                possible = 0
                regulation = false
                lookingForMax = true
                continue
            }
            if (mx - mn > SENS) {
                dyn.addLast((mx + mn) / 2)
                if (dyn.size > THRESHOLD_ORDER) dyn.removeFirst()
                dynVal = dyn.average()
            }
            possible++
            if (regulation) {
                steps++
            } else if (possible >= CONFIRM) {
                steps += possible
                regulation = true
            }
        } else {
            possible = 0
            regulation = false
        }
        lookingForMax = true
    }
    return steps
}

/** The rate the constants below were tuned at, and the raw stream's rate. */
const val SAMPLE_RATE_HZ = 100.0

private const val FILTER = 8
private const val WINDOW = 33
private const val SENS = 0.10
private const val THRESHOLD_ORDER = 4
private const val CONFIRM = 8
private const val MAX_MIN_TIMEOUT = 120
private const val MIN_STEP_INTERVAL_S = 0.2
private const val MAX_STEP_INTERVAL_S = 2.0
