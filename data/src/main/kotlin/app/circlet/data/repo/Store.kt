package app.circlet.data.repo

import android.content.Context
import app.circlet.core.BackupSettings
import app.circlet.core.ClockStyle
import app.circlet.core.DayEngine
import app.circlet.core.DoubleTapAction
import app.circlet.core.HealthConnectStatus
import app.circlet.core.InsightEngine
import app.circlet.core.JournalTag
import app.circlet.core.LiveWorkout
import app.circlet.core.MassUnit
import app.circlet.core.OnboardingChecks
import app.circlet.core.Profile
import app.circlet.core.ScheduledAlarm
import app.circlet.core.StrapAlarm
import app.circlet.core.TimeSource
import app.circlet.core.UserSettings
import app.circlet.core.ageOn
import app.circlet.data.codec.LiveCodec
import app.circlet.data.codec.ProfileCodec
import app.circlet.data.codec.defaultProfile
import app.circlet.data.db.CircletDatabase
import app.circlet.data.db.Keys
import app.circlet.data.db.openDatabase
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class Store(
    val context: Context,
    val time: TimeSource,
    val dayEngine: DayEngine,
    val insightEngine: InsightEngine,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val debounceMs: Long,
    database: CircletDatabase? = null,
) {
    val database: CircletDatabase = database ?: openDatabase(context)
    val generation = MutableStateFlow(0)
    val lock = Mutex()
    val ready = CompletableDeferred<Unit>()

    suspend fun <T> io(block: () -> T): T = withContext(io) { block() }
}

internal class Memory(private val store: Store) {
    val profile = MutableStateFlow(defaultProfile())
    val settings = MutableStateFlow(defaultSettings())
    val alarms = MutableStateFlow<List<StrapAlarm>>(emptyList())
    val nextAlarm = MutableStateFlow<ScheduledAlarm?>(null)
    val tags = MutableStateFlow<List<JournalTag>>(emptyList())
    val address = MutableStateFlow<String?>(null)
    val strapName = MutableStateFlow<String?>(null)
    val lastSync = MutableStateFlow<Instant?>(null)
    val onboardingCompleted = MutableStateFlow(false)
    val checks = MutableStateFlow(blankChecks())
    val health = MutableStateFlow(HealthConnectStatus(available = false, enabled = false, granted = emptySet()))
    val backup = MutableStateFlow(defaultBackup())
    val live = MutableStateFlow<LiveWorkout?>(null)
    var liveSamples: Int = 0
    var liveTickMillis: Long = 0L
    val zoneSeconds = IntArray(6)

    suspend fun reload() = store.lock.withLock { readIntoMemory() }

    /** Caller holds [Store.lock]. A restore uses this so it does not take the lock twice. */
    suspend fun readIntoMemory() {
        val loaded = store.io {
            val db = store.database
            applyRetention(db, store.time.now())
            Loaded(
                profile = db.profile().get()?.let { ProfileCodec.decode(it.payload) } ?: defaultProfile(),
                settings = readSettings(db),
                alarms = db.alarms().all().map { it.toAlarm() },
                tags = db.journal().tags().map { JournalTag(it.id, it.name, it.builtIn) },
                address = db.kv().get(Keys.PAIRED_ADDRESS),
                name = db.kv().get(Keys.PAIRED_NAME),
                lastSync = db.kv().get(Keys.LAST_SYNC)?.toLongOrNull()?.let(Instant::ofEpochMilli),
                onboarding = db.kv().get(Keys.ONBOARDING) == "true",
                backup = readBackup(db),
                healthEnabled = db.kv().get(Keys.HEALTH_ENABLED) == "true",
                live = db.liveWorkout().get()?.let { LiveCodec.decode(it.payload) },
            )
        }
        profile.value = loaded.profile
        settings.value = loaded.settings
        alarms.value = loaded.alarms
        tags.value = loaded.tags
        address.value = loaded.address
        strapName.value = loaded.name
        lastSync.value = loaded.lastSync
        onboardingCompleted.value = loaded.onboarding
        backup.value = loaded.backup
        val storedLive = loaded.live
        live.value = storedLive?.workout
        liveSamples = storedLive?.samples ?: 0
        liveTickMillis = storedLive?.lastTickMillis ?: 0L
        zoneSeconds.fill(0)
        val enabled = loaded.healthEnabled
        health.value = health.value.copy(enabled = enabled, granted = if (enabled) health.value.granted else emptySet())
        publishNext()
        refreshChecks()
    }

    fun publishNext() {
        val now = store.time.now()
        val zone = store.time.zone()
        nextAlarm.value = alarms.value.mapNotNull { alarm ->
            alarm.nextFire(now, zone)?.let { ScheduledAlarm(alarm, it) }
        }.minByOrNull { it.at }
    }

    fun refreshChecks() {
        val today = store.time.now().atZone(store.time.zone()).toLocalDate()
        val device = readDeviceChecks(store.context)
        val body = profile.value
        checks.value = OnboardingChecks(
            bluetoothScan = device.scan,
            bluetoothConnect = device.connect,
            notifications = device.notifications,
            batteryOptimisationExempt = device.battery,
            companionDeviceAssociated = device.companion,
            profileSaved = body.heightCm != null && body.weightKg != null &&
                body.ageOn(today) != null && body.sex != app.circlet.core.Sex.UNSPECIFIED,
            strapPaired = !address.value.isNullOrBlank(),
            firstSyncFinished = lastSync.value != null,
        )
    }
}

private data class Loaded(
    val profile: Profile,
    val settings: UserSettings,
    val alarms: List<StrapAlarm>,
    val tags: List<JournalTag>,
    val address: String?,
    val name: String?,
    val lastSync: Instant?,
    val onboarding: Boolean,
    val backup: BackupSettings,
    val healthEnabled: Boolean,
    val live: app.circlet.data.codec.StoredLive?,
)

internal fun defaultSettings(): UserSettings = UserSettings(
    mass = MassUnit.KILOGRAM,
    clock = ClockStyle.MATCH_SYSTEM,
    doubleTap = DoubleTapAction.STOP_ALARM,
    heartRateBroadcast = false,
    zoneBuzz = false,
)

internal fun defaultBackup(): BackupSettings = BackupSettings(
    enabled = false,
    interval = Duration.ofHours(24),
    folderUri = null,
    lastBackup = null,
)

internal fun blankChecks(): OnboardingChecks = OnboardingChecks(
    bluetoothScan = false,
    bluetoothConnect = false,
    notifications = false,
    batteryOptimisationExempt = false,
    companionDeviceAssociated = false,
    profileSaved = false,
    strapPaired = false,
    firstSyncFinished = false,
)

internal fun readSettings(db: CircletDatabase): UserSettings {
    val defaults = defaultSettings()
    return UserSettings(
        mass = db.kv().get(Keys.MASS)?.let { runCatching { MassUnit.valueOf(it) }.getOrNull() } ?: defaults.mass,
        clock = db.kv().get(Keys.CLOCK)?.let { runCatching { ClockStyle.valueOf(it) }.getOrNull() } ?: defaults.clock,
        doubleTap = db.kv().get(Keys.DOUBLE_TAP)?.let { runCatching { DoubleTapAction.valueOf(it) }.getOrNull() } ?: defaults.doubleTap,
        heartRateBroadcast = db.kv().get(Keys.HR_BROADCAST) == "true",
        zoneBuzz = db.kv().get(Keys.ZONE_BUZZ) == "true",
    )
}

internal fun readBackup(db: CircletDatabase): BackupSettings {
    val defaults = defaultBackup()
    return BackupSettings(
        enabled = db.kv().get(Keys.BACKUP_ENABLED) == "true",
        interval = db.kv().get(Keys.BACKUP_INTERVAL)?.toLongOrNull()?.let(Duration::ofMillis) ?: defaults.interval,
        folderUri = db.kv().get(Keys.BACKUP_FOLDER),
        lastBackup = db.kv().get(Keys.LAST_BACKUP)?.toLongOrNull()?.let(Instant::ofEpochMilli),
    )
}

internal fun applyRetention(db: CircletDatabase, now: Instant) {
    val rawDays = db.kv().get(Keys.RAW_RETENTION)?.toLongOrNull() ?: 14L
    if (rawDays > 0) db.rawRecords().deleteBefore(now.minus(Duration.ofDays(rawDays)).epochSecond)
    val sampleDays = db.kv().get(Keys.SAMPLE_RETENTION)?.toLongOrNull()
    if (sampleDays != null && sampleDays > 0) {
        db.samples().deleteBefore(now.minus(Duration.ofDays(sampleDays)).epochSecond)
    }
}
