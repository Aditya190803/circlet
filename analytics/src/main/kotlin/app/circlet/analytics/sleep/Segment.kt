// Ported from OpenStrap analytics/lib/src/onehz/sleep/segment.dart.
// Single-source in-bed segmentation: TST, WASO, efficiency, and stages from one label stream.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.OsMetric
import app.circlet.analytics.foundations.Tier
import app.circlet.analytics.foundations.round6
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val MIN_QUALIFYING_SLEEP_SEC = 3 * 3600

/** Fraction of the in-bed window that must be observed before any figure is published. */
const val kMinObservedFractionForSleep = 0.5

private const val HR_EVIDENCE_HALF_WIN_SEC = 15

/**
 * Coverage ceiling, not an accuracy. Stage-interval half-widths scale by
 * confidence / this, so the cap is load-bearing.
 */
const val kMaxSleepConfidence = 0.6

/** Narrowest half-width a stage interval may have, in seconds. */
const val kStageIntervalFloorSec = 300

private const val REM_REL_HALF_BEST = 0.30
private const val REM_REL_HALF_WORST = 0.55
private const val DEEP_REL_HALF_BEST = 0.45
private const val DEEP_REL_HALF_WORST = 0.75

/** A stage duration as the interval it actually is. */
class StageInterval(
    val pointSec: Int,
    val loSec: Int,
    val hiSec: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "point_sec" to pointSec,
        "lo_sec" to loSec,
        "hi_sec" to hiSec,
    )
}

class StageRanges(
    val light: StageInterval,
    val deep: StageInterval,
    val rem: StageInterval,
)

/** SLP-13 intervals. [confidence] is that night's own coverage score. */
fun stageIntervals(
    lightSec: Int,
    deepSec: Int,
    remSec: Int,
    tstSec: Int,
    confidence: Double,
): StageRanges {
    val t = (confidence / kMaxSleepConfidence).coerceIn(0.0, 1.0)
    fun rel(best: Double, worst: Double): Double = worst + (best - worst) * t
    val deepHalf = max(
        roundHalfAwayFromZero(rel(DEEP_REL_HALF_BEST, DEEP_REL_HALF_WORST) * deepSec).toInt(),
        kStageIntervalFloorSec,
    )
    val remHalf = max(
        roundHalfAwayFromZero(rel(REM_REL_HALF_BEST, REM_REL_HALF_WORST) * remSec).toInt(),
        kStageIntervalFloorSec,
    )
    return StageRanges(
        light = interval(lightSec, deepHalf, tstSec),
        deep = interval(deepSec, deepHalf, tstSec),
        rem = interval(remSec, remHalf, tstSec),
    )
}

private fun interval(sec: Int, halfSec: Int, tstSec: Int): StageInterval = StageInterval(
    sec,
    max(0, sec - halfSec),
    min(tstSec, sec + halfSec),
)

/** How long a wake run must last to count as an awakening. A choice, not physiology. */
const val kSustainedAwakeningSec = 300

/** Caller-asserted in-bed window. Replaces detection; it does not restage. */
data class ForcedWindow(val onsetSec: Int, val offsetSec: Int)

/** One historical sleep block for [habitualMidsleepSecFromHistory]. */
data class MidsleepBlock(val startSec: Int, val endSec: Int, val dayKey: String)

/** Single-source sleep segmentation. Every accounting figure comes from the per-second labels. */
class SleepSegmentation(
    val window: SleepWindow?,
    val stages: List<OsStage>,
    val stages4: List<String>,
    val tstSec: Int?,
    val wasoSec: Int?,
    val inBedSec: Int?,
    val unobservedSec: Int?,
    val efficiencyPct: Double?,
    val nremSec: Int?,
    val lightSec: Int?,
    val deepSec: Int?,
    val remSec: Int?,
    val wakeSec: Int?,
    val sustainedAwakenings: Int?,
    val longestSleepRunSec: Int?,
    val confidence: Double,
    val absenceReason: String? = null,
    val bandOffsetTrimSec: Int? = null,
) {
    val present: Boolean get() = window != null

    val stageRanges: StageRanges?
        get() {
            val l = lightSec
            val d = deepSec
            val r = remSec
            val t = tstSec
            if (l == null || d == null || r == null || t == null) return null
            return stageIntervals(l, d, r, t, confidence)
        }

    fun toJson(): Map<String, Any?> {
        val ranges = stageRanges
        val m = linkedMapOf<String, Any?>(
            "window" to window?.toJson(),
        )
        if (bandOffsetTrimSec != null) m["band_offset_trim_sec"] = bandOffsetTrimSec
        m["tst_sec"] = tstSec
        m["waso_sec"] = wasoSec
        m["in_bed_sec"] = inBedSec
        m["unobserved_sec"] = unobservedSec
        m["efficiency_pct"] = if (efficiencyPct == null) null else round6(efficiencyPct)
        m["nrem_sec"] = nremSec
        m["light_sec"] = lightSec
        m["deep_sec"] = deepSec
        m["rem_sec"] = remSec
        m["wake_sec"] = wakeSec
        m["sustained_awakenings"] = sustainedAwakenings
        m["awakening_min_sec"] = kSustainedAwakeningSec
        m["longest_sleep_run_sec"] = longestSleepRunSec
        m["epochs"] = stages.size
        m["confidence"] = round6(confidence)
        if (absenceReason != null) m["absence_reason"] = absenceReason
        m["deep_low_confidence"] = true
        m["light_range_sec"] = rangeJson(ranges?.light)
        m["deep_range_sec"] = rangeJson(ranges?.deep)
        m["rem_range_sec"] = rangeJson(ranges?.rem)
        return m
    }

    companion object {
        val absent: SleepSegmentation = SleepSegmentation(
            window = null,
            stages = emptyList(),
            stages4 = emptyList(),
            tstSec = null,
            wasoSec = null,
            inBedSec = null,
            unobservedSec = null,
            efficiencyPct = null,
            nremSec = null,
            lightSec = null,
            deepSec = null,
            remSec = null,
            wakeSec = null,
            sustainedAwakenings = null,
            longestSleepRunSec = null,
            confidence = 0.0,
        )

        fun unobservedWindow(reason: String): SleepSegmentation = SleepSegmentation(
            window = null,
            stages = emptyList(),
            stages4 = emptyList(),
            tstSec = null,
            wasoSec = null,
            inBedSec = null,
            unobservedSec = null,
            efficiencyPct = null,
            nremSec = null,
            lightSec = null,
            deepSec = null,
            remSec = null,
            wakeSec = null,
            sustainedAwakenings = null,
            longestSleepRunSec = null,
            confidence = 0.0,
            absenceReason = reason,
        )

        private fun rangeJson(r: StageInterval?): List<Int>? =
            if (r == null) null else listOf(r.loSec, r.hiSec)
    }
}

/**
 * The single-source sleep segmentation.
 *
 * [hrBaseline] is unused. It stays in the signature so a later HR-refinement
 * step is a one-file change; onset and offset are not HR-refined.
 */
@Suppress("UNUSED_PARAMETER")
fun segmentSleep(
    accel: List<AccelSample>,
    hr1hz: List<Double>,
    hrBaseline: List<Double>? = null,
    rrMs: List<Double> = emptyList(),
    rrTsMs: List<Double> = emptyList(),
    habitualMidsleepSec: Int? = null,
    forcedWindow: ForcedWindow? = null,
    tzOffsetSec: Int? = null,
    tzOffsetResolver: ((Int) -> Int)? = null,
    bandSleepState: List<Int>? = null,
): SleepSegmentation {
    val n = min(accel.size, hr1hz.size)
    if (forcedWindow == null && n < MIN_QUALIFYING_SLEEP_SEC) return SleepSegmentation.absent
    if (n == 0) return SleepSegmentation.absent

    val trimmedAccel = accel.subList(0, n)
    val trimmedHr = hr1hz.subList(0, n)
    val wm: OsMetric<SleepWindow> = if (forcedWindow == null) {
        vanHeesSleepWindow(trimmedAccel)
    } else {
        OsMetric.absent(tier = Tier.ESTIMATE, inputsUsed = listOf("forced_window"))
    }
    val fallbackWindow = wm.value

    val tzAt: (Int) -> Int = tzOffsetResolver
        ?: if (tzOffsetSec != null) {
            { _: Int -> tzOffsetSec }
        } else {
            { ts -> systemTzOffsetSec(ts) }
        }
    val grav = ArrayList<GravTs>(n)
    for (i in 0 until n) {
        val a = trimmedAccel[i]
        grav.add(
            GravTs(
                (a.tsMs / 1000.0).toInt(),
                a.x,
                a.y,
                a.z,
                valid = a.valid,
            ),
        )
    }
    val hr = ArrayList<HrTs>()
    for (i in 0 until n) {
        if (trimmedHr[i] > 0.0) {
            hr.add(HrTs((trimmedAccel[i].tsMs / 1000.0).toInt(), trimmedHr[i]))
        }
    }
    val rr = ArrayList<RrTs>()
    val rrN = min(rrMs.size, rrTsMs.size)
    for (i in 0 until rrN) {
        if (rrMs[i].isFinite() && rrMs[i] > 0.0) {
            rr.add(RrTs(roundHalfAwayFromZero(rrTsMs[i] / 1000.0).toInt(), rrMs[i]))
        }
    }

    var chosen: SleepGroup? = if (forcedWindow != null) {
        val onsetSec = forcedWindow.onsetSec
        val offsetSec = forcedWindow.offsetSec
        if (offsetSec <= onsetSec) return SleepSegmentation.absent
        val session = AdvancedSleepStager.stageWindow(onsetSec, offsetSec, grav, hr, rr = rr)
        SleepGroup(
            sessions = listOf(session),
            start = onsetSec,
            end = offsetSec,
            asleepMin = AdvancedSleepStager.hypnogramMetrics(session).tstS / 60.0,
        )
    } else {
        val sessions = AdvancedSleepStager.detectSleep(grav, hr, rr = rr, tzOffsetResolver = tzAt)
        if (sessions.isEmpty()) return SleepSegmentation.absent
        pickMainSleepGroup(
            bridgeAdjacentSessions(sessions),
            tzAt,
            habitualMidsleepSec = habitualMidsleepSec,
        )
    }
    if (chosen == null) return SleepSegmentation.absent

    val tsSec = IntArray(n) { (trimmedAccel[it].tsMs / 1000.0).toInt() }
    var bandOffsetTrimSec: Int? = null
    if (forcedWindow == null && bandSleepState != null && bandSleepState.size >= n) {
        val trimmedEnd = bandTrimmedOffsetSec(
            startSec = chosen.start,
            endSec = chosen.end,
            tsSec = tsSec.toList(),
            bandState = bandSleepState.subList(0, n),
            minNightSec = MIN_QUALIFYING_SLEEP_SEC,
        )
        if (trimmedEnd != null) {
            bandOffsetTrimSec = chosen.end - trimmedEnd
            chosen = SleepGroup(
                sessions = chosen.sessions,
                start = chosen.start,
                end = trimmedEnd,
                asleepMin = chosen.asleepMin,
            )
        }
    }
    val onset = lowerBoundInt(tsSec, chosen.start)
    val offset = lowerBoundInt(tsSec, chosen.end)
    val inBed = chosen.end - chosen.start
    if (inBed <= 0) return SleepSegmentation.absent
    if (forcedWindow == null && (offset <= onset || inBed < MIN_QUALIFYING_SLEEP_SEC)) {
        return SleepSegmentation.absent
    }

    val sampled = BooleanArray(inBed)
    val hrNear = BooleanArray(inBed)
    var k = onset
    while (k < tsSec.size && tsSec[k] < chosen.end) {
        val off = tsSec[k] - chosen.start
        if (off >= 0 && off < inBed) {
            if (trimmedAccel[k].valid) sampled[off] = true
            if (trimmedHr[k] > 0.0) {
                val lo = max(0, off - HR_EVIDENCE_HALF_WIN_SEC)
                val hi = min(inBed - 1, off + HR_EVIDENCE_HALF_WIN_SEC)
                for (m in lo..hi) hrNear[m] = true
            }
        }
        k++
    }
    val observed = BooleanArray(inBed) { sampled[it] && hrNear[it] }

    val stages4 = MutableList(inBed) { "wake" }
    for (session in chosen.sessions) {
        for (seg in session.stages) {
            val lo = max(0, seg.start - chosen.start)
            val hi = min(inBed, seg.end - chosen.start)
            for (i in lo until hi) stages4[i] = seg.stage
        }
    }
    var unobservedSec = 0
    for (i in 0 until inBed) {
        if (observed[i] && stages4[i] != "unobserved") continue
        observed[i] = false
        stages4[i] = "unobserved"
        unobservedSec++
    }
    val observedSec = inBed - unobservedSec
    if (observedSec < inBed * kMinObservedFractionForSleep) {
        val floorPct = roundHalfAwayFromZero(kMinObservedFractionForSleep * 100.0).toInt()
        return SleepSegmentation.unobservedWindow(
            "only ${observedSec}s of a ${inBed}s window were observed " +
                "(sample + heart rate); below the ${floorPct}% floor",
        )
    }

    val perSec = List(inBed) { sleepStageFor(stages4[it]) }
    var tst = 0
    var waso = 0
    var nrem = 0
    var light = 0
    var deep = 0
    var rem = 0
    var wake = 0
    var firstSleep = -1
    var lastSleep = -1
    for (i in perSec.indices) {
        if (!observed[i]) continue
        when (perSec[i]) {
            OsStage.WAKE -> wake++
            OsStage.NREM -> {
                nrem++
                tst++
                if (stages4[i] == "deep") deep++ else light++
            }
            OsStage.REM -> {
                rem++
                tst++
            }
        }
        if (perSec[i] != OsStage.WAKE) {
            if (firstSleep < 0) firstSleep = i
            lastSleep = i
        }
    }
    if (firstSleep >= 0) {
        for (i in firstSleep..lastSleep) {
            if (observed[i] && perSec[i] == OsStage.WAKE) waso++
        }
    }

    var sustainedAwakenings = 0
    var longestSleepRun = 0
    var sleepRun = 0
    var wakeRun = 0
    for (i in perSec.indices) {
        val asleep = observed[i] && perSec[i] != OsStage.WAKE
        val awake = observed[i] && perSec[i] == OsStage.WAKE
        sleepRun = if (asleep) sleepRun + 1 else 0
        if (sleepRun > longestSleepRun) longestSleepRun = sleepRun
        val inWaso = firstSleep >= 0 && i > firstSleep && i < lastSleep
        if (awake && inWaso) {
            wakeRun++
            if (wakeRun == kSustainedAwakeningSec) sustainedAwakenings++
        } else {
            wakeRun = 0
        }
    }
    if (tst == 0) return SleepSegmentation.absent
    val efficiency = if (observedSec > 0) 100.0 * tst / observedSec else 0.0

    var hrCovered = 0
    val rrSeconds = HashSet<Int>()
    for (i in 0 until n) {
        val ts = (trimmedAccel[i].tsMs / 1000.0).toInt()
        if (ts < chosen.start || ts >= chosen.end) continue
        if (trimmedHr[i] > 0.0) hrCovered++
    }
    for (b in rr) {
        if (b.ts >= chosen.start && b.ts < chosen.end) rrSeconds.add(b.ts)
    }
    val hrCov = (hrCovered.toDouble() / inBed).coerceIn(0.0, 1.0)
    val rrCov = (rrSeconds.size.toDouble() / inBed).coerceIn(0.0, 1.0)
    val stagingConf = (0.35 + 0.25 * rrCov) * hrCov
    val windowConf = (inBed / (7.0 * 3600.0)).coerceIn(0.3, 0.95)
    val conf = ((windowConf + stagingConf) / 2.0).coerceIn(0.0, kMaxSleepConfidence)

    return SleepSegmentation(
        window = SleepWindow(
            onsetIdx = onset,
            offsetIdx = offset,
            onsetMs = chosen.start * 1000.0,
            offsetMs = chosen.end * 1000.0,
            immobile = fallbackWindow?.immobile ?: emptyList(),
            immobileUnknown = fallbackWindow?.immobileUnknown ?: emptyList(),
            zAngleDeg = fallbackWindow?.zAngleDeg ?: emptyList(),
            sptSec = inBed,
        ),
        stages = perSec,
        stages4 = stages4,
        tstSec = tst,
        wasoSec = waso,
        inBedSec = inBed,
        unobservedSec = unobservedSec,
        efficiencyPct = efficiency,
        nremSec = nrem,
        lightSec = light,
        deepSec = deep,
        remSec = rem,
        wakeSec = wake,
        sustainedAwakenings = sustainedAwakenings,
        longestSleepRunSec = longestSleepRun,
        confidence = conf,
        bandOffsetTrimSec = bandOffsetTrimSec,
    )
}

private class SleepGroup(
    val sessions: List<SleepSession>,
    val start: Int,
    val end: Int,
    val asleepMin: Double,
) {
    val midsleepSec: Int get() = start + (end - start) / 2
}

private fun bridgeAdjacentSessions(sessions: List<SleepSession>): List<SleepGroup> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedBy { it.start }
    val bridgeGapSec = 60 * 60
    val out = ArrayList<SleepGroup>()
    for (session in sorted) {
        val asleepMin = AdvancedSleepStager.hypnogramMetrics(session).tstS / 60.0
        if (out.isEmpty()) {
            out.add(SleepGroup(listOf(session), session.start, session.end, asleepMin))
            continue
        }
        val last = out.removeAt(out.lastIndex)
        val gap = session.start - last.end
        if (gap >= 0 && gap < bridgeGapSec) {
            out.add(
                SleepGroup(
                    sessions = last.sessions + session,
                    start = last.start,
                    end = max(last.end, session.end),
                    asleepMin = last.asleepMin + asleepMin,
                ),
            )
        } else {
            out.add(last)
            out.add(SleepGroup(listOf(session), session.start, session.end, asleepMin))
        }
    }
    return out
}

private fun pickMainSleepGroup(
    groups: List<SleepGroup>,
    tzAt: (Int) -> Int,
    habitualMidsleepSec: Int? = null,
): SleepGroup? {
    if (groups.isEmpty()) return null
    val alignmentBonusMin = 90.0
    val fullWindowSec = 2 * 3600
    val zeroWindowSec = 5 * 3600
    val overnightStartHour = 20
    val overnightEndHour = 11
    val secondsPerDay = 86400
    val overnightSpanSec =
        (((overnightEndHour - overnightStartHour) * 3600) + secondsPerDay) % secondsPerDay
    val coldStartAnchorSec =
        ((overnightStartHour * 3600) + overnightSpanSec / 2) % secondsPerDay
    val targetMidsleepSec = habitualMidsleepSec ?: coldStartAnchorSec

    fun localSecOfDay(ts: Int): Int = localSecOfDay(ts, tzAt(ts))

    fun circularDistanceSec(a: Int, b: Int): Int {
        val raw = abs(a - b) % secondsPerDay
        return min(raw, secondsPerDay - raw)
    }

    fun alignmentBonusFor(g: SleepGroup): Double {
        val dist = circularDistanceSec(localSecOfDay(g.midsleepSec), targetMidsleepSec)
        if (dist <= fullWindowSec) return alignmentBonusMin
        if (dist >= zeroWindowSec) return 0.0
        val frac = (zeroWindowSec - dist).toDouble() / (zeroWindowSec - fullWindowSec)
        return alignmentBonusMin * frac
    }

    var winner = groups.first()
    var bestScore = winner.asleepMin + alignmentBonusFor(winner)
    for (g in groups.drop(1)) {
        val score = g.asleepMin + alignmentBonusFor(g)
        if (score > bestScore || (score == bestScore && g.start < winner.start)) {
            winner = g
            bestScore = score
        }
    }
    return winner
}

/**
 * Habitual midsleep anchor (local second-of-day) from at least [minDays] of history.
 * Same per-timestamp offset precedence as [segmentSleep].
 */
fun habitualMidsleepSecFromHistory(
    history: List<MidsleepBlock>,
    tzOffsetSeconds: Int? = null,
    tzOffsetResolver: ((Int) -> Int)? = null,
    minDays: Int = 14,
): Int? {
    if (history.isEmpty()) return null
    val tzAt: (Int) -> Int = tzOffsetResolver
        ?: if (tzOffsetSeconds != null) {
            { _: Int -> tzOffsetSeconds }
        } else {
            { ts -> systemTzOffsetSec(ts) }
        }
    val longestByDay = LinkedHashMap<String, MidsleepBlock>()
    for (block in history) {
        val cur = longestByDay[block.dayKey]
        val dur = block.endSec - block.startSec
        val curDur = if (cur == null) -1 else cur.endSec - cur.startSec
        if (cur == null || dur > curDur || (dur == curDur && block.startSec < cur.startSec)) {
            longestByDay[block.dayKey] = block
        }
    }
    if (longestByDay.size < minDays) return null
    val mids = ArrayList<Int>(longestByDay.size)
    for (block in longestByDay.values) {
        val mid = block.startSec + ((block.endSec - block.startSec) / 2)
        mids.add(localSecOfDay(mid, tzAt(mid)))
    }
    return circularMeanSec(mids)
}

private fun localSecOfDay(ts: Int, offsetSec: Int): Int {
    val secondsPerDay = 86400L
    val local = ts.toLong() + offsetSec.toLong()
    return (((local % secondsPerDay) + secondsPerDay) % secondsPerDay).toInt()
}

private fun circularMeanSec(secs: List<Int>): Int? {
    if (secs.isEmpty()) return null
    val secondsPerDay = 86400
    val minResultant = 1e-9
    var sumSin = 0.0
    var sumCos = 0.0
    val k = 2.0 * PI / secondsPerDay
    for (s in secs) {
        val a = s * k
        sumSin += sin(a)
        sumCos += cos(a)
    }
    val resultant = sqrt(sumSin * sumSin + sumCos * sumCos) / secs.size
    if (resultant < minResultant) return null
    var ang = atan2(sumSin, sumCos)
    if (ang < 0.0) ang += 2.0 * PI
    val sec = roundHalfAwayFromZero(ang / k).toInt() % secondsPerDay
    return ((sec % secondsPerDay) + secondsPerDay) % secondsPerDay
}

private fun sleepStageFor(label: String): OsStage = when (label) {
    "rem" -> OsStage.REM
    "light", "deep" -> OsStage.NREM
    else -> OsStage.WAKE
}

private fun lowerBoundInt(xs: IntArray, target: Int): Int {
    var lo = 0
    var hi = xs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (xs[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}

/** Local offset at [tsSec], matching Dart `DateTime.fromMillisecondsSinceEpoch(..., isUtc: false)`. */
private fun systemTzOffsetSec(tsSec: Int): Int {
    val instant = Instant.ofEpochSecond(tsSec.toLong())
    return ZoneId.systemDefault().rules.getOffset(instant).totalSeconds
}
