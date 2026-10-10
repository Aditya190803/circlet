// Ported from OpenStrap analytics/lib/src/onehz/sleep/band_offset.dart.
// Band-state corroboration: trim a lie-in after the last continuously observed SLEEP second.

package app.circlet.analytics.sleep

const val kBandStateSleep = 2
const val kBandStateStill = 1
const val kMinBandStateCoveragePct = 80
const val kMinBandTailCoveragePct = 95
const val kMaxBandTailGapSec = 300
const val kMaxBandTailStillSec = 60
const val kMinBandOffsetTrimSec = 600

/**
 * The night's new end (same convention as the chosen group's end), or null.
 *
 * [tsSec] ascending, 1:1 with [bandState]; a state outside 0..3 is absent.
 */
fun bandTrimmedOffsetSec(
    startSec: Int,
    endSec: Int,
    tsSec: List<Int>,
    bandState: List<Int>,
    minNightSec: Int = 3 * 3600,
): Int? {
    if (endSec <= startSec || tsSec.size != bandState.size) return null
    fun known(s: Int): Boolean = s in 0..3

    var lo = 0
    var hi = tsSec.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (tsSec[mid] < startSec) {
            lo = mid + 1
        } else {
            hi = mid
        }
    }
    val first = lo

    var knownSec = 0
    var lastSleepSec: Int? = null
    var lastCounted: Int? = null
    var k = first
    while (k < tsSec.size && tsSec[k] < endSec) {
        if (known(bandState[k])) {
            if (tsSec[k] != lastCounted) {
                knownSec++
                lastCounted = tsSec[k]
            }
            if (bandState[k] == kBandStateSleep) lastSleepSec = tsSec[k]
        }
        k++
    }
    val sleepSec = lastSleepSec ?: return null
    if (knownSec * 100 < (endSec - startSec) * kMinBandStateCoveragePct) return null

    val newEnd = sleepSec + 1
    val tailSec = endSec - newEnd
    if (tailSec < kMinBandOffsetTrimSec) return null
    if (newEnd - startSec < minNightSec) return null

    var tailKnown = 0
    var stillSec = 0
    var maxGap = 0
    var prevKnownTs = sleepSec
    var lastTailCounted: Int? = null
    var lastStillCounted: Int? = null
    k = first
    while (k < tsSec.size && tsSec[k] < endSec) {
        val t = tsSec[k]
        if (t >= newEnd && known(bandState[k])) {
            if (bandState[k] == kBandStateStill && t != lastStillCounted) {
                stillSec++
                lastStillCounted = t
            }
            if (t != lastTailCounted) {
                lastTailCounted = t
                tailKnown++
                val gap = t - prevKnownTs - 1
                if (gap > maxGap) maxGap = gap
                prevKnownTs = t
            }
        }
        k++
    }
    val trailingGap = endSec - 1 - prevKnownTs
    if (trailingGap > maxGap) maxGap = trailingGap
    if (tailKnown * 100 < tailSec * kMinBandTailCoveragePct) return null
    if (maxGap > kMaxBandTailGapSec) return null
    if (stillSec > kMaxBandTailStillSec) return null
    return newEnd
}
