// Ported from OpenStrap analytics/lib/src/onehz/sleep/nap.dart.
// Daytime naps: van Hees z-angle immobility plus an HR dip vs the awake baseline (wrist estimate, not PSG).

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.round6

/** Index range [start, end) of the main nocturnal sleep, so [detectNaps] can carve it out. */
data class SleepWindowSpan(val start: Int, val end: Int)

/**
 * One daytime sleep episode. [startSec] / [endSec] are seconds relative to the
 * first supplied sample. [tstSec] is the sleep-need credit, never [tibSec].
 */
data class NapWindow(
    val startSec: Int,
    val endSec: Int,
    /** Full span, brief arousals included. */
    val tibSec: Int,
    /** Seconds inside the bout with no wrist movement. Always ≤ [tibSec]. */
    val tstSec: Int,
    /** In [0.2, 0.85]. Not sleep efficiency. */
    val confidence: Double,
    /**
     * The bout was already in progress at the first sample, so [startSec] is
     * the record edge, not sleep onset. Propagated forward across [napChainGapSec].
     */
    val startsAtRecordEdge: Boolean = false,
) {
    val efficiency: Double get() = if (tibSec <= 0) 0.0 else tstSec.toDouble() / tibSec

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "start_sec" to startSec,
        "end_sec" to endSec,
        "tib_sec" to tibSec,
        "tst_sec" to tstSec,
        "efficiency" to round6(efficiency),
        "confidence" to round6(confidence),
        "starts_at_record_edge" to startsAtRecordEdge,
    )
}

/** Shortest episode reported as a nap. Below this it is rest, not sleep. */
const val minNapSec = 15 * 60

/** Longest episode still called a nap. Exactly this long is kept; longer is not. */
const val maxNapSec = 6 * 3600

/** Brief arousals bridged inside one nap. A gap of exactly this long is not bridged. */
const val napBridgeSec = 5 * 60

/** Median HR inside a nap must sit at or below this multiple of the daytime-awake baseline. */
const val napRestingHrMult = 0.95

/** Below this fraction of seconds with HR, the bout is not judged. */
const val minNapHrCoverage = 0.5

/**
 * Decoded gravity on at least this fraction of the bout's wall-clock seconds.
 * A second the band never measured fails the still test (NaN angle), and this
 * fraction keeps a future bridge change from reopening that hole.
 */
const val minNapAccelCoverage = 0.5

/** Overlap with off-wrist or excluded spans at or above this fraction discards the bout. */
const val maxNapOffWristFraction = 0.5

/**
 * Bouts closer than this are one episode. Used only to propagate deferral
 * backward and the record-edge flag forward. A gap of exactly this long does not chain.
 */
const val napChainGapSec = 60 * 60

/** Awake HR samples needed before any nap is judged. */
const val minAwakeHrSamples = 10 * 60

private val napInputs = listOf("accel_1hz", "hr_1hz")

/**
 * Daytime naps in a 1 Hz day.
 *
 * [accel] and [hr] share a time base; the shorter length wins. [mainSleep] is
 * an index range to carve out. [wristOff] and [exclude] are `[startSec, endSec]`
 * spans in absolute epoch seconds.
 *
 * Absent only when the day cannot be judged (too little data, or no awake HR
 * baseline). A judged day with no nap returns an empty list.
 * Stillness is `deltaDeg < threshold` on contiguous seconds, not `immobile`:
 * the mask marks the start of a sustained window and would drop the last
 * [ImmobilityMask.sustainedSec] of every bout. Sedentary wake stays in the
 * baseline; only the candidate itself and unfinished bouts come out.
 */
fun detectNaps(
    accel: List<AccelSample>,
    hr: List<Double>,
    mainSleep: SleepWindowSpan? = null,
    wristOff: List<List<Int>> = emptyList(),
    exclude: List<List<Int>> = emptyList(),
): OsMetric<List<NapWindow>> {
    val n = minOf(accel.size, hr.size)
    if (n < minNapSec) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = napInputs,
            note = "too little data for nap detection (need ≥15 min)",
        )
    }

    val series = if (accel.size == n) accel else accel.subList(0, n)
    val mask = immobilityMask(series)
    val win = mask.sustainedSec
    val thr = mask.thresholdDeg
    val baseSec = (series[0].tsMs / 1000.0).toInt()
    fun absSec(idx: Int): Int = (series[idx].tsMs / 1000.0).toInt()

    // NaN < thr is false, so an undecoded second is not still. A timestamp
    // jump is not still either: adjacent indices are not adjacent seconds.
    fun stillAt(k: Int): Boolean =
        mask.deltaDeg[k] < thr && (k == 0 || absSec(k) - absSec(k - 1) == 1)

    val runs = ArrayList<MutableList<Int>>()
    var i = 0
    while (i < n) {
        if (!stillAt(i)) {
            i++
            continue
        }
        var j = i
        while (j < n && stillAt(j)) j++
        if (absSec(j - 1) + 1 - absSec(i) >= win) runs.add(mutableListOf(i, j))
        i = j
    }

    val bouts = ArrayList<MutableList<Int>>()
    for (r in runs) {
        if (bouts.isNotEmpty() &&
            absSec(r[0]) - (absSec(bouts.last()[1] - 1) + 1) < napBridgeSec
        ) {
            bouts.last()[1] = r[1]
        } else {
            bouts.add(mutableListOf(r[0], r[1]))
        }
    }

    // A bout that runs off the end of the record has no knowable end, and so
    // does every bout chained to it within napChainGapSec.
    val unfinished = MutableList(bouts.size) { false }
    for (b in bouts.indices.reversed()) {
        if (bouts[b][1] >= n) {
            unfinished[b] = true
            continue
        }
        if (b + 1 < bouts.size &&
            unfinished[b + 1] &&
            absSec(bouts[b + 1][0]) - (absSec(bouts[b][1] - 1) + 1) < napChainGapSec
        ) {
            unfinished[b] = true
        }
    }

    // Mirror of unfinished, walking forward. The bout is still emitted.
    val startsAtEdge = MutableList(bouts.size) { false }
    for (b in bouts.indices) {
        if (b == 0) {
            startsAtEdge[0] = bouts[0][0] == 0
        } else if (startsAtEdge[b - 1] &&
            absSec(bouts[b][0]) - (absSec(bouts[b - 1][1] - 1) + 1) < napChainGapSec
        ) {
            startsAtEdge[b] = true
        }
    }

    val deferredSec = MutableList(n) { false }
    for (b in bouts.indices) {
        if (!unfinished[b]) continue
        for (k in bouts[b][0] until bouts[b][1]) deferredSec[k] = true
    }
    val awakeIdx = ArrayList<Int>()
    for (k in 0 until n) {
        if (hr[k] <= 0.0 || deferredSec[k]) continue
        if (mainSleep != null && k >= mainSleep.start && k < mainSleep.end) continue
        awakeIdx.add(k)
    }
    if (awakeIdx.size < minAwakeHrSamples) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = napInputs,
            note = "not enough awake daytime HR to set a baseline " +
                "(${awakeIdx.size}s, need ${minAwakeHrSamples}s) — " +
                "cannot corroborate stillness as sleep",
        )
    }

    val naps = ArrayList<NapWindow>()
    var deferred = 0
    var unverifiable = 0
    var offWrist = 0
    var awakeStill = 0
    var outOfRange = 0
    var inMainSleep = 0
    var noBaseline = 0
    var unobserved = 0

    for (bi in bouts.indices) {
        val start = bouts[bi][0]
        val end = bouts[bi][1]
        if (unfinished[bi]) {
            deferred++
            continue
        }

        val aStart = absSec(start)
        val aEnd = absSec(end - 1) + 1
        val tib = aEnd - aStart
        if (tib < minNapSec || tib > maxNapSec) {
            outOfRange++
            continue
        }
        if (mainSleep != null && start < mainSleep.end && end > mainSleep.start) {
            inMainSleep++
            continue
        }

        var accelSec = 0
        for (k in start until end) {
            if (series[k].valid) accelSec++
        }
        if (accelSec.toDouble() / tib < minNapAccelCoverage) {
            unobserved++
            continue
        }

        val offFrac = overlapFraction(aStart, aEnd, wristOff)
        val exFrac = overlapFraction(aStart, aEnd, exclude)
        if (offFrac >= maxNapOffWristFraction || exFrac >= maxNapOffWristFraction) {
            offWrist++
            continue
        }

        val boutHr = ArrayList<Double>()
        for (k in start until end) {
            if (hr[k] > 0.0) boutHr.add(hr[k])
        }
        val coverage = boutHr.size.toDouble() / tib
        if (coverage < minNapHrCoverage) {
            unverifiable++
            continue
        }
        val medHr = median(boutHr)!!

        val awakeHr = ArrayList<Double>()
        for (k in awakeIdx) {
            if (k in start until end) continue
            awakeHr.add(hr[k])
        }
        if (awakeHr.size < minAwakeHrSamples) {
            noBaseline++
            continue
        }
        val baseline = median(awakeHr)!!
        if (baseline <= 0.0) {
            noBaseline++
            continue
        }
        if (medHr > baseline * napRestingHrMult) {
            awakeStill++
            continue
        }

        var tst = 0
        for (k in start until end) {
            if (stillAt(k)) tst++
        }

        val dipScore = ((baseline - medHr) / (baseline * 0.20)).coerceIn(0.0, 1.0)
        val stillScore = if (tib <= 0) 0.0 else tst.toDouble() / tib
        val worstOff = if (offFrac > exFrac) offFrac else exFrac
        val corroborated = (1.0 - worstOff / maxNapOffWristFraction).coerceIn(0.0, 1.0)
        val conf = (0.20 +
            0.30 * dipScore +
            0.25 * coverage +
            0.15 * stillScore +
            0.10 * corroborated).coerceIn(0.2, 0.85)

        naps.add(
            NapWindow(
                startSec = aStart - baseSec,
                endSec = aEnd - baseSec,
                tibSec = tib,
                tstSec = tst,
                confidence = conf,
                startsAtRecordEdge = startsAtEdge[bi],
            ),
        )
    }

    if (naps.isEmpty() && noBaseline > 0) {
        return OsMetric.absent(
            tier = Tier.ESTIMATE,
            inputsUsed = napInputs,
            note = "not enough awake daytime HR to set a baseline for " +
                "$noBaseline still block(s) (need ${minAwakeHrSamples}s outside the " +
                "block itself) — cannot corroborate stillness as sleep",
        )
    }

    val skipped = ArrayList<String>()
    if (deferred > 0) skipped.add("$deferred deferred (record ends mid-bout)")
    if (noBaseline > 0) skipped.add("$noBaseline without an awake HR baseline")
    if (outOfRange > 0) skipped.add("$outOfRange outside 15 min–6 h")
    if (inMainSleep > 0) skipped.add("$inMainSleep inside the main sleep window")
    if (unobserved > 0) skipped.add("$unobserved with accel coverage <50% (unmeasured)")
    if (unverifiable > 0) skipped.add("$unverifiable unverifiable (HR coverage <50%)")
    if (offWrist > 0) skipped.add("$offWrist off-wrist/excluded")
    if (awakeStill > 0) skipped.add("$awakeStill still but no HR dip")
    val atEdge = naps.count { it.startsAtRecordEdge }
    val tail = (if (skipped.isEmpty()) "" else "; skipped: ${skipped.joinToString(", ")}") +
        if (atEdge == 0) {
            ""
        } else {
            "; $atEdge already in progress at the first sample " +
                "(start_sec is the record edge, not the sleep onset)"
        }
    val confidence = if (naps.isEmpty()) {
        0.3
    } else {
        var s = 0.0
        for (nap in naps) s += nap.confidence
        s / naps.size
    }
    val note = if (naps.isEmpty()) {
        "no qualifying nap (15 min–6 h, HR-corroborated) outside the main " +
            "sleep window$tail"
    } else {
        "${naps.size} nap(s) via van Hees z-angle immobility + an HR dip " +
            "vs the awake daytime baseline; wrist ESTIMATE, not PSG, and no " +
            "sleep-stage claim$tail"
    }
    return OsMetric(
        value = naps,
        confidence = confidence,
        tier = Tier.ESTIMATE,
        inputsUsed = napInputs,
        note = note,
    )
}

/**
 * Fraction of [start, end) covered by the union of [spans] (absolute seconds).
 * Overlapping spans are merged so a second is not counted twice.
 */
private fun overlapFraction(start: Int, end: Int, spans: List<List<Int>>): Double {
    val dur = end - start
    if (dur <= 0 || spans.isEmpty()) return 0.0
    val clipped = ArrayList<MutableList<Int>>()
    for (s in spans) {
        if (s.size < 2) continue
        val lo = if (s[0] > start) s[0] else start
        val hi = if (s[1] < end) s[1] else end
        if (hi > lo) clipped.add(mutableListOf(lo, hi))
    }
    if (clipped.isEmpty()) return 0.0
    clipped.sortWith { a, b -> a[0].compareTo(b[0]) }
    var covered = 0
    var runLo = clipped[0][0]
    var runHi = clipped[0][1]
    for (i in 1 until clipped.size) {
        val s = clipped[i]
        if (s[0] <= runHi) {
            if (s[1] > runHi) runHi = s[1]
        } else {
            covered += runHi - runLo
            runLo = s[0]
            runHi = s[1]
        }
    }
    covered += runHi - runLo
    return covered.toDouble() / dur
}
