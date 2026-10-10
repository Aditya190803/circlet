package app.circlet.data

import android.content.Context
import app.circlet.core.AlarmController
import app.circlet.core.DataPortability
import app.circlet.core.DayEngine
import app.circlet.core.InsightEngine
import app.circlet.core.JournalRepository
import app.circlet.core.MetricsRepository
import app.circlet.core.OnboardingState
import app.circlet.core.Outcome
import app.circlet.core.ProfileRepository
import app.circlet.core.SettingsRepository
import app.circlet.core.StrapPorts
import app.circlet.core.TimeSource
import app.circlet.core.WorkoutController
import app.circlet.data.backup.CircletDataHolder
import app.circlet.data.db.CircletDatabase
import app.circlet.data.db.Keys
import app.circlet.data.db.KvEntity
import app.circlet.data.health.HealthConnectGateway
import app.circlet.data.pipeline.RecomputePipeline
import app.circlet.data.repo.AlarmControllerImpl
import app.circlet.data.repo.DataPortabilityImpl
import app.circlet.data.repo.JournalRepositoryImpl
import app.circlet.data.repo.Memory
import app.circlet.data.repo.MetricsRepositoryImpl
import app.circlet.data.repo.OnboardingStateImpl
import app.circlet.data.repo.ProfileLiveLoad
import app.circlet.data.repo.ProfileRepositoryImpl
import app.circlet.data.repo.SampleSinkImpl
import app.circlet.data.repo.SettingsDoubleTap
import app.circlet.data.repo.SettingsRepositoryImpl
import app.circlet.data.repo.Store
import app.circlet.data.repo.StoredSleepStage
import app.circlet.data.repo.StrapEventSinkImpl
import app.circlet.data.repo.StrapPrefsImpl
import app.circlet.data.repo.WorkoutControllerImpl
import app.circlet.data.repo.WorkoutStoreImpl
import app.circlet.data.repo.applyRetention
import app.circlet.data.repo.documentStore
import java.time.Instant
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Opens the on-device store.
 *
 * Implemented against `core/.../StrapPorts.kt` as it stood while this module was written
 * (the file was not in HEAD yet): [app.circlet.core.SampleSink], [app.circlet.core.StrapEventSink],
 * [app.circlet.core.StrapPrefs], [app.circlet.core.WorkoutStore], plus the sleep-stage, double-tap,
 * and live-load ports on [app.circlet.core.StrapPorts].
 *
 * The strap module owns the [WorkoutController] and [AlarmController] the UI should use.
 * Those talk to the radio. This module stores what they write, through [CircletData.ports].
 * [CircletData.workouts] and [CircletData.alarms] are the storage-only copies for a build
 * that has not wired the strap module yet: they persist rows and do not arm the strap or
 * stream heart rate.
 */
object DataModule {
    const val DEBOUNCE_MS: Long = 1_500

    fun open(
        context: Context,
        dayEngine: DayEngine,
        insightEngine: InsightEngine,
        time: TimeSource,
    ): CircletData = open(
        context = context,
        dayEngine = dayEngine,
        insightEngine = insightEngine,
        time = time,
        database = null,
        scope = null,
        io = Dispatchers.IO,
        debounceMs = DEBOUNCE_MS,
        startPipeline = true,
    )

    internal fun open(
        context: Context,
        dayEngine: DayEngine,
        insightEngine: InsightEngine,
        time: TimeSource,
        database: CircletDatabase?,
        scope: CoroutineScope?,
        io: CoroutineDispatcher,
        debounceMs: Long,
        startPipeline: Boolean,
    ): CircletData {
        val job = SupervisorJob()
        val effective = if (scope == null) {
            CoroutineScope(job + Dispatchers.Default)
        } else {
            CoroutineScope(scope.coroutineContext + job)
        }
        val store = Store(
            context = context.applicationContext,
            time = time,
            dayEngine = dayEngine,
            insightEngine = insightEngine,
            scope = effective,
            io = io,
            debounceMs = debounceMs,
            database = database,
        )
        val memory = Memory(store)
        val gateway = HealthConnectGateway(context.applicationContext)
        val pipeline = RecomputePipeline(store, gateway)
        val metrics = MetricsRepositoryImpl(store, pipeline)
        val portability = DataPortabilityImpl(store, memory, gateway, documentStore(context.applicationContext), pipeline)
        val data = CircletData(
            job = job,
            store = store,
            memory = memory,
            pipeline = pipeline,
            portabilityImpl = portability,
            metrics = metrics,
            journal = JournalRepositoryImpl(store, memory, metrics),
            profile = ProfileRepositoryImpl(store, memory, pipeline),
            settings = SettingsRepositoryImpl(store, memory),
            onboarding = OnboardingStateImpl(store, memory),
            alarms = AlarmControllerImpl(store, memory),
            workouts = WorkoutControllerImpl(store, memory, pipeline),
            ports = StrapPorts(
                samples = SampleSinkImpl(store, pipeline),
                events = StrapEventSinkImpl(store),
                prefs = StrapPrefsImpl(store, memory),
                workouts = WorkoutStoreImpl(store, pipeline),
                sleepStage = StoredSleepStage(store),
                doubleTap = SettingsDoubleTap(memory),
                liveLoad = ProfileLiveLoad(store, memory),
            ),
        )
        CircletDataHolder.current = data
        effective.launch {
            try {
                memory.reload()
                portability.refreshHealth()
                store.ready.complete(Unit)
            } catch (error: Throwable) {
                store.ready.completeExceptionally(error)
            }
        }
        if (startPipeline) pipeline.start()
        return data
    }
}

class CircletData internal constructor(
    private val job: CompletableJob,
    private val store: Store,
    private val memory: Memory,
    private val pipeline: RecomputePipeline,
    private val portabilityImpl: DataPortabilityImpl,
    val metrics: MetricsRepository,
    val journal: JournalRepository,
    val profile: ProfileRepository,
    val settings: SettingsRepository,
    val onboarding: OnboardingState,
    val alarms: AlarmController,
    val workouts: WorkoutController,
    val ports: StrapPorts,
) {
    val portability: DataPortability = portabilityImpl

    internal val database: CircletDatabase get() = store.database

    suspend fun awaitReady() = store.ready.await()

    /** Flushes dirty days now. Sample bursts normally wait for [DataModule.DEBOUNCE_MS]. */
    internal suspend fun recomputeNow() = pipeline.recomputeDirty()

    fun healthWritePermissions(): Set<String> = portabilityImpl.writePermissions()

    internal suspend fun runDueBackup(): Outcome<Unit> = portabilityImpl.runDueBackup()

    /**
     * [sampleDays] null keeps 1 Hz rows indefinitely.
     * [rawDays] is the raw-record archive window. The default seeded at creation is 14.
     */
    internal suspend fun setRetention(sampleDays: Long?, rawDays: Long) {
        require(rawDays > 0)
        store.lock.withLock {
            store.io {
                val kv = store.database.kv()
                if (sampleDays == null || sampleDays <= 0) kv.delete(Keys.SAMPLE_RETENTION)
                else kv.put(KvEntity(Keys.SAMPLE_RETENTION, sampleDays.toString()))
                kv.put(KvEntity(Keys.RAW_RETENTION, rawDays.toString()))
                applyRetention(store.database, store.time.now())
            }
        }
    }

    internal suspend fun rawRetentionDays(): Long =
        store.io { store.database.kv().get(Keys.RAW_RETENTION)?.toLongOrNull() ?: 14L }

    fun close() {
        if (CircletDataHolder.current === this) CircletDataHolder.current = null
        job.cancel()
        store.database.close()
    }
}

/** The backup worker looks the open store up by this hook. */
internal fun currentData(): CircletData? = CircletDataHolder.current

internal fun backupStamp(at: Instant): String = at.toEpochMilli().toString().padStart(13, '0')
