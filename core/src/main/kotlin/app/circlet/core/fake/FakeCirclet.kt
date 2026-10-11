package app.circlet.core.fake

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.AlarmController
import app.circlet.core.BackupSettings
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.CorrelationMetric
import app.circlet.core.DataPortability
import app.circlet.core.DaySummary
import app.circlet.core.ExportResult
import app.circlet.core.FoundStrap
import app.circlet.core.Haptic
import app.circlet.core.HealthConnectStatus
import app.circlet.core.HealthKind
import app.circlet.core.HrvSpotCheck
import app.circlet.core.JournalEntry
import app.circlet.core.JournalRepository
import app.circlet.core.JournalTag
import app.circlet.core.LinkState
import app.circlet.core.LiveWorkout
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.MetricsRepository
import app.circlet.core.OnboardingChecks
import app.circlet.core.OnboardingState
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.Profile
import app.circlet.core.ProfileRepository
import app.circlet.core.reading
import app.circlet.core.RecordKind
import app.circlet.core.ScheduledAlarm
import app.circlet.core.SettingsRepository
import app.circlet.core.Sex
import app.circlet.core.Sport
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapController
import app.circlet.core.StrapEvent
import app.circlet.core.StrapState
import app.circlet.core.Strain
import app.circlet.core.SyncProgress
import app.circlet.core.TagCorrelation
import app.circlet.core.TimeSource
import app.circlet.core.TrendPoint
import app.circlet.core.TrendRange
import app.circlet.core.UserSettings
import app.circlet.core.MassUnit
import app.circlet.core.ClockStyle
import app.circlet.core.DoubleTapAction
import app.circlet.core.WeeklyRecap
import app.circlet.core.WorkoutController
import app.circlet.core.ZoneMinutes
import app.circlet.core.ageOn
import app.circlet.core.emptyDay
import app.circlet.core.validate
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.DayOfWeek
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Which demo the UI is looking at.
 * [PAIRED] is fourteen consistent days. The other three are the empty states.
 */
enum class FakeScenario {
    PAIRED,
    UNPAIRED,
    CALIBRATING,
    NO_DATA_TODAY,
}

/**
 * In-memory [app.circlet.core] backend for debug builds.
 *
 * The fourteen days are generated once, from [time] at construction, and stay
 * internally consistent: zone minutes match the heart-rate series, sleep
 * efficiency matches the epochs, and recovery moves with HRV, resting heart
 * rate, and sleep. The numbers are demo data, not the analytics module.
 *
 * Deleting or editing an activity removes or replaces the row and leaves the
 * day's strain total where it was. A stopped workout is appended and its strain
 * is added, capped at 21.
 */
class FakeCirclet(
    private val time: TimeSource,
    private val scenario: FakeScenario = FakeScenario.PAIRED,
) : MetricsRepository,
    StrapController,
    AlarmController,
    WorkoutController,
    JournalRepository,
    ProfileRepository,
    SettingsRepository,
    DataPortability,
    OnboardingState {

    private val history = generateHistory(
        time.now().atZone(time.zone()).toLocalDate(),
        time.zone(),
        scenario,
    )
    private val days = MutableStateFlow(history.days)
    private val spots = MutableStateFlow(history.spotChecks)
    private val strap = MutableStateFlow(initialStrap())
    private val scanningState = MutableStateFlow(false)
    private val found = MutableStateFlow<List<FoundStrap>>(emptyList())
    private val eventFlow = MutableSharedFlow<StrapEvent>(extraBufferCapacity = 16)
    private val alarmState = MutableStateFlow(initialAlarms())
    private val nextState = MutableStateFlow<ScheduledAlarm?>(null)
    private val liveState = MutableStateFlow<LiveWorkout?>(null)
    private val tagState = MutableStateFlow(history.tags)
    private val entryState = MutableStateFlow(history.entries)
    private val profileState = MutableStateFlow(demoProfile())
    private val settingsState = MutableStateFlow(
        UserSettings(MassUnit.KILOGRAM, ClockStyle.MATCH_SYSTEM, DoubleTapAction.STOP_ALARM, heartRateBroadcast = false),
    )
    private val healthState = MutableStateFlow(HealthConnectStatus(available = true, enabled = false, granted = emptySet()))
    private val backupState = MutableStateFlow(
        BackupSettings(enabled = false, interval = Duration.ofHours(24), folderUri = null, lastBackup = null),
    )
    private val completedState = MutableStateFlow(scenario != FakeScenario.UNPAIRED)
    private val checksState = MutableStateFlow(currentChecks())
    private var liveHeartRate = false

    init {
        publishNext()
    }

    override fun observeDay(date: LocalDate): Flow<DaySummary> =
        days.map { stored -> stored[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA) }

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> = days.map { stored ->
        if (to < from) emptyList()
        else {
            val out = ArrayList<DaySummary>()
            var date = from
            while (!date.isAfter(to)) {
                out += stored[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
                date = date.plusDays(1)
            }
            out
        }
    }

    override suspend fun trend(metric: MetricKind, range: TrendRange): Outcome<List<TrendPoint>> {
        val today = today()
        val count = if (range == TrendRange.WEEK) 7 else 30
        val start = today.minusDays((count - 1).toLong())
        val points = (0 until count).map { offset ->
            val date = start.plusDays(offset.toLong())
            val day = days.value[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
            TrendPoint(date, read(day, metric))
        }
        return Outcome.Ok(points)
    }

    override suspend fun records(): Outcome<List<PersonalRecord>> {
        val stored = days.value.values
        val out = ArrayList<PersonalRecord>()
        stored.mapNotNull { day -> day.strain.score.value?.let { day to it } }
            .maxByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.HIGHEST_STRAIN, value, day.date, null) }
        stored.mapNotNull { day -> day.sleep.value?.let { day to it.timeAsleep.toMinutes().toDouble() } }
            .maxByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.LONGEST_SLEEP, value, day.date, null) }
        stored.mapNotNull { day -> day.recovery.score.value?.let { day to it.toDouble() } }
            .maxByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.HIGHEST_RECOVERY, value, day.date, null) }
        stored.mapNotNull { day -> day.heart.hrv.rmssdMs.value?.let { day to it } }
            .maxByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.HIGHEST_HRV, value, day.date, null) }
        stored.mapNotNull { day -> day.heart.resting.bpm.value?.let { day to it.toDouble() } }
            .minByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.LOWEST_RESTING_HEART_RATE, value, day.date, null) }
        stored.mapNotNull { day -> day.steps.count.value?.let { day to it.toDouble() } }
            .maxByOrNull { it.second }
            ?.let { (day, value) -> out += PersonalRecord(RecordKind.MOST_STEPS, value, day.date, null) }
        stored.flatMap { day -> day.activities.mapNotNull { activity -> activity.maxHeartRateBpm.value?.let { day to (activity to it) } } }
            .maxByOrNull { it.second.second }
            ?.let { (day, pair) -> out += PersonalRecord(RecordKind.HIGHEST_HEART_RATE, pair.second.toDouble(), day.date, pair.first.id) }
        stored.flatMap { day ->
            day.activities.map { activity -> day to (activity to Duration.between(activity.start, activity.end).toMinutes().toDouble()) }
        }.maxByOrNull { it.second.second }
            ?.let { (day, pair) -> out += PersonalRecord(RecordKind.LONGEST_ACTIVITY, pair.second, day.date, pair.first.id) }
        return Outcome.Ok(out)
    }

    override suspend fun weeklyRecap(weekStart: LocalDate): Outcome<WeeklyRecap> {
        val week = (0..6).map { offset ->
            val date = weekStart.plusDays(offset.toLong())
            days.value[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
        }
        val debtMinutes = week.mapNotNull { it.sleepDebt.shortfall.value?.toMinutes() }
        return Outcome.Ok(
            WeeklyRecap(
                weekStart = weekStart,
                days = week,
                averageRecovery = average(week.mapNotNull { it.recovery.score.value?.toDouble() }),
                averageStrain = average(week.mapNotNull { it.strain.score.value }),
                averageSleepPerformance = average(week.mapNotNull { it.sleepPerformance.percent.value?.toDouble() }),
                averageHrvMs = average(week.mapNotNull { it.heart.hrv.rmssdMs.value }),
                sleepDebt = if (debtMinutes.isEmpty()) Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
                else Metric.of(Duration.ofMinutes(debtMinutes.sum()), Confidence.MEDIUM),
            ),
        )
    }

    override fun observeSpotChecks(date: LocalDate): Flow<List<HrvSpotCheck>> =
        spots.map { it[date].orEmpty() }

    override val state: StateFlow<StrapState> = strap
    override val scanning: StateFlow<Boolean> = scanningState
    override val scanResults: StateFlow<List<FoundStrap>> = found
    override val events: Flow<StrapEvent> = eventFlow

    override suspend fun startScan(): Outcome<Unit> {
        scanningState.value = true
        found.value = listOf(
            FoundStrap("C0:FF:EE:00:00:01", "Circlet", -62),
            FoundStrap("C0:FF:EE:00:00:02", "Circlet 2", -74),
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun stopScan(): Outcome<Unit> {
        scanningState.value = false
        return Outcome.Ok(Unit)
    }

    override suspend fun pair(strap: FoundStrap): Outcome<Unit> {
        this.strap.value = StrapState(
            link = LinkState.CONNECTED,
            name = strap.name,
            serial = "W4A0-1042",
            firmware = "1.0.0",
            batteryPercent = 76.0,
            charging = false,
            onWrist = true,
            sync = SyncProgress(samplesPulled = 0, draining = false),
        )
        refreshChecks()
        return Outcome.Ok(Unit)
    }

    override suspend fun forget(): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        liveHeartRate = false
        liveState.value = null
        strap.value = StrapState()
        refreshChecks()
        return Outcome.Ok(Unit)
    }

    override suspend fun syncNow(): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        val pulled = strap.value.sync.samplesPulled + 1_440
        strap.value = strap.value.copy(
            sync = SyncProgress(samplesPulled = pulled, draining = false),
            lastSync = time.now(),
            error = null,
        )
        refreshChecks()
        return Outcome.Ok(Unit)
    }

    override suspend fun setLiveHeartRate(enabled: Boolean): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        liveHeartRate = enabled
        applyLiveHeartRate()
        return Outcome.Ok(Unit)
    }

    override suspend fun buzz(haptic: Haptic): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        return Outcome.Ok(Unit)
    }

    override suspend fun setBroadcast(enabled: Boolean): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        settingsState.value = settingsState.value.copy(heartRateBroadcast = enabled)
        return Outcome.Ok(Unit)
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        if (strap.value.link == LinkState.UNPAIRED) return Outcome.Err(CoreError.NotPaired)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Outcome.Err(CoreError.Invalid("name is empty"))
        strap.value = strap.value.copy(name = trimmed)
        return Outcome.Ok(Unit)
    }

    /** Pushes a strap event into [events] and updates battery, charging, or wrist on [state]. */
    fun preview(event: StrapEvent) {
        eventFlow.tryEmit(event)
        strap.value = when (event) {
            is StrapEvent.Battery -> strap.value.copy(batteryPercent = event.percent)
            is StrapEvent.Charging -> strap.value.copy(charging = event.charging)
            is StrapEvent.Wrist -> strap.value.copy(
                onWrist = event.onWrist,
                liveHeartRateBpm = if (event.onWrist) strap.value.liveHeartRateBpm else null,
            )
            is StrapEvent.DoubleTap, is StrapEvent.HighFrequencySyncPrompt -> strap.value
        }
    }

    override val alarms: StateFlow<List<StrapAlarm>> = alarmState
    override val next: StateFlow<ScheduledAlarm?> = nextState

    override suspend fun set(alarm: StrapAlarm): Outcome<Unit> {
        if (alarm.id.isBlank()) return Outcome.Err(CoreError.Invalid("alarm id is empty"))
        if (alarm.weekdays.isEmpty()) return Outcome.Err(CoreError.Invalid("alarm has no weekdays"))
        if (alarm.smartWake.isNegative) return Outcome.Err(CoreError.Invalid("smart wake cannot be negative"))
        alarmState.value = alarmState.value.filter { it.id != alarm.id } + alarm
        publishNext()
        return Outcome.Ok(Unit)
    }

    override suspend fun disable(id: String): Outcome<Unit> {
        val alarm = alarmState.value.find { it.id == id } ?: return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        alarmState.value = alarmState.value.map { if (it.id == alarm.id) it.copy(enabled = false) else it }
        publishNext()
        return Outcome.Ok(Unit)
    }

    override suspend fun remove(id: String): Outcome<Unit> {
        if (alarmState.value.none { it.id == id }) return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        alarmState.value = alarmState.value.filter { it.id != id }
        publishNext()
        return Outcome.Ok(Unit)
    }

    override suspend fun setSmartWake(id: String, window: Duration): Outcome<Unit> {
        if (window.isNegative) return Outcome.Err(CoreError.Invalid("smart wake cannot be negative"))
        val alarm = alarmState.value.find { it.id == id } ?: return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        alarmState.value = alarmState.value.map { if (it.id == alarm.id) it.copy(smartWake = window) else it }
        publishNext()
        return Outcome.Ok(Unit)
    }

    override val live: StateFlow<LiveWorkout?> = liveState

    override suspend fun start(sport: Sport): Outcome<Unit> {
        if (liveState.value != null) return Outcome.Err(CoreError.Busy)
        val hr = if (strap.value.link == LinkState.CONNECTED && strap.value.onWrist != false) 132 else null
        liveState.value = LiveWorkout(
            sport = sport,
            startedAt = time.now(),
            heartRateBpm = hr,
            strain = 0.0,
            caloriesKcal = 0,
            zoneMinutes = ZoneMinutes.ZERO,
            averageHeartRateBpm = hr,
            maxHeartRateBpm = hr,
        )
        applyLiveHeartRate()
        return Outcome.Ok(Unit)
    }

    override suspend fun stop(): Outcome<Activity> {
        val session = liveState.value ?: return Outcome.Err(CoreError.Invalid("no workout is running"))
        val end = time.now()
        if (!end.isAfter(session.startedAt)) return Outcome.Err(CoreError.Invalid("workout is too short"))
        val minutes = Duration.between(session.startedAt, end).toMinutes().toInt().coerceAtLeast(1)
        val activity = Activity(
            id = "live-${session.startedAt.epochSecond}",
            source = ActivitySource.MANUAL,
            sport = session.sport,
            sportConfidence = null,
            start = session.startedAt,
            end = end,
            strain = Metric.of(min(21.0, minutes / 8.0), Confidence.LOW),
            averageHeartRateBpm = Metric.of(session.averageHeartRateBpm ?: 120, Confidence.LOW),
            maxHeartRateBpm = Metric.of(session.maxHeartRateBpm ?: 140, Confidence.LOW),
            calories = Metric.of(minutes * 8, Confidence.LOW),
            zoneMinutes = ZoneMinutes.of(0, 0, minutes, 0, 0, 0),
            heartRateRecovery = null,
        )
        liveState.value = null
        val date = session.startedAt.atZone(time.zone()).toLocalDate()
        days.value = days.value + (date to (days.value[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)).adding(activity))
        applyLiveHeartRate()
        return Outcome.Ok(activity)
    }

    override suspend fun edit(activity: Activity): Outcome<Unit> {
        val located = find(activity.id) ?: return Outcome.Err(CoreError.Invalid("no activity with that id"))
        val date = activity.start.atZone(time.zone()).toLocalDate()
        val without = days.value[located.first]?.let { it.copy(activities = it.activities.filter { item -> item.id != activity.id }) }
        val target = (if (date == located.first) without else days.value[date] ?: emptyDay(date, AbsentReason.NOT_ENOUGH_DATA))
            ?: return Outcome.Err(CoreError.Invalid("no activity with that id"))
        val updated = target.copy(activities = target.activities + activity)
        val next = days.value.toMutableMap()
        if (without != null) next[located.first] = if (date == located.first) updated else without
        next[date] = updated
        days.value = next
        return Outcome.Ok(Unit)
    }

    override suspend fun delete(id: String): Outcome<Unit> {
        val located = find(id) ?: return Outcome.Err(CoreError.Invalid("no activity with that id"))
        val day = days.value.getValue(located.first)
        days.value = days.value + (located.first to day.copy(activities = day.activities.filter { it.id != id }))
        return Outcome.Ok(Unit)
    }

    override suspend fun merge(ids: List<String>): Outcome<Activity> {
        if (ids.size < 2) return Outcome.Err(CoreError.Invalid("merge needs two activities"))
        val located = ids.map { id -> find(id) ?: return Outcome.Err(CoreError.Invalid("no activity with that id")) }
        if (located.map { it.first }.distinct().size != 1) {
            return Outcome.Err(CoreError.Invalid("activities are on different days"))
        }
        val date = located.first().first
        val chosen = located.map { it.second }
        val merged = mergeActivities(chosen)
        val day = days.value.getValue(date)
        val kept = day.activities.filter { it.id !in ids } + merged
        days.value = days.value + (date to day.copy(activities = kept))
        return Outcome.Ok(merged)
    }

    override val tags: StateFlow<List<JournalTag>> = tagState

    override fun observeEntry(date: LocalDate): Flow<JournalEntry?> = entryState.map { it[date] }

    override suspend fun saveTag(tag: JournalTag): Outcome<JournalTag> {
        if (tag.id.isBlank() || tag.name.isBlank()) return Outcome.Err(CoreError.Invalid("tag needs an id and a name"))
        val existing = tagState.value.find { it.id == tag.id }
        val stored = if (existing?.builtIn == true) tag.copy(builtIn = true) else tag
        tagState.value = tagState.value.filter { it.id != tag.id } + stored
        return Outcome.Ok(stored)
    }

    override suspend fun deleteTag(id: String): Outcome<Unit> {
        val tag = tagState.value.find { it.id == id } ?: return Outcome.Err(CoreError.Invalid("no tag with that id"))
        if (tag.builtIn) return Outcome.Err(CoreError.Invalid("built-in tags stay in the list"))
        tagState.value = tagState.value.filter { it.id != id }
        entryState.value = entryState.value.mapValues { (_, entry) -> entry.copy(tagIds = entry.tagIds - id) }
        return Outcome.Ok(Unit)
    }

    override suspend fun saveEntry(entry: JournalEntry): Outcome<Unit> {
        if (entry.tagIds.any { id -> tagState.value.none { it.id == id } }) {
            return Outcome.Err(CoreError.Invalid("unknown tag"))
        }
        entryState.value = entryState.value + (entry.date to entry)
        return Outcome.Ok(Unit)
    }

    override suspend fun correlations(): Outcome<List<TagCorrelation>> {
        val stored = days.value.values.sortedBy { it.date }
        val entries = entryState.value
        val rows = ArrayList<TagCorrelation>()
        for (tag in tagState.value) {
            for (metric in CorrelationMetric.entries) {
                val withValues = stored.mapNotNull { day ->
                    if (entries[day.date]?.tagIds?.contains(tag.id) == true) valueOf(day, metric) else null
                }
                val withoutValues = stored.mapNotNull { day ->
                    if (entries[day.date]?.tagIds?.contains(tag.id) == true) null else valueOf(day, metric)
                }
                val effect = if (withValues.size >= 2 && withoutValues.size >= 2) {
                    Metric.of(withValues.average() - withoutValues.average(), Confidence.LOW)
                } else {
                    Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
                }
                rows += TagCorrelation(tag.id, metric, effect, withValues.size, withoutValues.size)
            }
        }
        return Outcome.Ok(rows)
    }

    override val profile: StateFlow<Profile> = profileState

    override suspend fun update(profile: Profile): Outcome<Profile> = when (val result = profile.validate()) {
        is Outcome.Err -> result
        is Outcome.Ok -> {
            profileState.value = result.value
            refreshChecks()
            result
        }
    }

    override val settings: StateFlow<UserSettings> = settingsState

    override suspend fun update(settings: UserSettings): Outcome<UserSettings> {
        settingsState.value = settings
        return Outcome.Ok(settings)
    }

    override val healthConnect: StateFlow<HealthConnectStatus> = healthState
    override val backup: StateFlow<BackupSettings> = backupState

    override suspend fun exportDatabase(): Outcome<ExportResult> = Outcome.Ok(
        ExportResult(
            displayName = "circlet-export.json",
            uri = "content://app.circlet/export/circlet-export.json",
            byteCount = 524_288,
        ),
    )

    override suspend fun updateBackup(backup: BackupSettings): Outcome<Unit> {
        if (backup.interval.isZero || backup.interval.isNegative) {
            return Outcome.Err(CoreError.Invalid("backup interval must be positive"))
        }
        if (backup.enabled && backup.folderUri.isNullOrBlank()) {
            return Outcome.Err(CoreError.Invalid("backup needs a folder"))
        }
        backupState.value = backup
        return Outcome.Ok(Unit)
    }

    override suspend fun restore(uri: String): Outcome<Unit> {
        if (uri.isBlank()) return Outcome.Err(CoreError.Invalid("restore needs a file"))
        return Outcome.Ok(Unit)
    }

    override suspend fun setHealthConnectEnabled(enabled: Boolean): Outcome<Unit> {
        healthState.value = healthState.value.copy(
            enabled = enabled,
            granted = if (enabled) HealthKind.entries.toSet() else emptySet(),
        )
        return Outcome.Ok(Unit)
    }

    override val completed: StateFlow<Boolean> = completedState
    override val checks: StateFlow<OnboardingChecks> = checksState

    override suspend fun setCompleted(completed: Boolean): Outcome<Unit> {
        completedState.value = completed
        return Outcome.Ok(Unit)
    }

    private fun initialStrap(): StrapState {
        if (scenario == FakeScenario.UNPAIRED) return StrapState()
        return StrapState(
            link = LinkState.CONNECTED,
            name = "Circlet",
            serial = "W4A0-1042",
            firmware = "1.0.0",
            batteryPercent = 76.0,
            charging = false,
            onWrist = true,
            sync = SyncProgress(samplesPulled = 120_000, draining = false),
            lastSync = time.now().minus(Duration.ofMinutes(12)),
        )
    }

    private fun initialAlarms(): List<StrapAlarm> {
        if (scenario == FakeScenario.UNPAIRED) return emptyList()
        return listOf(
            StrapAlarm(
                id = "weekday",
                time = LocalTime.of(6, 30),
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                smartWake = Duration.ofMinutes(30),
                enabled = true,
            ),
        )
    }

    private fun publishNext() {
        val now = time.now()
        val zone = time.zone()
        nextState.value = alarmState.value.mapNotNull { alarm ->
            alarm.nextFire(now, zone)?.let { ScheduledAlarm(alarm, it) }
        }.minByOrNull { it.at }
    }

    private fun applyLiveHeartRate() {
        val hr = when {
            liveState.value != null -> liveState.value?.heartRateBpm
            liveHeartRate && strap.value.link == LinkState.CONNECTED && strap.value.onWrist != false -> 72
            else -> null
        }
        strap.value = strap.value.copy(liveHeartRateBpm = hr)
    }

    private fun find(id: String): Pair<LocalDate, Activity>? {
        for ((date, day) in days.value) {
            val activity = day.activities.find { it.id == id } ?: continue
            return date to activity
        }
        return null
    }

    private fun today(): LocalDate = time.now().atZone(time.zone()).toLocalDate()

    private fun refreshChecks() {
        checksState.value = currentChecks()
    }

    private fun currentChecks(): OnboardingChecks {
        val paired = strap.value.link != LinkState.UNPAIRED
        val body = profileState.value
        return OnboardingChecks(
            bluetoothScan = true,
            bluetoothConnect = true,
            notifications = true,
            batteryOptimisationExempt = true,
            companionDeviceAssociated = paired,
            profileSaved = body.heightCm != null && body.weightKg != null && body.ageOn(today()) != null && body.sex != Sex.UNSPECIFIED,
            strapPaired = paired,
            firstSyncFinished = strap.value.lastSync != null,
        )
    }
}

private fun demoProfile(): Profile = Profile(
    birthDate = LocalDate.of(1994, 6, 15),
    ageYears = null,
    sex = Sex.FEMALE,
    heightCm = 168.0,
    weightKg = 64.0,
    maxHeartRateBpm = null,
    customZones = emptyList(),
)

private fun DaySummary.adding(activity: Activity): DaySummary {
    val currentScore = strain.score.value
    val addedScore = activity.strain.value
    val score = when {
        currentScore != null && addedScore != null -> Metric.of(min(21.0, currentScore + addedScore), Confidence.LOW)
        addedScore != null -> Metric.of(addedScore, Confidence.LOW)
        else -> strain.score
    }
    val currentCalories = calories.value
    val addedCalories = activity.calories.value
    val burned = when {
        currentCalories != null && addedCalories != null -> Metric.of(currentCalories + addedCalories, calories.confidence)
        addedCalories != null -> activity.calories
        else -> calories
    }
    return copy(
        activities = activities + activity,
        strain = Strain(score, strain.zoneMinutes.plus(activity.zoneMinutes)),
        calories = burned,
    )
}

private fun mergeActivities(activities: List<Activity>): Activity {
    val longer = activities.maxBy { Duration.between(it.start, it.end) }
    val minutes = activities.map { Duration.between(it.start, it.end).toMinutes().coerceAtLeast(1) }
    val total = minutes.sum()
    val average = activities.zip(minutes).sumOf { (activity, span) ->
        (activity.averageHeartRateBpm.value ?: 0).toLong() * span
    } / total
    return longer.copy(
        id = activities.first().id,
        source = if (activities.any { it.source == ActivitySource.MANUAL }) ActivitySource.MANUAL else ActivitySource.AUTO,
        sportConfidence = if (activities.any { it.source == ActivitySource.MANUAL }) null else longer.sportConfidence,
        start = activities.minOf { it.start },
        end = activities.maxOf { it.end },
        strain = Metric.of(activities.mapNotNull { it.strain.value }.maxOrNull() ?: 0.0, Confidence.LOW),
        averageHeartRateBpm = Metric.of(average.toInt(), Confidence.LOW),
        maxHeartRateBpm = Metric.of(activities.mapNotNull { it.maxHeartRateBpm.value }.maxOrNull() ?: 0, Confidence.LOW),
        calories = Metric.of(activities.sumOf { it.calories.value ?: 0 }, Confidence.LOW),
        zoneMinutes = activities.map { it.zoneMinutes }.reduce { left, right -> left.plus(right) },
        heartRateRecovery = null,
    )
}

private fun average(values: List<Double>): Metric<Double> =
    if (values.isEmpty()) Metric.absent(AbsentReason.NOT_ENOUGH_DATA)
    else Metric.of(values.average(), Confidence.MEDIUM)

private fun valueOf(day: DaySummary, metric: CorrelationMetric): Double? = when (metric) {
    CorrelationMetric.RECOVERY -> day.recovery.score.value?.toDouble()
    CorrelationMetric.HRV -> day.heart.hrv.rmssdMs.value
    CorrelationMetric.SLEEP_PERFORMANCE -> day.sleepPerformance.percent.value?.toDouble()
}

private fun read(day: DaySummary, metric: MetricKind): Metric<Double> = day.reading(metric)
