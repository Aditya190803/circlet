// Day-level sleep entry. Composes the OpenStrap segmentation, nap, cycle, debt,
// need, performance, and Phillips SRI ports into the core sleep types.
// Time zone and "now" are arguments. This file does not read a clock.

package app.circlet.analytics.sleep

import app.circlet.analytics.foundations.roundHalfAwayFromZero
import app.circlet.core.AbsentReason
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.Profile
import app.circlet.core.Sample
import app.circlet.core.SleepConsistency
import app.circlet.core.SleepDebt
import app.circlet.core.SleepEdit
import app.circlet.core.SleepEpoch
import app.circlet.core.SleepKind
import app.circlet.core.SleepNeed
import app.circlet.core.SleepPerformance
import app.circlet.core.SleepSession as CoreSession
import app.circlet.core.SleepStage
import app.circlet.core.WakeEvent
import app.circlet.core.SleepCycle as CoreCycle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/**
 * Sleep for one local day: the main session, naps, and the need / debt /
 * performance / consistency figures that sit beside it on [app.circlet.core.DaySummary].
 */
data class SleepDay(
    val main: Metric<CoreSession>,
    val naps: List<CoreSession>,
    val need: SleepNeed,
    val debt: SleepDebt,
    val performance: SleepPerformance,
    val consistency: SleepConsistency,
)

/**
 * Sleep maths for [date].
 *
 * [samples] run from 18:00 local on the previous day through the end of [date].
 * [history] is older than [date], oldest first. [dayStrain] is today's strain on
 * the 0–21 scale; null means strain was not supplied and the need uses 0.
 * [profile] is accepted so a day engine can pass one [app.circlet.core.DayInput];
 * these sleep methods do not read age or sex.
 * [now] drops any sample after that instant.
 */
fun computeSleep(
    samples: List<Sample>,
    zone: ZoneId,
    date: LocalDate,
    now: Instant,
    profile: Profile,
    history: List<DaySummary>,
    dayStrain: Double? = null,
): SleepDay {
    val segments = segmentDay(alignSamples(samples, now), zone, date, profile, history)
    return finishSleep(segments, history, zone, date, dayStrain)
}

/**
 * The expensive half of [computeSleep]: alignment is the caller's, and the
 * output stops at the staged sessions. The day engine runs this once, then
 * [finishSleep] with the strain once the load is known, instead of staging
 * the night twice. Only the strain-fed need changes between the two calls.
 */
internal data class SleepSegments(
    val series: AlignedSeries,
    val main: Metric<CoreSession>,
    val naps: List<CoreSession>,
)

internal fun segmentDay(
    series: AlignedSeries,
    zone: ZoneId,
    date: LocalDate,
    profile: Profile,
    history: List<DaySummary>,
    edit: SleepEdit? = null,
): SleepSegments {
    profile.hashCode()
    if (!series.worn || series.accel.isEmpty()) {
        return SleepSegments(series, Metric.absent(AbsentReason.NOT_WORN), emptyList())
    }
    val offsets = zoneOffsets(zone)
    val blocks = history.mapNotNull { day ->
        val session = day.sleep.value ?: return@mapNotNull null
        MidsleepBlock(
            startSec = session.start.epochSecond.toInt(),
            endSec = session.end.epochSecond.toInt(),
            dayKey = day.date.toString(),
        )
    }
    // One immobility mask for the night window and the naps. It covers the
    // same samples with the same settings both would compute, so sharing it
    // only skips the second scan.
    val mask = immobilityMask(series.accel)
    val segmentation = segmentSleep(
        accel = series.accel,
        hr1hz = series.hr,
        rrMs = series.rrMs,
        rrTsMs = series.rrTsMs,
        habitualMidsleepSec = habitualMidsleepSecFromHistory(blocks, tzOffsetResolver = offsets),
        tzOffsetResolver = offsets,
        vanHeesMask = mask,
        bandSleepState = series.bandState.takeIf { states -> states.any { it in 0..3 } },
        forcedWindow = edit?.window?.let { (start, end) ->
            ForcedWindow(start.epochSecond.toInt(), end.epochSecond.toInt())
        },
    )
    val main = when {
        edit?.deleted == true -> Metric.absent<CoreSession>(AbsentReason.NO_SLEEP)
        !segmentation.present || segmentation.tstSec == null ->
            Metric.absent<CoreSession>(AbsentReason.NO_SLEEP)
        else -> coreValue(mainSession(date, segmentation, series), segmentation.confidence)
    }
    val rejected = edit?.rejectedNaps?.map { it.epochSecond }.orEmpty().toSet()
    val naps = napSessions(date, series, segmentation, mask).filter { it.start.epochSecond !in rejected }
    return SleepSegments(series, main, naps)
}

/** The cheap half of [computeSleep]: need, debt, performance and consistency. */
internal fun finishSleep(
    segments: SleepSegments,
    history: List<DaySummary>,
    zone: ZoneId,
    date: LocalDate,
    dayStrain: Double? = null,
): SleepDay {
    val debt = debtOf(history)
    val need = needOf(debt, segments.naps, dayStrain, history, zone, date)
    val performance = performanceOf(segments.main, need)
    val consistency = consistencyOf(history, date, zone, segments.main, segments.naps, segments.series.worn)
    return SleepDay(segments.main, segments.naps, need, debt, performance, consistency)
}

private fun mainSession(date: LocalDate, segmentation: SleepSegmentation, series: AlignedSeries): CoreSession {
    val window = segmentation.window!!
    val onsetSec = (window.onsetMs!! / 1000.0).toLong()
    val offsetSec = (window.offsetMs!! / 1000.0).toLong()
    val epochs = collapseEpochs(onsetSec, segmentation.stages4)
    val cycles = cyclesOf(onsetSec, offsetSec, series)
    val wakes = wakeEvents(onsetSec, segmentation.stages4)
    val firstAsleep = epochs.firstOrNull { it.stage != SleepStage.AWAKE }
    val start = Instant.ofEpochSecond(onsetSec)
    val latency = if (firstAsleep == null) Duration.ZERO else Duration.between(start, firstAsleep.start)
    val efficiency = segmentation.efficiencyPct?.let { pct ->
        coreValue(roundHalfAwayFromZero(pct).toInt(), segmentation.confidence)
    } ?: Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    return CoreSession(
        id = "main-$date",
        kind = SleepKind.MAIN,
        start = start,
        end = Instant.ofEpochSecond(offsetSec),
        epochs = epochs,
        cycles = cycles,
        latency = latency,
        efficiency = efficiency,
        wakeEvents = wakes,
        timeInBed = Duration.ofSeconds(segmentation.inBedSec!!.toLong()),
        timeAsleep = Duration.ofSeconds(segmentation.tstSec!!.toLong()),
    )
}

private fun collapseEpochs(onsetSec: Long, stages4: List<String>): List<SleepEpoch> {
    val epochs = ArrayList<SleepEpoch>()
    var i = 0
    while (i < stages4.size) {
        val label = stages4[i]
        var j = i + 1
        while (j < stages4.size && stages4[j] == label) j++
        if (label != "unobserved") {
            epochs.add(
                SleepEpoch(
                    start = Instant.ofEpochSecond(onsetSec + i),
                    stage = stageOf(label),
                    duration = Duration.ofSeconds((j - i).toLong()),
                ),
            )
        }
        i = j
    }
    return epochs
}

private fun stageOf(label: String): SleepStage = when (label) {
    "deep" -> SleepStage.DEEP
    "rem" -> SleepStage.REM
    "wake" -> SleepStage.AWAKE
    else -> SleepStage.LIGHT
}

private fun cyclesOf(onsetSec: Long, offsetSec: Long, series: AlignedSeries): List<CoreCycle> {
    val result = detectSleepCycles(series.rrMs, series.rrTsMs, onsetSec.toInt(), offsetSec.toInt())
    return result.cycles.mapIndexed { index, cycle ->
        CoreCycle(
            number = index + 1,
            start = Instant.ofEpochSecond(onsetSec + cycle.startMin * 60L),
            end = Instant.ofEpochSecond(onsetSec + cycle.endMin * 60L),
        )
    }
}

private fun wakeEvents(onsetSec: Long, stages4: List<String>): List<WakeEvent> {
    var first = -1
    var last = -1
    for (i in stages4.indices) {
        val asleep = stages4[i] == "light" || stages4[i] == "deep" || stages4[i] == "rem"
        if (asleep) {
            if (first < 0) first = i
            last = i
        }
    }
    if (first < 0 || last <= first) return emptyList()
    val events = ArrayList<WakeEvent>()
    var run = 0
    var runStart = 0
    fun flush() {
        if (run >= kSustainedAwakeningSec) {
            events.add(
                WakeEvent(
                    at = Instant.ofEpochSecond(onsetSec + runStart),
                    duration = Duration.ofSeconds(run.toLong()),
                ),
            )
        }
        run = 0
    }
    for (i in (first + 1) until last) {
        if (stages4[i] == "wake") {
            if (run == 0) runStart = i
            run++
        } else {
            flush()
        }
    }
    flush()
    return events
}

private fun napSessions(
    date: LocalDate,
    series: AlignedSeries,
    segmentation: SleepSegmentation,
    mask: ImmobilityMask,
): List<CoreSession> {
    val span = segmentation.window?.let { SleepWindowSpan(it.onsetIdx, it.offsetIdx) }
    val wristOff = offWristSpans(series)
    val detected = detectNapsWithMask(series.accel, series.hr, mask, mainSleep = span, wristOff = wristOff)
    val naps = detected.value ?: return emptyList()
    val origin = if (series.accel.isEmpty()) 0L else (series.accel.first().tsMs / 1000.0).toLong()
    return naps.mapIndexed { index, nap ->
        val start = Instant.ofEpochSecond(origin + nap.startSec)
        val asleep = Duration.ofSeconds(nap.tstSec.toLong())
        val inBed = Duration.ofSeconds(nap.tibSec.toLong())
        val epochs = ArrayList<SleepEpoch>()
        epochs.add(SleepEpoch(start, SleepStage.LIGHT, asleep))
        val remainder = nap.tibSec - nap.tstSec
        if (remainder > 0) {
            epochs.add(
                SleepEpoch(
                    start = start.plus(asleep),
                    stage = SleepStage.AWAKE,
                    duration = Duration.ofSeconds(remainder.toLong()),
                ),
            )
        }
        val efficiencyPct = if (nap.tibSec <= 0) 0.0 else 100.0 * nap.tstSec / nap.tibSec
        CoreSession(
            id = "nap-$date-$index",
            kind = SleepKind.NAP,
            start = start,
            end = start.plus(inBed),
            epochs = epochs,
            cycles = emptyList(),
            latency = Duration.ZERO,
            efficiency = coreValue(roundHalfAwayFromZero(efficiencyPct).toInt(), nap.confidence),
            wakeEvents = emptyList(),
            timeInBed = inBed,
            timeAsleep = asleep,
        )
    }
}

/** Off-wrist runs as absolute `[startSec, endSec)` spans. Heart rate 0 is off-wrist. */
private fun offWristSpans(series: AlignedSeries): List<List<Int>> {
    val spans = ArrayList<List<Int>>()
    var start: Int? = null
    for (i in series.hr.indices) {
        val sec = (series.accel[i].tsMs / 1000.0).toInt()
        if (series.hr[i] <= 0.0) {
            if (start == null) start = sec
        } else if (start != null) {
            spans.add(listOf(start, sec))
            start = null
        }
    }
    if (start != null && series.accel.isNotEmpty()) {
        val last = (series.accel.last().tsMs / 1000.0).toInt() + 1
        spans.add(listOf(start, last))
    }
    return spans
}

private fun debtOf(history: List<DaySummary>): SleepDebt {
    val recent = history.takeLast(7).mapNotNull { hoursOf(it) }
    val free = history.filter { isFreeDay(it.date) }.mapNotNull { hoursOf(it) }
    val metric = sleepDebt(recent, free)
    val value = metric.value
    if (value == null) {
        return SleepDebt(Metric.absent(reasonFromNote(metric.note)))
    }
    val hours = value.debtHours
    if (hours == null) {
        return SleepDebt(Metric.absent(AbsentReason.CALIBRATING))
    }
    val shortfall = Duration.ofSeconds(roundHalfAwayFromZero(max(0.0, hours) * 3600.0).toLong())
    return SleepDebt(coreValue(shortfall, metric.confidence))
}

private fun hoursOf(day: DaySummary): Double? {
    val asleep = day.sleep.value?.timeAsleep ?: return null
    return asleep.seconds / 3600.0
}

private fun isFreeDay(date: LocalDate): Boolean {
    val dow = date.dayOfWeek.value
    return dow == 6 || dow == 7
}

private fun needOf(
    debt: SleepDebt,
    naps: List<CoreSession>,
    dayStrain: Double?,
    history: List<DaySummary>,
    zone: ZoneId,
    date: LocalDate,
): SleepNeed {
    val recent = history.takeLast(7).mapNotNull { hoursOf(it) }
    val free = history.filter { isFreeDay(it.date) }.mapNotNull { hoursOf(it) }
    val os = sleepDebt(recent, free)
    val osd = os.value?.osdHours
    if (os.value == null || osd == null) {
        val reason = if (os.value == null) reasonFromNote(os.note) else AbsentReason.CALIBRATING
        return SleepNeed(Metric.absent(reason), recommendedBedtime = null)
    }
    val debtSec = max(0.0, os.value.debtHours ?: 0.0) * 3600.0
    val baseline = osd.coerceIn(7.0, 9.5) * 3600.0
    val napCredit = naps.sumOf { it.timeAsleep.seconds }.toDouble()
    val need = sleepNeed(baseline, debtSec, dayStrain ?: 0.0, napCredit)
    val seconds = need.value?.needSec
    if (seconds == null) {
        return SleepNeed(Metric.absent(reasonFromNote(need.note)), recommendedBedtime = null)
    }
    val duration = coreValue(
        Duration.ofSeconds(roundHalfAwayFromZero(seconds).toLong()),
        need.confidence,
    )
    return SleepNeed(duration, bedtimeOf(seconds, history, zone, date))
}

private fun bedtimeOf(needSec: Double, history: List<DaySummary>, zone: ZoneId, date: LocalDate): Instant? {
    val wakes = ArrayList<Double>()
    val efficiencies = ArrayList<Double>()
    for (day in history) {
        val session = day.sleep.value ?: continue
        val local = session.end.atZone(zone)
        wakes.add((local.hour * 60 + local.minute).toDouble())
        val eff = session.efficiency.value ?: continue
        efficiencies.add(eff.toDouble())
    }
    if (wakes.isEmpty() || efficiencies.isEmpty()) return null
    val wake = medianOf(wakes) ?: return null
    val efficiency = medianOf(efficiencies) ?: return null
    val bed = recommendedBedtime(needSec, wake, efficiency).value ?: return null
    val bedMin = bed.bedtimeMinOfDay
    val bedDate = if (bedMin > wake) date else date.plusDays(1)
    val minute = bedMin.toInt()
    return bedDate.atTime(minute / 60, minute % 60).atZone(zone).toInstant()
}

private fun medianOf(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun performanceOf(main: Metric<CoreSession>, need: SleepNeed): SleepPerformance {
    val asleep = main.value?.timeAsleep
    val needDuration = need.duration.value
    if (asleep == null) {
        return SleepPerformance(Metric.absent(main.absentReason ?: AbsentReason.NO_SLEEP))
    }
    if (needDuration == null) {
        return SleepPerformance(Metric.absent(need.duration.absentReason ?: AbsentReason.NOT_ENOUGH_DATA))
    }
    val metric = sleepPerformance(asleep.seconds.toDouble(), needDuration.seconds.toDouble())
    val pct = metric.value?.pct ?: return SleepPerformance(Metric.absent(reasonFromNote(metric.note)))
    return SleepPerformance(coreValue(roundHalfAwayFromZero(pct).toInt(), metric.confidence))
}

private fun consistencyOf(
    history: List<DaySummary>,
    date: LocalDate,
    zone: ZoneId,
    main: Metric<CoreSession>,
    naps: List<CoreSession>,
    worn: Boolean,
): SleepConsistency {
    val days = (history.map { it.date } + date).distinct().sorted()
    if (days.size < 2) {
        return SleepConsistency(Metric.absent(AbsentReason.NOT_ENOUGH_DATA))
    }
    val indexOf = days.withIndex().associate { it.value to it.index }
    val asleep = Array(days.size) { BooleanArray(1440) }
    val valid = Array(days.size) { BooleanArray(1440) { true } }
    for (day in history) {
        val slot = indexOf[day.date] ?: continue
        if (day.sleep.absentReason == AbsentReason.NOT_WORN) {
            valid[slot].fill(false)
            continue
        }
        val session = day.sleep.value ?: continue
        paint(session, days, asleep, valid, zone)
        for (nap in day.naps) paint(nap, days, asleep, valid, zone)
    }
    val today = indexOf[date]
    if (today != null) {
        if (!worn) {
            valid[today].fill(false)
        } else {
            val session = main.value
            if (session != null) {
                paint(session, days, asleep, valid, zone)
                for (nap in naps) paint(nap, days, asleep, valid, zone)
            }
        }
    }
    val flat = ArrayList<Boolean>(days.size * 1440)
    val mask = ArrayList<Boolean>(days.size * 1440)
    for (d in days.indices) {
        for (m in 0 until 1440) {
            flat.add(asleep[d][m])
            mask.add(valid[d][m])
        }
    }
    val sri = phillipsSri(flat, epochsPerDay = 1440, valid = mask, minPairCases = 240)
    val value = sri.value ?: return SleepConsistency(Metric.absent(reasonFromNote(sri.note)))
    return SleepConsistency(coreValue((value.sri + 100.0) / 2.0, sri.confidence))
}

private fun paint(
    session: CoreSession,
    days: List<LocalDate>,
    asleep: Array<BooleanArray>,
    valid: Array<BooleanArray>,
    zone: ZoneId,
) {
    val indexOf = days.withIndex().associate { it.value to it.index }
    var t = session.start
    val end = session.end
    while (t < end) {
        val local = t.atZone(zone)
        val slot = indexOf[local.toLocalDate()]
        if (slot != null) {
            val minute = local.hour * 60 + local.minute
            val stage = stageAt(session, t)
            if (stage != null && stage != SleepStage.AWAKE) asleep[slot][minute] = true
            valid[slot][minute] = true
        }
        t = t.plusSeconds(60)
    }
}

private fun stageAt(session: CoreSession, at: Instant): SleepStage? {
    for (epoch in session.epochs) {
        val end = epoch.start.plus(epoch.duration)
        if (!at.isBefore(epoch.start) && at.isBefore(end)) return epoch.stage
    }
    return null
}
