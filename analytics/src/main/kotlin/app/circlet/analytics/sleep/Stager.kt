// Ported from OpenStrap analytics/lib/src/onehz/sleep/stager.dart.
// Webster/Cole-Kripke continuity rescore and NREM/REM architecture consolidation.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import kotlin.math.max

class StagerResult(
    val stages: List<OsStage>,
    val epochSec: Int,
    val wakePct: Double,
    val nremPct: Double,
    val remPct: Double,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "epoch_sec" to epochSec,
        "wake_pct" to round6(wakePct),
        "nrem_pct" to round6(nremPct),
        "rem_pct" to round6(remPct),
        "epochs" to stages.size,
    )
}

/**
 * Test seam for the autonomic Webster rescore. The top bridge is 5 min,
 * widened to absorb the van Hees 5-min mask smear. Not the cardio table.
 */
fun websterRescoreAutonomic(sm: MutableList<OsStage>, epochSec: Int) {
    websterRescore(sm, epochSec)
}

private fun isSleep(s: OsStage): Boolean = s != OsStage.WAKE

/**
 * Webster/Cole-Kripke sleep-continuity rescore, in place, against a snapshot
 * taken before the pass so bridging cannot cascade.
 */
private fun websterRescore(sm: MutableList<OsStage>, epochSec: Int) {
    val n = sm.size
    fun minToEp(m: Double): Double = m * 60.0 / epochSec
    val snap = sm.toList()

    var onset = -1
    var lastSleep = -1
    for (i in 0 until n) {
        if (isSleep(snap[i])) {
            if (onset < 0) onset = i
            lastSleep = i
        }
    }
    if (onset < 0) return

    fun sleepRunBefore(i: Int): Int {
        var c = 0
        var k = i - 1
        while (k >= onset && isSleep(snap[k])) {
            c++
            k--
        }
        return c
    }

    fun sleepRunAfter(i: Int): Int {
        var c = 0
        var k = i + 1
        while (k <= lastSleep && isSleep(snap[k])) {
            c++
            k++
        }
        return c
    }

    val rules = listOf(
        doubleArrayOf(minToEp(15.0), minToEp(5.0)),
        doubleArrayOf(minToEp(10.0), minToEp(3.0)),
        doubleArrayOf(minToEp(4.0), minToEp(1.0)),
    )

    var i = onset
    while (i <= lastSleep) {
        if (isSleep(snap[i])) {
            i++
            continue
        }
        var j = i
        while (j <= lastSleep && !isSleep(snap[j])) {
            j++
        }
        val wakeLen = j - i
        val before = sleepRunBefore(i)
        val after = sleepRunAfter(j - 1)
        val context = max(before, after).toDouble()
        var rescore = false
        for (r in rules) {
            if (context >= r[0] && wakeLen.toDouble() <= r[1]) {
                rescore = true
                break
            }
        }
        if (rescore) {
            for (k in i until j) {
                sm[k] = OsStage.NREM
            }
        }
        i = j
    }
}

/** Minimum sustained REM episode and minimum NREM/REM bout, in minutes. */
const val remEpisodeMinMin = 5.0
const val stageBoutMinMin = 5.0

/**
 * Consolidation of a per-epoch 3-class stream into sustained NREM/REM bouts.
 * Returns a new list. Wake bouts are preserved.
 */
fun consolidateSleepStages(stages: List<OsStage>, epochSec: Int): List<OsStage> {
    val out = stages.toMutableList()
    consolidateStages(out, epochSec)
    return out
}

private fun consolidateStages(sm: MutableList<OsStage>, epochSec: Int) {
    val n = sm.size
    if (n == 0) return
    val remGapEp = roundHalfAwayFromZero(remEpisodeMinMin * 60.0 / epochSec).toInt()
    val minBoutEp = roundHalfAwayFromZero(stageBoutMinMin * 60.0 / epochSec).toInt()
    if (minBoutEp <= 1 && remGapEp <= 1) return

    run {
        val runs = ArrayList<IntArray>()
        var p = 0
        while (p < n) {
            var q = p
            while (q < n && sm[q] == sm[p]) q++
            runs.add(intArrayOf(p, q))
            p = q
        }
        for (ri in runs.indices) {
            val start = runs[ri][0]
            val end = runs[ri][1]
            if (sm[start] != OsStage.NREM) continue
            val gapLen = end - start
            if (gapLen >= remGapEp) continue
            val left = if (ri > 0 && sm[runs[ri - 1][0]] == OsStage.REM) {
                runs[ri - 1][1] - runs[ri - 1][0]
            } else {
                0
            }
            val right = if (ri + 1 < runs.size && sm[runs[ri + 1][0]] == OsStage.REM) {
                runs[ri + 1][1] - runs[ri + 1][0]
            } else {
                0
            }
            if (left > 0 && right > 0 && left + right >= gapLen &&
                left + right + gapLen >= remGapEp
            ) {
                for (k in start until end) sm[k] = OsStage.REM
            }
        }
    }

    var changed = true
    while (changed) {
        changed = false
        val runs = ArrayList<IntArray>()
        var p = 0
        while (p < n) {
            var q = p
            while (q < n && sm[q] == sm[p]) q++
            runs.add(intArrayOf(p, q))
            p = q
        }
        for (ri in runs.indices) {
            val start = runs[ri][0]
            val end = runs[ri][1]
            val stage = sm[start]
            if (!isSleep(stage)) continue
            if ((end - start) >= minBoutEp) continue
            var left: OsStage? = null
            var right: OsStage? = null
            var leftLen = 0
            var rightLen = 0
            if (ri > 0 && isSleep(sm[runs[ri - 1][0]])) {
                left = sm[runs[ri - 1][0]]
                leftLen = runs[ri - 1][1] - runs[ri - 1][0]
            }
            if (ri + 1 < runs.size && isSleep(sm[runs[ri + 1][0]])) {
                right = sm[runs[ri + 1][0]]
                rightLen = runs[ri + 1][1] - runs[ri + 1][0]
            }
            val target: OsStage? = when {
                left != null && right != null -> if (leftLen >= rightLen) left else right
                left != null -> left
                right != null -> right
                else -> null
            }
            if (target == null || target == stage) continue
            for (k in start until end) sm[k] = target
            changed = true
            break
        }
    }
}
