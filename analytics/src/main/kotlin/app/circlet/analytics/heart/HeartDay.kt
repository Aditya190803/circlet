// Day-level heart entry. Composes OpenStrap clinical/hrv_time.dart (windowed RMSSD,
// SDNN, RR-coverage refusal), clinical/hrv_freq.dart (Lomb–Scargle LF/HF),
// clinical/nocturnal.dart (lowest 30-min mean), and respiration/resp_rate.dart (RSA).
// Time zone and "now" are arguments. This file does not read a clock.

package app.circlet.analytics.heart

import app.circlet.analytics.foundations.correctRr
import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.analytics.sleep.alignSamples
import app.circlet.analytics.sleep.coreValue
import app.circlet.analytics.sleep.reasonFromNote
import app.circlet.analytics.sleep.segmentSleep
import app.circlet.analytics.sleep.zoneOffsets
import app.circlet.core.AbsentReason
import app.circlet.core.DayHeart
import app.circlet.core.FrequencyDomain
import app.circlet.core.Hrv
import app.circlet.core.HrvSpotCheck
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.measurementBpm
import app.circlet.core.Sample
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Heart maths for [date].
 *
 * [samples] run from 18:00 local on the previous day through the end of [date].
 * Pass both [sleepStart] and [sleepEnd] to score the night that sleep already found.
 * Omit them and this detects that window itself. [profile] is accepted so a day
 * engine can pass one [app.circlet.core.DayInput]; these methods do not read it.
 * [now] drops any sample after that instant.
 */
@Suppress("UNUSED_PARAMETER")
fun computeHeart(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    profile: Profile,
    sleepStart: Instant? = null,
    sleepEnd: Instant? = null,
): DayHeart = computeHeart(samples, alignSamples(samples, now), zone, date, now, profile, sleepStart, sleepEnd)

/**
 * [computeHeart] over an already-aligned series. The day engine aligns once
 * and shares the series with sleep, instead of sorting the day twice.
 */
internal fun computeHeart(
    samples: List<Sample>,
    series: app.circlet.analytics.sleep.AlignedSeries,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    profile: Profile,
    sleepStart: Instant? = null,
    sleepEnd: Instant? = null,
): DayHeart {
    val dayRates = dayRates(samples, zone, date, now)
    val average = rateMetric(dayRates, ::averageOf)
    val max = rateMetric(dayRates, ::maxOf)
    if (!series.worn) {
        val absent = Metric.absent<Double>(AbsentReason.NOT_WORN)
        val absentInt = Metric.absent<Int>(AbsentReason.NOT_WORN)
        return DayHeart(
            resting = RestingHeartRate(absentInt),
            hrv = Hrv(absent, absent, null),
            respiratoryRate = RespiratoryRate(absent),
            averageBpm = average,
            maxBpm = max,
        )
    }
    val window = sleepWindow(series, zone, sleepStart, sleepEnd)
    if (window == null) {
        val absent = Metric.absent<Double>(AbsentReason.NO_SLEEP)
        val absentInt = Metric.absent<Int>(AbsentReason.NO_SLEEP)
        return DayHeart(
            resting = RestingHeartRate(absentInt),
            hrv = Hrv(absent, absent, null),
            respiratoryRate = RespiratoryRate(absent),
            averageBpm = average,
            maxBpm = max,
        )
    }
    return nightHeart(series, window, average, max)
}

/**
 * HRV of one short RR series, the awake spot-check.
 * Coverage is not applied: that gate needs ten minutes of wall time, and a
 * check is shorter than that. [duration] defaults to the sum of the intervals.
 */
fun spotCheck(
    rrIntervalsMs: List<Int>,
    at: Instant,
    id: String,
    duration: Duration? = null,
): HrvSpotCheck {
    val rr = ArrayList<Double>(rrIntervalsMs.size)
    val times = ArrayList<Double>(rrIntervalsMs.size)
    var cursor = at.toEpochMilli().toDouble()
    var sum = 0L
    for (interval in rrIntervalsMs) {
        val ms = interval.toDouble()
        rr.add(ms)
        cursor += ms
        times.add(cursor)
        sum += interval
    }
    val span = duration ?: Duration.ofMillis(sum)
    val corrected = correctRr(rr, times)
    val artifact = (1.0 - corrected.cleanFraction).coerceIn(0.0, 1.0)
    val time = hrvTime(corrected.nn, corrected.nnTimesMs, artifactFraction = artifact)
    val rmssd = field(time.value?.rmssd, time.present, time.note, time.confidence)
    val sdnn = sdnnField(time.value?.sdnn, time.present, time.note, time.confidence)
    val frequency = spectrum(corrected.nn, corrected.nnTimesMs, artifact, refused = false)
    return HrvSpotCheck(id, at, span, Hrv(rmssd, sdnn, frequency))
}

private data class SessionWindow(val startSec: Int, val endSec: Int)

private fun sleepWindow(
    series: app.circlet.analytics.sleep.AlignedSeries,
    zone: ZoneId,
    sleepStart: Instant?,
    sleepEnd: Instant?,
): SessionWindow? {
    if (sleepStart != null && sleepEnd != null) {
        val start = sessionSec(sleepStart) ?: return null
        val end = sessionSec(sleepEnd) ?: return null
        if (end <= start) return null
        return SessionWindow(start, end)
    }
    if (series.accel.isEmpty()) return null
    val segmentation = segmentSleep(
        accel = series.accel,
        hr1hz = series.hr,
        rrMs = series.rrMs,
        rrTsMs = series.rrTsMs,
        tzOffsetResolver = zoneOffsets(zone),
    )
    val window = segmentation.window ?: return null
    val onset = window.onsetMs ?: return null
    val offset = window.offsetMs ?: return null
    val start = sessionSec(onset / 1000.0) ?: return null
    val end = sessionSec(offset / 1000.0) ?: return null
    if (end <= start) return null
    return SessionWindow(start, end)
}

private fun nightHeart(
    series: app.circlet.analytics.sleep.AlignedSeries,
    window: SessionWindow,
    average: Metric<Int>,
    max: Metric<Int>,
): DayHeart {
    val beats = beatsIn(series, window)
    val coverage = rrCoverage(beats.rr, beats.ts)
    val overCounted = coverage?.overCounted == true
    val detail = sleepSessionRmssdDetail(series.rrMs, series.rrTsMs, window.startSec, window.endSec)
    val rmssd = if (detail.value != null) {
        coreValue(detail.value!!.rmssd, detail.confidence)
    } else {
        Metric.absent(reasonFromNote(detail.note))
    }
    val corrected = correctRr(beats.rr, beats.ts)
    val artifact = (1.0 - corrected.cleanFraction).coerceIn(0.0, 1.0)
    val time = hrvTime(
        corrected.nn,
        corrected.nnTimesMs,
        artifactFraction = artifact,
        coverage = coverage,
    )
    val sdnn = sdnnField(time.value?.sdnn, time.present, time.note, time.confidence)
    val frequency = spectrum(corrected.nn, corrected.nnTimesMs, artifact, overCounted)
    val respiratory = if (overCounted) {
        Metric.absent<Double>(AbsentReason.SIGNAL_TOO_NOISY)
    } else {
        val estimate = rsaRespRate(corrected.nn, corrected.nnTimesMs, artifact)
        val brpm = estimate.value?.brpm
        if (brpm == null) {
            Metric.absent(reasonFromNote(estimate.note))
        } else {
            coreValue(brpm, estimate.confidence)
        }
    }
    return DayHeart(
        resting = RestingHeartRate(restingOf(series, window)),
        hrv = Hrv(rmssd, sdnn, frequency),
        respiratoryRate = RespiratoryRate(respiratory),
        averageBpm = average,
        maxBpm = max,
    )
}

private fun restingOf(series: app.circlet.analytics.sleep.AlignedSeries, window: SessionWindow): Metric<Int> {
    val hr = ArrayList<Double>()
    val ts = ArrayList<Double>()
    for (i in series.hr.indices) {
        val sec = series.accel[i].tsMs / 1000.0
        if (sec >= window.startSec && sec < window.endSec) {
            hr.add(series.hr[i])
            ts.add(sec)
        }
    }
    val metric = nocturnalRhr(hr, ts)
    val mean = metric.value?.low30Mean ?: return Metric.absent(reasonFromNote(metric.note))
    return coreValue(roundHalfAwayFromZero(mean).toInt(), metric.confidence)
}

private data class BeatSeries(val rr: List<Double>, val ts: List<Double>)

private fun beatsIn(series: app.circlet.analytics.sleep.AlignedSeries, window: SessionWindow): BeatSeries {
    val rr = ArrayList<Double>()
    val ts = ArrayList<Double>()
    for (i in series.rrMs.indices) {
        val sec = roundHalfAwayFromZero(series.rrTsMs[i] / 1000.0).toInt()
        if (sec >= window.startSec && sec < window.endSec) {
            rr.add(series.rrMs[i])
            ts.add(series.rrTsMs[i])
        }
    }
    return BeatSeries(rr, ts)
}

private fun spectrum(
    nn: List<Double>,
    times: List<Double>,
    artifact: Double,
    refused: Boolean,
): FrequencyDomain? {
    if (refused || nn.size < 16) return null
    // Above the HF gate hrvFreq withholds HF, and without HF there is no
    // ratio — so this is null either way, without running the Welch averages.
    if (artifact > HF_ARTIFACT_GATE_DEFAULT) return null
    // ULF and VLF never reach DayHeart; their long Welch segments are most of
    // the work, so only LF and HF are averaged. The bands are independent, so
    // these are the values the full call would return.
    val freq = hrvFreqBands(nn, times, artifact, wantUlf = false, wantVlf = false).value ?: return null
    val lf = freq.lf ?: return null
    val hf = freq.hf ?: return null
    val ratio = freq.lfhf ?: return null
    return FrequencyDomain(lf, hf, ratio)
}

private fun field(value: Double?, present: Boolean, note: String?, confidence: Double): Metric<Double> {
    if (value != null) return coreValue(value, confidence)
    if (!present) return Metric.absent(reasonFromNote(note))
    return Metric.absent(reasonFromNote(note))
}

private fun sdnnField(value: Double?, present: Boolean, note: String?, confidence: Double): Metric<Double> {
    if (value != null) return coreValue(value, confidence)
    if (!present) return Metric.absent(reasonFromNote(note))
    return Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
}

private fun dayRates(samples: List<Sample>, zone: ZoneId, date: LocalDate, now: Instant): List<Int> {
    val nowSec = now.epochSecond
    val range = dayRange(date, zone)
    val rates = ArrayList<Int>()
    for (sample in samples) {
        val epoch = sample.epochSecond
        if (epoch > nowSec || epoch !in range) continue
        val bpm = sample.measurementBpm() ?: continue
        if (bpm > 0) rates.add(bpm)
    }
    return rates
}

/**
 * Epoch seconds of one local day. Exactly the instants whose local date is
 * [date], daylight-saving transitions included, so filtering by range is the
 * same as comparing local dates one sample at a time.
 */
private fun dayRange(date: LocalDate, zone: ZoneId): LongRange {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return start until end
}

private fun rateMetric(rates: List<Int>, reduce: (List<Int>) -> Int): Metric<Int> {
    if (rates.isEmpty()) return Metric.absent(AbsentReason.NOT_WORN)
    val confidence = when {
        rates.size >= 3600 -> 0.9
        rates.size >= 60 -> 0.6
        else -> 0.4
    }
    return coreValue(reduce(rates), confidence)
}

private fun averageOf(rates: List<Int>): Int {
    var sum = 0.0
    for (bpm in rates) sum += bpm
    return roundHalfAwayFromZero(sum / rates.size).toInt()
}

private fun maxOf(rates: List<Int>): Int {
    var max = rates[0]
    for (bpm in rates) if (bpm > max) max = bpm
    return max
}

private fun sessionSec(instant: Instant): Int? = sessionSec(instant.epochSecond.toDouble())

private fun sessionSec(seconds: Double): Int? {
    if (!seconds.isFinite() || seconds <= 0.0 || seconds > Int.MAX_VALUE) return null
    return seconds.toInt()
}
