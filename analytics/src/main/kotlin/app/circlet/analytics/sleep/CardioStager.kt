// Ported from OpenStrap analytics/lib/src/onehz/sleep/cardio_stager.dart.
// Transparent night-relative HR, ENMO, and RR staging (Webster rescore; no ML).

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.AccelSample
import app.circlet.analytics.foundations.RobustScale
import app.circlet.analytics.foundations.lombScargle
import app.circlet.analytics.foundations.freqGrid
import app.circlet.analytics.foundations.mad
import app.circlet.analytics.foundations.mean
import app.circlet.analytics.foundations.median
import app.circlet.analytics.foundations.percentile
import app.circlet.analytics.foundations.percentileSorted
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.foundations.sampleCadenceSeconds
import app.circlet.analytics.foundations.stddev
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CardioStagerResult(
    val base: StagerResult,
    val deepFlag: List<Boolean>,
    val confidence: Double,
)

private const val EPOCH_SEC = 30

/** Minimum fraction of epochs that must carry a valid HR before any label is emitted. */
const val minHrCoverage = 0.5

private const val W_REM_RK = 0.43
private const val W_REM_SDNN = 0.32
private const val W_REM_HR_SD = 0.27
private const val W_REM_LFHF = 0.12

private const val W_DEEP_RK = 0.53
private const val W_DEEP_HR_SD = 0.43
private const val W_DEEP_SDNN = 0.41
private const val W_DEEP_LFHF = 0.33

private const val REM_SCORE_CUT = 0.5
private const val DEEP_SCORE_CUT = 0.3

/** Shipped cutoffs. Production callers should not override them. */
const val kDefaultRemScoreCut = REM_SCORE_CUT
const val kDefaultDeepScoreCut = DEEP_SCORE_CUT

private const val RR_MIN = 300.0
private const val RR_MAX = 2000.0
private const val RR_MAX_STEP = 200.0

/**
 * Per-user rolling sleep profile. Null fields mean no personalization on that axis.
 * No clock: [fold] leaves [updatedAtMs] at [nowMs] (default 0).
 */
class SleepUserProfile(
    val nights: Int = 0,
    val hrFloorP5: Double? = null,
    val hrFloorP25: Double? = null,
    val hrSleepMedian: Double? = null,
    val hrArousal: Double? = null,
    val rmssdMed: Double? = null,
    val rmssdMad: Double? = null,
    val enmoStillCut: Double? = null,
    val enmoMoveCut: Double? = null,
    val lfhfMed: Double? = null,
    val rkMed: Double? = null,
    val updatedAtMs: Long = 0,
) {
    /** 0 at cold start, hard cap 0.5 at ≥14 nights. */
    val personalWeight: Double get() = (nights / 28.0).coerceIn(0.0, 0.5)

    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("nights" to nights)
        if (hrFloorP5 != null) m["hr_floor_p5"] = hrFloorP5
        if (hrFloorP25 != null) m["hr_floor_p25"] = hrFloorP25
        if (hrSleepMedian != null) m["hr_sleep_median"] = hrSleepMedian
        if (hrArousal != null) m["hr_arousal"] = hrArousal
        if (rmssdMed != null) m["rmssd_med"] = rmssdMed
        if (rmssdMad != null) m["rmssd_mad"] = rmssdMad
        if (enmoStillCut != null) m["enmo_still_cut"] = enmoStillCut
        if (enmoMoveCut != null) m["enmo_move_cut"] = enmoMoveCut
        if (lfhfMed != null) m["lfhf_med"] = lfhfMed
        if (rkMed != null) m["rk_med"] = rkMed
        m["updated_at_ms"] = updatedAtMs
        return m
    }

    /**
     * EWMA-fold one night. [nowMs] is the stamp; this package does not read a clock.
     */
    fun fold(o: SleepNightObservation, horizonNights: Int = 14, nowMs: Long = 0): SleepUserProfile {
        require(horizonNights > 0) { "horizonNights must be positive" }
        val n2 = nights + 1
        val a = max(1.0 / n2, 2.0 / (horizonNights + 1))
        fun ew(old: Double?, obs: Double?): Double? =
            if (obs == null) old else if (old == null) obs else old * (1 - a) + obs * a
        return SleepUserProfile(
            nights = n2,
            hrFloorP5 = ew(hrFloorP5, o.hrFloorP5),
            hrFloorP25 = ew(hrFloorP25, o.hrFloorP25),
            hrSleepMedian = ew(hrSleepMedian, o.hrSleepMedian),
            hrArousal = ew(hrArousal, o.hrArousal),
            rmssdMed = ew(rmssdMed, o.rmssdMed),
            rmssdMad = ew(rmssdMad, o.rmssdMad),
            enmoStillCut = ew(enmoStillCut, o.enmoStillCut),
            enmoMoveCut = ew(enmoMoveCut, o.enmoMoveCut),
            lfhfMed = ew(lfhfMed, o.lfhfMed),
            rkMed = ew(rkMed, o.rkMed),
            updatedAtMs = nowMs,
        )
    }

    companion object {
        private fun d(v: Any?): Double? = when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is Number -> v.toDouble()
            else -> null
        }

        private fun i(v: Any?): Int = when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is Number -> v.toInt()
            else -> 0
        }

        private fun l(v: Any?): Long = when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is Number -> v.toLong()
            else -> 0L
        }

        fun fromJson(j: Map<String, Any?>): SleepUserProfile = SleepUserProfile(
            nights = i(j["nights"]),
            hrFloorP5 = d(j["hr_floor_p5"]),
            hrFloorP25 = d(j["hr_floor_p25"]),
            hrSleepMedian = d(j["hr_sleep_median"]),
            hrArousal = d(j["hr_arousal"]),
            rmssdMed = d(j["rmssd_med"]),
            rmssdMad = d(j["rmssd_mad"]),
            enmoStillCut = d(j["enmo_still_cut"]),
            enmoMoveCut = d(j["enmo_move_cut"]),
            lfhfMed = d(j["lfhf_med"]),
            rkMed = d(j["rk_med"]),
            updatedAtMs = l(j["updated_at_ms"]),
        )
    }
}

/** One night's observed sleep baselines, for folding into a [SleepUserProfile]. */
class SleepNightObservation(
    val epochs: Int,
    val hrFloorP5: Double? = null,
    val hrFloorP25: Double? = null,
    val hrSleepMedian: Double? = null,
    val hrArousal: Double? = null,
    val rmssdMed: Double? = null,
    val rmssdMad: Double? = null,
    val enmoStillCut: Double? = null,
    val enmoMoveCut: Double? = null,
    val lfhfMed: Double? = null,
    val rkMed: Double? = null,
)

/** Ambient profile used when [cardioStager] is called without an explicit profile. */
var cardioUserProfile: SleepUserProfile? = null

/** When true, each [cardioStager] run appends one observation (nights of ≥60 min). */
var cardioRecordObservations: Boolean = false

private val cardioObservations = ArrayList<SleepNightObservation>()

/** Drain and clear the recorded observations. */
fun takeCardioObservations(): List<SleepNightObservation> {
    val c = cardioObservations.toList()
    cardioObservations.clear()
    return c
}

/** Clear the observation buffer without reading it. */
fun resetCardioObservations() {
    cardioObservations.clear()
}

/**
 * Transparent cardiorespiratory stager.
 *
 * [hr1hz] and [accel] share a time base. Cadence is measured from `accel[i].tsMs`.
 * No measurable cadence, or one coarser than [epochSec], abstains.
 * [rrTsMs] must be non-decreasing.
 */
fun cardioStager(
    hr1hz: List<Double>,
    accel: List<AccelSample>,
    rrMs: List<Double> = emptyList(),
    rrTsMs: List<Double> = emptyList(),
    epochSec: Int = EPOCH_SEC,
    userProfile: SleepUserProfile? = null,
    remScoreCut: Double = REM_SCORE_CUT,
    deepScoreCut: Double = DEEP_SCORE_CUT,
): CardioStagerResult {
    val profile = userProfile ?: cardioUserProfile
    for (i in 1 until rrTsMs.size) {
        check(rrTsMs[i] >= rrTsMs[i - 1]) {
            "rrTsMs must be non-decreasing — the beat window is binary-searched"
        }
    }
    val n = min(hr1hz.size, accel.size)
    val cadenceSec = sampleCadenceSeconds(List(n) { accel[it].tsMs / 1000.0 })
    if (cadenceSec == null || cadenceSec > epochSec) return abstain(epochSec)
    val perEpoch = max(1, roundHalfAwayFromZero(epochSec / cadenceSec).toInt())
    if (abs(perEpoch * cadenceSec - epochSec) > 1e-6) return abstain(epochSec)
    val nEpoch = n / perEpoch
    if (nEpoch < 3) return abstain(epochSec)

    val mag = DoubleArray(n) { i ->
        val a = accel[i]
        sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
    }
    val gRefWinSec = 300
    val gRefHalf = max(1, roundHalfAwayFromZero(gRefWinSec / cadenceSec).toInt()) / 2
    val gRefByEpoch = DoubleArray(nEpoch) { 1.0 }
    for (e in 0 until nEpoch) {
        val es = e * perEpoch
        val lo = max(0, es - gRefHalf)
        val hi = min(n, es + perEpoch + gRefHalf)
        gRefByEpoch[e] = median(mag.slice(lo until hi)) ?: 1.0
    }

    val motion = DoubleArray(nEpoch)
    val hr = DoubleArray(nEpoch) { Double.NaN }
    val hrSd = DoubleArray(nEpoch)
    val rmssd = DoubleArray(nEpoch) { Double.NaN }
    val sdnn = DoubleArray(nEpoch) { Double.NaN }
    val lfhf = DoubleArray(nEpoch) { Double.NaN }
    val rk = DoubleArray(nEpoch) { Double.NaN }

    for (e in 0 until nEpoch) {
        val s = e * perEpoch
        val t = min(s + perEpoch, n)
        val gRefE = gRefByEpoch[e]
        var ms = 0.0
        for (i in s until t) {
            val d = mag[i] - gRefE
            ms += if (d > 0.0) d else 0.0
        }
        motion[e] = ms / (t - s)
        val hv = ArrayList<Double>()
        for (i in s until t) {
            if (hr1hz[i] > 0.0) hv.add(hr1hz[i])
        }
        if (hv.isNotEmpty()) hr[e] = mean(hv)!!
        if (hv.size >= 2) hrSd[e] = stddev(hv) ?: 0.0
        rmssd[e] = windowRmssd(rrMs, rrTsMs, accel, s, t)
        sdnn[e] = windowSdnn(rrMs, rrTsMs, accel, s, t)
        val rem = windowRemFeatures(rrMs, rrTsMs, accel, s, t)
        if (rem.lfhf != null) lfhf[e] = rem.lfhf
        if (rem.rk != null) rk[e] = rem.rk
    }

    return classifyCardioEpochs(
        CardioEpochFeatures(
            motion = motion.toList(),
            hr = hr.toList(),
            hrSd = hrSd.toList(),
            rmssd = rmssd.toList(),
            lfhf = lfhf.toList(),
            rk = rk.toList(),
            sdnn = sdnn.toList(),
        ),
        epochSec = epochSec,
        userProfile = profile,
        remScoreCut = remScoreCut,
        deepScoreCut = deepScoreCut,
    )
}

/** Per-epoch feature vectors. NaN marks "not measurable". Lists should be equal length. */
class CardioEpochFeatures(
    val motion: List<Double>,
    val hr: List<Double>,
    val hrSd: List<Double>,
    val rmssd: List<Double>,
    val lfhf: List<Double>,
    val rk: List<Double>,
    val sdnn: List<Double>,
) {
    /** Shortest list, so a mismatched caller truncates instead of throwing. */
    val length: Int
        get() = minOf(
            motion.size, hr.size, hrSd.size, rmssd.size, lfhf.size, rk.size, sdnn.size,
        )
}

/** Staging decision: per-epoch features to stages and deep flags. */
fun classifyCardioEpochs(
    f: CardioEpochFeatures,
    epochSec: Int = EPOCH_SEC,
    userProfile: SleepUserProfile? = null,
    remScoreCut: Double = REM_SCORE_CUT,
    deepScoreCut: Double = DEEP_SCORE_CUT,
): CardioStagerResult {
    val profile = userProfile ?: cardioUserProfile
    val nEpoch = f.length
    if (nEpoch < 3) return abstain(epochSec)
    val motion = f.motion
    val hr = f.hr
    val hrSd = f.hrSd
    val rmssd = f.rmssd
    val lfhf = f.lfhf
    val rk = f.rk
    val sdnn = f.sdnn

    val motSample = motion.subList(0, nEpoch).toList()
    val motMed = median(motSample) ?: 0.0
    val motMadRaw = mad(motSample) ?: 0.0
    val motUsable = motMadRaw > 0.0 && motMadRaw.isFinite()
    val motMad = if (motUsable) motMadRaw else 0.0
    val pw = profile?.personalWeight ?: 0.0
    fun blendP(local: Double, personal: Double?): Double =
        if (personal == null || pw == 0.0) local else local * (1 - pw) + personal * pw

    val stillCut = blendP(motMed + 1.5 * motMad, profile?.enmoStillCut)
    fun still(e: Int): Boolean = !motUsable || motion[e] <= stillCut
    val bigMoveCut = blendP(motMed + 5.0 * motMad, profile?.enmoMoveCut)
    fun bigMove(e: Int): Boolean = motUsable && motion[e] > bigMoveCut

    val sleepHr = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && !hr[e].isNaN()) sleepHr.add(hr[e])
    }
    val hrAll = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (!hr[e].isNaN()) hrAll.add(hr[e])
    }
    val hrCov = if (nEpoch == 0) 0.0 else hrAll.size / nEpoch.toDouble()
    if (hrCov < minHrCoverage) return abstain(epochSec)
    val hrMedGlobal = median(sleepHr) ?: mean(hrAll)!!

    val hrWinEpochs = 180
    val hrMedLocal = DoubleArray(nEpoch) { hrMedGlobal }
    val hrArousalLocal = DoubleArray(nEpoch) { hrMedGlobal + 6.0 }
    val hrP25Local = DoubleArray(nEpoch) { hrMedGlobal }
    for (e in 0 until nEpoch) {
        val lo = max(0, e - hrWinEpochs)
        val hi = min(nEpoch, e + hrWinEpochs + 1)
        val win = ArrayList<Double>()
        for (k in lo until hi) {
            if (still(k) && !hr[k].isNaN()) win.add(hr[k])
        }
        val sd = stddev(win)
        win.sort()
        val m = percentileSorted(win, 50.0)
        if (m != null) {
            hrMedLocal[e] = m
            hrArousalLocal[e] = m + max(6.0, sd ?: 6.0)
            hrP25Local[e] = percentileSorted(win, 25.0) ?: m
        }
    }
    if (profile != null && pw > 0.0) {
        for (e in 0 until nEpoch) {
            hrMedLocal[e] = blendP(hrMedLocal[e], profile.hrSleepMedian)
            hrArousalLocal[e] = blendP(hrArousalLocal[e], profile.hrArousal)
            hrP25Local[e] = blendP(hrP25Local[e], profile.hrFloorP25)
        }
    }

    val sleepRmssd = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && !rmssd[e].isNaN()) sleepRmssd.add(rmssd[e])
    }
    val rmssdMed = median(sleepRmssd)
    val sleepLfhf = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && !lfhf[e].isNaN()) sleepLfhf.add(lfhf[e])
    }
    val sleepRk = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && !rk[e].isNaN()) sleepRk.add(rk[e])
    }
    val lfhfScale = RobustScale.of(sleepLfhf)
    val rkScale = RobustScale.of(sleepRk)
    val sleepSdnn = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && !sdnn[e].isNaN()) sleepSdnn.add(sdnn[e])
    }
    val sdnnScale = RobustScale.of(sleepSdnn)
    val sleepHrSd = ArrayList<Double>()
    for (e in 0 until nEpoch) {
        if (still(e) && hrSd[e] > 0.0) sleepHrSd.add(hrSd[e])
    }
    val hrSdScale = RobustScale.of(sleepHrSd)

    val stages = MutableList(nEpoch) { OsStage.WAKE }
    val deepFlag = MutableList(nEpoch) { false }
    for (e in 0 until nEpoch) {
        val hrMed = hrMedLocal[e]
        val hrArousal = hrArousalLocal[e]
        val hrUp = !hr[e].isNaN() && hr[e] >= hrArousal
        val bigPrev = e > 0 && bigMove(e - 1)
        val bigMoveWake = bigMove(e) && ((!hr[e].isNaN() && hr[e] >= hrMed) || bigPrev)
        if (hrUp || bigMoveWake) {
            stages[e] = OsStage.WAKE
            continue
        }
        val rkZ = if (sleepRk.size >= 4 && !rk[e].isNaN()) rkScale?.z(rk[e]) else null
        val sdnnZ = if (sleepSdnn.size >= 4 && !sdnn[e].isNaN()) sdnnScale?.z(sdnn[e]) else null
        val lfhfZ = if (sleepLfhf.size >= 4 && !lfhf[e].isNaN()) lfhfScale?.z(lfhf[e]) else null
        val hrSdZ = if (sleepHrSd.size >= 4 && hrSd[e] > 0.0) hrSdScale?.z(hrSd[e]) else null

        fun score(terms: List<Pair<Double?, Double>>): Double? {
            var num = 0.0
            var den = 0.0
            for ((z, w) in terms) {
                if (z == null || z.isNaN()) continue
                num += z * w
                den += abs(w)
            }
            return if (den == 0.0) null else num / den
        }

        val remScore = score(
            listOf(
                rkZ to W_REM_RK,
                sdnnZ to W_REM_SDNN,
                hrSdZ to W_REM_HR_SD,
                lfhfZ to W_REM_LFHF,
            ),
        )
        val deepScore = score(
            listOf(
                rkZ to -W_DEEP_RK,
                hrSdZ to -W_DEEP_HR_SD,
                sdnnZ to -W_DEEP_SDNN,
                lfhfZ to -W_DEEP_LFHF,
            ),
        )
        val atonia = !bigMove(e)
        val hrTowardWake = !hr[e].isNaN() && hr[e] >= hrP25Local[e]
        if (remScore != null && remScore > remScoreCut && atonia && hrTowardWake) {
            stages[e] = OsStage.REM
        } else {
            stages[e] = OsStage.NREM
            deepFlag[e] = deepScore != null && deepScore > deepScoreCut
        }
    }

    modeFilterStages(stages)
    websterRescoreCardio(stages, epochSec)
    val sm = consolidateSleepStages(stages, epochSec)
    for (e in 0 until min(deepFlag.size, sm.size)) {
        if (deepFlag[e] && sm[e] != OsStage.NREM) deepFlag[e] = false
    }
    mergeShortDeep(deepFlag, sm, epochSec)

    if (cardioRecordObservations && nEpoch >= 120 && sleepHr.isNotEmpty()) {
        val hrSleepMed = median(sleepHr)
        cardioObservations.add(
            SleepNightObservation(
                epochs = nEpoch,
                hrFloorP5 = percentile(sleepHr, 5.0),
                hrFloorP25 = percentile(sleepHr, 25.0),
                hrSleepMedian = hrSleepMed,
                hrArousal = (hrSleepMed ?: hrMedGlobal) + max(6.0, stddev(sleepHr) ?: 6.0),
                rmssdMed = rmssdMed,
                rmssdMad = mad(sleepRmssd),
                enmoStillCut = motMed + 1.5 * motMad,
                enmoMoveCut = motMed + 5.0 * motMad,
                lfhfMed = median(sleepLfhf),
                rkMed = median(sleepRk),
            ),
        )
        if (cardioObservations.size > 32) cardioObservations.removeAt(0)
    }

    var w = 0
    var nr = 0
    var r = 0
    for (s in sm) {
        when (s) {
            OsStage.WAKE -> w++
            OsStage.NREM -> nr++
            else -> r++
        }
    }
    val tot = sm.size.toDouble()
    val rrCov = if (nEpoch == 0) 0.0 else sleepRmssd.size / nEpoch.toDouble()
    var hrFinite = 0
    for (e in 0 until nEpoch) {
        if (!hr[e].isNaN()) hrFinite++
    }
    val hrCovConf = if (nEpoch == 0) 0.0 else (hrFinite / nEpoch.toDouble()).coerceIn(0.0, 1.0)
    val conf = ((0.35 + 0.25 * rrCov) * hrCovConf).coerceIn(0.15, 0.6)
    return CardioStagerResult(
        StagerResult(
            stages = sm,
            epochSec = epochSec,
            wakePct = 100.0 * w / tot,
            nremPct = 100.0 * nr / tot,
            remPct = 100.0 * r / tot,
        ),
        deepFlag,
        conf,
    )
}

private fun abstain(epochSec: Int): CardioStagerResult = CardioStagerResult(
    StagerResult(
        stages = emptyList(),
        epochSec = epochSec,
        wakePct = 0.0,
        nremPct = 0.0,
        remPct = 0.0,
    ),
    emptyList(),
    0.0,
)

private class CleanBeats(
    val beats: List<Double>,
    val tsSec: List<Double>,
    val seam: List<Boolean>,
)

private fun windowRmssd(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
): Double {
    val win = cleanBeatsInWindow(rrMs, rrTsMs, accel, s, t)
    val beats = win.beats
    if (beats.size < 5) return Double.NaN
    var ss = 0.0
    var pairs = 0
    for (i in 1 until beats.size) {
        if (win.seam[i]) continue
        val d = beats[i] - beats[i - 1]
        ss += d * d
        pairs++
    }
    return if (pairs >= 4) sqrt(ss / pairs) else Double.NaN
}

private fun windowSdnn(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
): Double {
    val beats = cleanBeatsInWindow(rrMs, rrTsMs, accel, s, t).beats
    if (beats.size < 5) return Double.NaN
    return stddev(beats) ?: Double.NaN
}

private fun cleanBeatsInWindow(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
    halfWinMs: Int = 150 * 1000,
): CleanBeats {
    val empty = CleanBeats(emptyList(), emptyList(), emptyList())
    if (rrMs.isEmpty() || rrTsMs.size != rrMs.size) return empty
    val mid = (s + t) / 2
    if (mid >= accel.size) return empty
    val centreMs = accel[mid].tsMs
    val lo = centreMs - halfWinMs
    val hi = centreMs + halfWinMs
    var a = 0
    var b = rrTsMs.size
    while (a < b) {
        val m = (a + b) ushr 1
        if (rrTsMs[m] < lo) {
            a = m + 1
        } else {
            b = m
        }
    }
    val beats = ArrayList<Double>()
    val tsSec = ArrayList<Double>()
    val seam = ArrayList<Boolean>()
    var prev: Double? = null
    var broken = true
    var i = a
    while (i < rrMs.size) {
        val ts = rrTsMs[i]
        if (ts > hi) break
        val v = rrMs[i]
        if (i > a && ts - rrTsMs[i - 1] > v + 1000.0) broken = true
        if (v < RR_MIN || v > RR_MAX) {
            prev = null
            broken = true
            i++
            continue
        }
        if (prev != null && abs(v - prev) > RR_MAX_STEP) {
            prev = v
            broken = true
            i++
            continue
        }
        beats.add(v)
        seam.add(broken)
        broken = false
        tsSec.add((ts - lo) / 1000.0)
        prev = v
        i++
    }
    return CleanBeats(beats, tsSec, seam)
}

/** Beats inside the window. Test hook, not a staging entry point. */
fun cleanBeatsInWindowForTest(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
    halfWinMs: Int = 150 * 1000,
): List<Double> = cleanBeatsInWindow(rrMs, rrTsMs, accel, s, t, halfWinMs).beats

/** Successive differences actually taken, seams skipped. Test hook. */
fun cleanBeatDiffsInWindowForTest(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
    halfWinMs: Int = 150 * 1000,
): List<Double> {
    val w = cleanBeatsInWindow(rrMs, rrTsMs, accel, s, t, halfWinMs)
    val out = ArrayList<Double>()
    for (k in 1 until w.beats.size) {
        if (!w.seam[k]) out.add(w.beats[k] - w.beats[k - 1])
    }
    return out
}

class WindowRmssdRk(val rmssd: Double, val rk: Double?)

/** RMSSD and the R(k) half of the REM window. Test hook. */
fun windowRmssdRkForTest(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
): WindowRmssdRk = WindowRmssdRk(
    rmssd = windowRmssd(rrMs, rrTsMs, accel, s, t),
    rk = windowRemFeatures(rrMs, rrTsMs, accel, s, t).rk,
)

/**
 * Published Webster table (15 min bridges ≤4, 10 bridges ≤3, 4 bridges ≤1).
 * Context is read from a snapshot so bridging cannot cascade.
 */
fun websterRescoreCardio(sm: MutableList<OsStage>, epochSec: Int) {
    websterRescore(sm, epochSec)
}

private fun websterRescore(sm: MutableList<OsStage>, epochSec: Int) {
    fun isSleep(s: OsStage): Boolean = s != OsStage.WAKE
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
    fun runBefore(i: Int): Int {
        var c = 0
        var k = i - 1
        while (k >= onset && isSleep(snap[k])) {
            c++
            k--
        }
        return c
    }
    fun runAfter(i: Int): Int {
        var c = 0
        var k = i + 1
        while (k <= lastSleep && isSleep(snap[k])) {
            c++
            k++
        }
        return c
    }
    val rules = listOf(
        doubleArrayOf(minToEp(15.0), minToEp(4.0)),
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
        while (j <= lastSleep && !isSleep(snap[j])) j++
        val wakeLen = (j - i).toDouble()
        val ctx = max(runBefore(i), runAfter(j - 1)).toDouble()
        for (r in rules) {
            if (ctx >= r[0] && wakeLen <= r[1]) {
                for (k in i until j) sm[k] = OsStage.NREM
                break
            }
        }
        i = j
    }
}

private class RemFeatures(val lfhf: Double?, val rk: Double?)

private fun windowRemFeatures(
    rrMs: List<Double>,
    rrTsMs: List<Double>,
    accel: List<AccelSample>,
    s: Int,
    t: Int,
): RemFeatures {
    val win = cleanBeatsInWindow(rrMs, rrTsMs, accel, s, t, halfWinMs = 90 * 1000)
    val beats = win.beats
    val beatTsSec = win.tsSec
    if (beats.size < 16) return RemFeatures(null, null)
    var rkSum = 0.0
    var rkCnt = 0
    var prevIhr: Double? = null
    for (k in beats.indices) {
        val ihr = 60000.0 / beats[k]
        if (prevIhr != null && !win.seam[k]) {
            rkSum += abs(ihr - prevIhr)
            rkCnt++
        }
        prevIhr = ihr
    }
    val rk = if (rkCnt >= 15) rkSum / rkCnt else null
    var lfhf: Double? = null
    val spanSec = beatTsSec.last() - beatTsSec.first()
    if (spanSec > 0.0) {
        val loHz = (1.0 / spanSec).coerceIn(0.0005, 0.04)
        val ls = lombScargle(beatTsSec, beats, freqGrid(loHz, 0.4, 240))
        if (ls != null) {
            val lf = ls.bandPower(0.04, 0.15)
            val hf = ls.bandPower(0.15, 0.40)
            if (hf > 0.0) lfhf = lf / hf
        }
    }
    return RemFeatures(lfhf, rk)
}

private fun modeFilterStages(s: MutableList<OsStage>) {
    val n = s.size
    if (n < 3) return
    val out = s.toMutableList()
    for (i in 1 until n - 1) {
        if (s[i - 1] == s[i + 1] && s[i] != s[i - 1]) out[i] = s[i - 1]
    }
    for (i in 0 until n) s[i] = out[i]
}

private fun mergeShortDeep(deepFlag: MutableList<Boolean>, sm: List<OsStage>, epochSec: Int) {
    val minEp = roundHalfAwayFromZero(3 * 60.0 / epochSec).toInt()
    if (minEp <= 1) return
    val n = deepFlag.size
    var i = 0
    while (i < n) {
        if (!deepFlag[i] || i >= sm.size || sm[i] != OsStage.NREM) {
            i++
            continue
        }
        var j = i
        while (j < n && deepFlag[j] && j < sm.size && sm[j] == OsStage.NREM) j++
        if ((j - i) < minEp) {
            for (k in i until j) deepFlag[k] = false
        }
        i = j
    }
}
