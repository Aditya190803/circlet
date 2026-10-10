package app.circlet.core.fake

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DayHeart
import app.circlet.core.DayStress
import app.circlet.core.DaySummary
import app.circlet.core.FrequencyDomain
import app.circlet.core.HeartRateRecovery
import app.circlet.core.Hrv
import app.circlet.core.HrvSpotCheck
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.Metric
import app.circlet.core.Recovery
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.SleepConsistency
import app.circlet.core.SleepCycle
import app.circlet.core.SleepDebt
import app.circlet.core.SleepEpoch
import app.circlet.core.SleepKind
import app.circlet.core.SleepNeed
import app.circlet.core.SleepPerformance
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.Sport
import app.circlet.core.Steps
import app.circlet.core.Strain
import app.circlet.core.StressPoint
import app.circlet.core.TargetStrain
import app.circlet.core.Vo2Max
import app.circlet.core.WakeEvent
import app.circlet.core.WellnessFlag
import app.circlet.core.WellnessKind
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class GeneratedHistory(
    val days: Map<LocalDate, DaySummary>,
    val spotChecks: Map<LocalDate, List<HrvSpotCheck>>,
    val tags: List<JournalTag>,
    val entries: Map<LocalDate, JournalEntry>,
)

internal fun generateHistory(today: LocalDate, zone: ZoneId, scenario: FakeScenario): GeneratedHistory {
    val dates = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val nights = dates.mapIndexed { index, date ->
        planNight(date, index, zone, scenario)
    }
    val byMorning = nights.filterNotNull().associateBy { it.date }
    val days = LinkedHashMap<LocalDate, DaySummary>()
    val spots = LinkedHashMap<LocalDate, List<HrvSpotCheck>>()
    val bedMinutes = ArrayList<Int>()
    val debtWindow = ArrayList<Pair<Int, Int>>()
    for ((index, date) in dates.withIndex()) {
        val night = byMorning[date]
        if (night == null) {
            val reason = if (scenario == FakeScenario.UNPAIRED) AbsentReason.STRAP_NOT_SYNCED else AbsentReason.NOT_ENOUGH_DATA
            val calibration = if (scenario == FakeScenario.CALIBRATING) CalibrationStatus.CALIBRATING else {
                if (byMorning.isNotEmpty()) CalibrationStatus.READY else CalibrationStatus.CALIBRATING
            }
            days[date] = emptyDay(date, reason, calibration)
            spots[date] = emptyList()
            continue
        }
        bedMinutes += night.bedMinute
        debtWindow += night.needMin to night.asleepMin
        val next = byMorning[date.plusDays(1)]
        val day = assemble(date, zone, night, next, index, scenario, bedMinutes.toList(), debtWindow.toList())
        days[date] = day
        spots[date] = if (index % 5 == 0) listOf(spotCheck(date, zone, night.hrv)) else emptyList()
    }
    return GeneratedHistory(
        days = days,
        spotChecks = spots,
        tags = demoTags(),
        entries = if (scenario == FakeScenario.UNPAIRED) emptyMap() else demoEntries(dates, scenario),
    )
}

private data class NightPlan(
    val date: LocalDate,
    val rest: Int,
    val hrv: Double,
    val resp: Double,
    val needMin: Int,
    val asleepMin: Int,
    val bedMinute: Int,
    val session: SleepSession,
    val nap: SleepSession?,
    val activities: List<Planned>,
    val poor: Boolean,
)

private data class Planned(
    val sport: Sport,
    val start: Instant,
    val end: Instant,
    val bpm: Int,
)

private fun planNight(date: LocalDate, index: Int, zone: ZoneId, scenario: FakeScenario): NightPlan? {
    if (scenario == FakeScenario.UNPAIRED) return null
    if (scenario == FakeScenario.NO_DATA_TODAY && index == 13) return null
    val seed = date.toEpochDay()
    val poor = index == 4
    val asleep = if (poor) 300 else 420 + unit(seed, 1, 80)
    val need = if (poor) 490 else 460 + unit(seed, 2, 20)
    val rest = if (poor) 66 else 46 + unit(seed, 3, 12)
    val hrv = if (poor) 28.0 else 42.0 + unit(seed, 4, 30)
    val resp = if (poor) 16.4 else 13.8 + unit(seed, 5, 24) / 10.0
    val latency = 8 + unit(seed, 6, 12)
    val bedMinute = 22 * 60 + 20 + unit(seed, 7, 70)
    val start = date.minusDays(1).atTime(bedMinute / 60, bedMinute % 60).atZone(zone).toInstant()
    val session = buildMainSleep("sleep-$date", start, asleep, latency, listOf(5, 3))
    val nap = if (!poor && index % 5 == 0) {
        val napStart = date.atTime(14, 30).atZone(zone).toInstant()
        buildNap("nap-$date", napStart, 28)
    } else {
        null
    }
    val activities = plannedActivities(date, zone, index, rest, poor)
    return NightPlan(date, rest, hrv, resp, need, session.timeAsleep.toMinutes().toInt(), bedMinute, session, nap, activities, poor)
}

private fun plannedActivities(date: LocalDate, zone: ZoneId, index: Int, rest: Int, poor: Boolean): List<Planned> {
    if (poor) {
        return listOf(planned(date, zone, Sport.RUNNING, 17, 0, 50, 172))
    }
    return when (index % 4) {
        1 -> emptyList()
        2 -> listOf(planned(date, zone, Sport.WALKING, 12, 20, 36, (rest + 73).coerceAtMost(140)))
        else -> listOf(planned(date, zone, Sport.RUNNING, 18, 5, 34, (rest + 106).coerceAtMost(176)))
    }
}

private fun planned(date: LocalDate, zone: ZoneId, sport: Sport, hour: Int, minute: Int, durationMin: Int, bpm: Int): Planned {
    val start = date.atTime(hour, minute).atZone(zone).toInstant()
    return Planned(sport, start, start.plus(Duration.ofMinutes(durationMin.toLong())), bpm)
}

private fun assemble(
    date: LocalDate,
    zone: ZoneId,
    night: NightPlan,
    next: NightPlan?,
    index: Int,
    scenario: FakeScenario,
    bedMinutes: List<Int>,
    debtWindow: List<Pair<Int, Int>>,
): DaySummary {
    val series = paint(date, zone, night, next)
    val present = series.filterNotNull()
    if (present.isEmpty()) {
        return emptyDay(date, AbsentReason.NOT_ENOUGH_DATA, CalibrationStatus.READY)
    }
    val rest = night.rest
    val zones = ZoneMinutes(List(6) { zone -> present.count { zoneIndex(it, rest) == zone } })
    val load = present.sumOf { weight(it, rest) }
    val strainScore = strainFrom(load)
    val activities = night.activities.mapIndexed { n, plan ->
        toActivity(date, plan, n, series, zone, rest)
    }
    val asleep = night.asleepMin
    val performance = ((asleep * 100.0) / night.needMin).roundToInt()
    val calibrating = scenario == FakeScenario.CALIBRATING
    val recovery = if (calibrating) {
        Recovery(
            score = Metric.absent(AbsentReason.CALIBRATING),
            calibration = CalibrationStatus.CALIBRATING,
            drivers = RecoveryDriverKind.entries.map { RecoveryDriver(it, null, AbsentReason.CALIBRATING) },
        )
    } else {
        val terms = driverTerms(night.hrv, rest, performance, night.resp)
        val score = (45.0 + terms.values.sum()).roundToInt().coerceIn(1, 99)
        Recovery(
            score = Metric.of(score, Confidence.HIGH),
            calibration = CalibrationStatus.READY,
            drivers = RecoveryDriverKind.entries.map { kind ->
                RecoveryDriver(kind, terms.getValue(kind), null)
            },
        )
    }
    val calories = 1400 +
        zones.minutes[1] * 4 + zones.minutes[2] * 6 + zones.minutes[3] * 9 +
        zones.minutes[4] * 12 + zones.minutes[5] * 15
    val asleepToday = minutesOnDate(night.session, date, zone) +
        (night.nap?.let { minutesOnDate(it, date, zone) } ?: 0)
    val steps = ((present.size - asleepToday).coerceAtLeast(0) * 7 + activities.sumOf { Duration.between(it.start, it.end).toMinutes().toInt() } * 30)
        .coerceAtLeast(0)
    val dayStart = date.atStartOfDay(zone).toInstant()
    val stressPoints = (0 until 24).mapNotNull { hour ->
        val slice = series.subList(hour * 60, hour * 60 + 60).filterNotNull()
        if (slice.isEmpty()) null
        else StressPoint(
            dayStart.plus(Duration.ofHours(hour.toLong())),
            ((slice.average() - rest) / 50.0 * 100.0).coerceIn(0.0, 100.0),
        )
    }
    val recentDebt = debtWindow.takeLast(3).sumOf { (need, slept) -> (need - slept).coerceAtLeast(0) }
    val consistency = consistency(bedMinutes)
    val bedtime = date.plusDays(1).atTime(7, 0).atZone(zone).toInstant()
        .minus(Duration.ofMinutes(night.needMin.toLong()))
        .minus(Duration.ofMinutes(10))
    val flags = ArrayList<WellnessFlag>()
    val recoveryScore = recovery.score.value
    if (recoveryScore != null && recoveryScore < 40 && strainScore > 14.0) {
        flags += WellnessFlag(WellnessKind.OVERREACHING, Confidence.MEDIUM, "Recovery is low after a hard day")
    }
    if (recoveryScore != null && rest >= 65 && night.hrv < 32.0) {
        flags += WellnessFlag(WellnessKind.ILLNESS, Confidence.LOW, "Resting heart rate is up and HRV is down")
    }
    val hrvConfidence = if (asleep >= 240) Confidence.HIGH else Confidence.MEDIUM
    return DaySummary(
        date = date,
        sleep = Metric.of(night.session, Confidence.HIGH),
        naps = listOfNotNull(night.nap),
        recovery = recovery,
        strain = Strain(Metric.of(strainScore, if (present.size > 480) Confidence.HIGH else Confidence.MEDIUM), zones),
        stress = DayStress(
            score = if (stressPoints.isEmpty()) Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
            else Metric.of(stressPoints.map { it.score }.average(), Confidence.MEDIUM),
            timeline = stressPoints,
        ),
        heart = DayHeart(
            resting = RestingHeartRate(Metric.of(rest, Confidence.HIGH)),
            hrv = hrvOf(night.hrv, hrvConfidence),
            respiratoryRate = RespiratoryRate(Metric.of(night.resp, Confidence.HIGH)),
            averageBpm = Metric.of(present.average().roundToInt(), Confidence.HIGH),
            maxBpm = Metric.of(present.maxOrNull() ?: rest, Confidence.HIGH),
        ),
        steps = Steps(Metric.of(steps, Confidence.LOW)),
        calories = Metric.of(calories, Confidence.MEDIUM),
        activities = activities,
        heartRateByMinute = series,
        sleepNeed = SleepNeed(Metric.of(Duration.ofMinutes(night.needMin.toLong()), Confidence.MEDIUM), bedtime),
        sleepDebt = SleepDebt(Metric.of(Duration.ofMinutes(recentDebt.toLong()), Confidence.MEDIUM)),
        sleepPerformance = SleepPerformance(Metric.of(performance, if (asleep >= 240) Confidence.HIGH else Confidence.MEDIUM)),
        sleepConsistency = consistency,
        targetStrain = if (recoveryScore == null) {
            TargetStrain(Metric.absent(recovery.score.absentReason ?: AbsentReason.CALIBRATING))
        } else {
            TargetStrain(Metric.of((8.0 + recoveryScore / 100.0 * 10.0).coerceIn(0.0, 21.0), Confidence.MEDIUM))
        },
        vo2Max = Vo2Max(Metric.of(41.0 + (index % 5) * 0.4, Confidence.LOW)),
        wellness = flags,
    )
}

private fun paint(date: LocalDate, zone: ZoneId, night: NightPlan, next: NightPlan?): List<Int?> {
    val bpm = IntArray(1440) { -1 }
    val dayStart = date.atStartOfDay(zone).toInstant()
    fun indexOf(instant: Instant): Int? {
        val minute = Duration.between(dayStart, instant).toMinutes()
        return if (minute in 0..1439) minute.toInt() else null
    }
    fun fill(from: Instant, until: Instant, value: Int) {
        var cursor = from
        while (cursor < until) {
            val index = indexOf(cursor)
            if (index != null) bpm[index] = value
            cursor = cursor.plus(Duration.ofMinutes(1))
        }
    }
    for (minute in 7 * 60 until 23 * 60) bpm[minute] = night.rest + 35
    paintSession(night.session, night.rest, ::fill)
    next?.let { paintSession(it.session, it.rest, ::fill) }
    night.nap?.let { paintSession(it, night.rest, ::fill) }
    for (activity in night.activities) fill(activity.start, activity.end, activity.bpm)
    val baseline = night.rest + 35
    for (activity in night.activities) {
        val index = indexOf(activity.end) ?: continue
        if (bpm[index] == baseline) bpm[index] = (activity.bpm - 28).coerceAtLeast(night.rest + 10)
    }
    return bpm.map { if (it < 0) null else it }
}

private fun paintSession(session: SleepSession, rest: Int, fill: (Instant, Instant, Int) -> Unit) {
    for (epoch in session.epochs) {
        val bpm = if (epoch.stage == SleepStage.AWAKE) rest + 8 else rest
        fill(epoch.start, epoch.start.plus(epoch.duration), bpm)
    }
}

private fun toActivity(date: LocalDate, plan: Planned, index: Int, series: List<Int?>, zone: ZoneId, rest: Int): Activity {
    val dayStart = date.atStartOfDay(zone).toInstant()
    val startMinute = Duration.between(dayStart, plan.start).toMinutes().toInt()
    val endMinute = Duration.between(dayStart, plan.end).toMinutes().toInt()
    val samples = (startMinute until endMinute).map { series[it] ?: plan.bpm }
    val zones = ZoneMinutes(List(6) { z -> samples.count { zoneIndex(it, rest) == z } })
    val score = strainFrom(samples.sumOf { weight(it, rest) })
    val after = series.getOrNull(endMinute)
    val drop = if (after != null && after < plan.bpm) plan.bpm - after else null
    val kcal = (zones.minutes[1] * 4 + zones.minutes[2] * 6 + zones.minutes[3] * 9 + zones.minutes[4] * 12 + zones.minutes[5] * 15)
        .coerceAtLeast(samples.size * 4)
    return Activity(
        id = "act-$date-$index",
        source = ActivitySource.AUTO,
        sport = plan.sport,
        sportConfidence = Confidence.MEDIUM,
        start = plan.start,
        end = plan.end,
        strain = Metric.of(score, Confidence.MEDIUM),
        averageHeartRateBpm = Metric.of(samples.average().roundToInt(), Confidence.HIGH),
        maxHeartRateBpm = Metric.of(samples.maxOrNull() ?: plan.bpm, Confidence.HIGH),
        calories = Metric.of(kcal, Confidence.MEDIUM),
        zoneMinutes = zones,
        heartRateRecovery = drop?.let { HeartRateRecovery(Metric.of(it, Confidence.MEDIUM)) },
    )
}

private fun buildMainSleep(id: String, start: Instant, asleepMin: Int, latencyMin: Int, wakes: List<Int>): SleepSession {
    val blocks = ArrayList<Pair<SleepStage, Int>>()
    blocks += SleepStage.AWAKE to latencyMin
    var remain = asleepMin
    val cycles = 4
    for (cycle in 0 until cycles) {
        val share = if (cycle == cycles - 1) remain else asleepMin / cycles
        remain -= share
        val deep = share * (if (cycle < 2) 35 else 15) / 100
        val rem = share * (if (cycle == 0) 10 else 30) / 100
        val light = share - deep - rem
        if (light > 0) blocks += SleepStage.LIGHT to light
        if (deep > 0) blocks += SleepStage.DEEP to deep
        if (rem > 0) blocks += SleepStage.REM to rem
        if (cycle < wakes.size && wakes[cycle] > 0) blocks += SleepStage.AWAKE to wakes[cycle]
    }
    return sessionFrom(id, SleepKind.MAIN, start, blocks)
}

private fun buildNap(id: String, start: Instant, minutes: Int): SleepSession =
    sessionFrom(id, SleepKind.NAP, start, listOf(SleepStage.LIGHT to minutes))

private fun sessionFrom(id: String, kind: SleepKind, start: Instant, blocks: List<Pair<SleepStage, Int>>): SleepSession {
    val epochs = ArrayList<SleepEpoch>()
    var cursor = start
    for ((stage, minutes) in blocks) {
        if (minutes <= 0) continue
        val duration = Duration.ofMinutes(minutes.toLong())
        epochs += SleepEpoch(cursor, stage, duration)
        cursor = cursor.plus(duration)
    }
    var asleep = 0L
    var inBed = 0L
    for (epoch in epochs) {
        val minutes = epoch.duration.toMinutes()
        inBed += minutes
        if (epoch.stage != SleepStage.AWAKE) asleep += minutes
    }
    val latencyMin = epochs.takeWhile { it.stage == SleepStage.AWAKE }.sumOf { it.duration.toMinutes() }
    val wakes = ArrayList<WakeEvent>()
    var fellAsleep = false
    for (epoch in epochs) {
        if (epoch.stage != SleepStage.AWAKE) fellAsleep = true
        else if (fellAsleep) wakes += WakeEvent(epoch.start, epoch.duration)
    }
    val cycles = ArrayList<SleepCycle>()
    var index = 0
    while (index < epochs.size && epochs[index].stage == SleepStage.AWAKE) index++
    var number = 1
    while (index < epochs.size) {
        val cycleStart = epochs[index].start
        while (index < epochs.size && epochs[index].stage != SleepStage.AWAKE) index++
        val last = epochs[index - 1]
        cycles += SleepCycle(number, cycleStart, last.start.plus(last.duration))
        number++
        while (index < epochs.size && epochs[index].stage == SleepStage.AWAKE) index++
    }
    val efficiency = ((asleep * 100.0) / inBed).roundToInt()
    return SleepSession(
        id = id,
        kind = kind,
        start = start,
        end = cursor,
        epochs = epochs,
        cycles = cycles,
        latency = Duration.ofMinutes(latencyMin),
        efficiency = Metric.of(efficiency, if (inBed >= 180) Confidence.HIGH else Confidence.MEDIUM),
        wakeEvents = wakes,
        timeInBed = Duration.ofMinutes(inBed),
        timeAsleep = Duration.ofMinutes(asleep),
    )
}

private fun driverTerms(hrv: Double, rest: Int, performance: Int, resp: Double): Map<RecoveryDriverKind, Double> = mapOf(
    RecoveryDriverKind.HRV to (hrv - 50.0) * 0.4,
    RecoveryDriverKind.RESTING_HEART_RATE to (55 - rest) * 0.5,
    RecoveryDriverKind.SLEEP to (performance - 85) * 0.15,
    RecoveryDriverKind.RESPIRATORY_RATE to (15.5 - resp) * 0.4,
)

private fun consistency(bedMinutes: List<Int>): SleepConsistency {
    if (bedMinutes.size < 4) return SleepConsistency(Metric.absent(AbsentReason.NOT_ENOUGH_DATA))
    val mean = bedMinutes.average()
    val variance = bedMinutes.sumOf { (it - mean) * (it - mean) } / bedMinutes.size
    val index = (100.0 - sqrt(variance) / 1.5).coerceIn(0.0, 100.0)
    val confidence = if (bedMinutes.size >= 7) Confidence.HIGH else Confidence.MEDIUM
    return SleepConsistency(Metric.of(index, confidence))
}

private fun hrvOf(rmssd: Double, confidence: Confidence): Hrv {
    val hf = rmssd * rmssd * 0.35
    val lf = hf * 1.4
    return Hrv(
        rmssdMs = Metric.of(rmssd, confidence),
        sdnnMs = Metric.of(rmssd * 1.2, confidence),
        frequency = FrequencyDomain(lfMs2 = lf, hfMs2 = hf, lfHfRatio = lf / hf),
    )
}

private fun spotCheck(date: LocalDate, zone: ZoneId, nightlyRmssd: Double): HrvSpotCheck = HrvSpotCheck(
    id = "spot-$date",
    at = date.atTime(16, 0).atZone(zone).toInstant(),
    duration = Duration.ofMinutes(2),
    hrv = hrvOf(nightlyRmssd + 3.0, Confidence.MEDIUM),
)

private fun minutesOnDate(session: SleepSession, date: LocalDate, zone: ZoneId): Int {
    val dayStart = date.atStartOfDay(zone).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
    var count = 0
    for (epoch in session.epochs) {
        if (epoch.stage == SleepStage.AWAKE) continue
        var cursor = epoch.start
        val end = epoch.start.plus(epoch.duration)
        while (cursor < end) {
            if (!cursor.isBefore(dayStart) && cursor.isBefore(dayEnd)) count++
            cursor = cursor.plus(Duration.ofMinutes(1))
        }
    }
    return count
}

private fun zoneIndex(bpm: Int, rest: Int): Int {
    val frac = (bpm - rest).toDouble() / (MAX_HR - rest).coerceAtLeast(40)
    return when {
        frac < 0.5 -> 0
        frac < 0.6 -> 1
        frac < 0.7 -> 2
        frac < 0.8 -> 3
        frac < 0.9 -> 4
        else -> 5
    }
}

private fun weight(bpm: Int, rest: Int): Double {
    val frac = (bpm - rest).toDouble() / (MAX_HR - rest).coerceAtLeast(40)
    return when {
        frac < 0.2 -> 0.0
        frac < 0.3 -> 0.1
        frac < 0.5 -> 0.3
        frac < 0.6 -> 1.0
        frac < 0.7 -> 2.0
        frac < 0.8 -> 3.0
        frac < 0.9 -> 4.0
        else -> 5.0
    }
}

private fun strainFrom(load: Double): Double = 21.0 * (1.0 - exp(-load / 200.0))

private fun unit(seed: Long, salt: Int, span: Int): Int {
    var x = (seed.toInt() xor (salt * 0x45d9f3b))
    x = (x xor (x ushr 16)) * 0x45d9f3b
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) % span
}

private fun demoTags(): List<JournalTag> = listOf(
    JournalTag("caffeine", "Caffeine", builtIn = true),
    JournalTag("alcohol", "Alcohol", builtIn = true),
    JournalTag("late-meal", "Late meal", builtIn = true),
    JournalTag("travel", "Travel", builtIn = true),
    JournalTag("stressed", "Stressed", builtIn = true),
    JournalTag("sick", "Sick", builtIn = true),
)

private fun demoEntries(dates: List<LocalDate>, scenario: FakeScenario): Map<LocalDate, JournalEntry> {
    val entries = LinkedHashMap<LocalDate, JournalEntry>()
    dates.forEachIndexed { index, date ->
        if (scenario == FakeScenario.NO_DATA_TODAY && index == 13) return@forEachIndexed
        val tags = LinkedHashSet<String>()
        if (index % 2 == 0) tags += "caffeine"
        if (index == 4 || index == 10) tags += "alcohol"
        if (index == 2) tags += "late-meal"
        if (tags.isEmpty()) return@forEachIndexed
        val note = if (index == 2) "Early night." else ""
        entries[date] = JournalEntry(date, tags, note)
    }
    return entries
}

private const val MAX_HR = 185
