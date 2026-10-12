package app.circlet.data.repo

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.DoubleTapAction
import app.circlet.core.DoubleTapPolicy
import app.circlet.core.LiveLoad
import app.circlet.core.LiveStrainEstimator
import app.circlet.core.Metric
import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.core.SampleSink
import app.circlet.core.SignalBounds
import app.circlet.core.SleepStage
import app.circlet.core.SleepStageEstimate
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapEvent
import app.circlet.core.StrapEventSink
import app.circlet.core.StrapPrefs
import app.circlet.core.LiveSession
import app.circlet.core.WorkoutStore
import app.circlet.core.ZoneMinutes
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.codec.DayCodec
import app.circlet.data.codec.LiveCodec
import app.circlet.data.codec.toEntity
import app.circlet.data.db.ActivityEntity
import app.circlet.data.db.Keys
import app.circlet.data.db.KvEntity
import app.circlet.data.db.LIVE_ROW
import app.circlet.data.db.LiveWorkoutEntity
import app.circlet.data.db.RawRecordEntity
import app.circlet.data.db.StrapEventEntity
import app.circlet.data.pipeline.RecomputePipeline
import app.circlet.data.pipeline.affectedDates
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.withLock

private const val INSERT_BATCH = 4_000

/**
 * [commit] returns only after the rows and the dirty marks are committed.
 * The strap link acknowledges a history batch after that return.
 */
internal class SampleSinkImpl(
    private val store: Store,
    private val pipeline: RecomputePipeline,
) : SampleSink {
    override suspend fun commit(samples: List<Sample>, rawRecords: List<RawRecord>) {
        if (samples.isEmpty() && rawRecords.isEmpty()) return
        val archived = LinkedHashMap<Long, ByteArray>()
        for (record in rawRecords) archived[record.epochSecond] = record.bytes
        for (sample in samples) {
            val raw = sample.rawRecord ?: continue
            if (sample.epochSecond !in archived) archived[sample.epochSecond] = raw.toByteArray()
        }
        store.lock.withLock {
            store.io {
                val db = store.database
                db.runInTransaction {
                    samples.map { it.toEntity() }.chunked(INSERT_BATCH).forEach { db.samples().insert(it) }
                    if (archived.isNotEmpty()) {
                        archived.entries.map { RawRecordEntity(it.key, it.value) }
                            .chunked(INSERT_BATCH)
                            .forEach { db.rawRecords().insert(it) }
                    }
                    val dates = affectedDates(samples.map { it.epochSecond }, store.time.zone())
                    for (date in dates) db.dirty().mark(date.toString())
                    applyRetention(db, store.time.now())
                }
            }
        }
        pipeline.nudge()
    }
}

/** [record] returns only after the event row is committed. */
internal class StrapEventSinkImpl(private val store: Store) : StrapEventSink {
    override suspend fun record(event: StrapEvent) {
        val row = when (event) {
            is StrapEvent.Battery -> StrapEventEntity(atMillis = event.at.toEpochMilli(), kind = "battery", number = event.percent, flag = null)
            is StrapEvent.Charging -> StrapEventEntity(atMillis = event.at.toEpochMilli(), kind = "charging", number = null, flag = event.charging)
            is StrapEvent.Wrist -> StrapEventEntity(atMillis = event.at.toEpochMilli(), kind = "wrist", number = null, flag = event.onWrist)
            is StrapEvent.DoubleTap -> StrapEventEntity(atMillis = event.at.toEpochMilli(), kind = "double_tap", number = null, flag = null)
            is StrapEvent.HighFrequencySyncPrompt -> StrapEventEntity(
                atMillis = event.at.toEpochMilli(),
                kind = "high_frequency_sync",
                number = null,
                flag = null,
            )
        }
        store.lock.withLock { store.io { store.database.strapEvents().insert(row) } }
    }
}

/**
 * Pairing, name, last sync, and the alarm list.
 * The strap module arms the radio from this list. This class only stores it.
 */
internal class StrapPrefsImpl(
    private val store: Store,
    private val memory: Memory,
) : StrapPrefs {
    override suspend fun pairedAddress(): String? = stored { memory.address.value }

    override suspend fun setPairedAddress(address: String?) = stored {
        putOrDelete(Keys.PAIRED_ADDRESS, address?.takeIf { it.isNotBlank() })
        memory.address.value = address?.takeIf { it.isNotBlank() }
        memory.refreshChecks()
    }

    override suspend fun strapName(): String? = stored { memory.strapName.value }

    override suspend fun setStrapName(name: String?) = stored {
        putOrDelete(Keys.PAIRED_NAME, name?.takeIf { it.isNotBlank() })
        memory.strapName.value = name?.takeIf { it.isNotBlank() }
    }

    override suspend fun lastSync(): Instant? = stored { memory.lastSync.value }

    override suspend fun setLastSync(at: Instant?) = stored {
        putOrDelete(Keys.LAST_SYNC, at?.toEpochMilli()?.toString())
        memory.lastSync.value = at
        memory.refreshChecks()
    }

    override suspend fun alarms(): List<StrapAlarm> = stored { memory.alarms.value }

    override suspend fun setAlarms(alarms: List<StrapAlarm>) = stored {
        store.database.alarms().deleteAll()
        alarms.forEach { store.database.alarms().upsert(it.toEntity()) }
        memory.alarms.value = alarms
        memory.publishNext()
    }

    private suspend fun <T> stored(block: () -> T): T {
        store.ready.await()
        return store.lock.withLock { store.io { block() } }
    }

    private fun putOrDelete(key: String, value: String?) {
        if (value.isNullOrBlank()) store.database.kv().delete(key)
        else store.database.kv().put(KvEntity(key, value))
    }
}

/**
 * Finished activities. Manual rows and user edits survive recomputation.
 * Deleting an auto-detected row leaves a tombstone so the same detection does not return.
 * The strap module owns the live session; this is the table it writes.
 */
internal class WorkoutStoreImpl(
    private val store: Store,
    private val pipeline: RecomputePipeline,
) : WorkoutStore {
    override suspend fun insert(activity: Activity) {
        val date = activity.start.atZone(store.time.zone()).toLocalDate()
        store.lock.withLock {
            store.io {
                store.database.activities().insert(listOf(activity.row(date.toString(), userEdited = false)))
                store.database.dirty().mark(date.toString())
            }
        }
        pipeline.nudge()
    }

    override suspend fun update(activity: Activity) {
        val zone = store.time.zone()
        val date = activity.start.atZone(zone).toLocalDate()
        store.lock.withLock {
            store.io {
                val existing = store.database.activities().get(activity.id)
                store.database.activities().insert(listOf(activity.row(date.toString(), userEdited = true)))
                store.database.dirty().mark(date.toString())
                if (existing != null && existing.localDate != date.toString()) {
                    store.database.dirty().mark(existing.localDate)
                }
                val endDate = activity.end.atZone(zone).toLocalDate().toString()
                if (endDate != date.toString()) store.database.dirty().mark(endDate)
            }
        }
        pipeline.nudge()
    }

    override suspend fun delete(id: String) {
        store.lock.withLock {
            store.io {
                val existing = store.database.activities().get(id) ?: return@io
                if (existing.source == ActivitySource.AUTO.name) {
                    store.database.activities().insert(listOf(existing.copy(suppressed = true)))
                } else {
                    store.database.activities().delete(id)
                }
                store.database.dirty().mark(existing.localDate)
            }
        }
        pipeline.nudge()
    }

    override suspend fun get(id: String): Activity? = store.io {
        store.database.activities().get(id)?.visibleActivity()
    }

    override suspend fun saveLive(session: LiveSession?) {
        store.lock.withLock {
            store.io {
                if (session == null) {
                    store.database.liveWorkout().clear()
                } else {
                    val payload = LiveCodec.encode(
                        session.workout,
                        session.beats.size,
                        session.workout.startedAt.toEpochMilli(),
                        session.beats,
                    )
                    store.database.liveWorkout().upsert(LiveWorkoutEntity(LIVE_ROW, payload))
                }
            }
        }
    }

    override suspend fun currentLive(): LiveSession? = store.io {
        val row = store.database.liveWorkout().get() ?: return@io null
        val stored = LiveCodec.decode(row.payload)
        LiveSession(stored.workout, stored.beats)
    }
}

/** The double-tap action from [app.circlet.core.SettingsRepository]. */
internal class SettingsDoubleTap(private val memory: Memory) : DoubleTapPolicy {
    override suspend fun current(): DoubleTapAction = memory.settings.value.doubleTap
}

/**
 * Sleep stage from the stored day, when [now] falls inside an epoch.
 * Reads Room. Call it off the main thread.
 */
internal class StoredSleepStage(private val store: Store) : SleepStageEstimate {
    override fun current(): SleepStage? {
        val now = store.time.now()
        val zone = store.time.zone()
        val today = now.atZone(zone).toLocalDate()
        for (date in listOf(today.minusDays(1), today)) {
            val row = store.database.days().get(date.toString()) ?: continue
            val summary = runCatching { DayCodec.decode(row.payload) }.getOrNull() ?: continue
            stageAt(summary, now)?.let { return it }
        }
        return null
    }

    private fun stageAt(summary: DaySummary, now: Instant): SleepStage? {
        val sessions = listOfNotNull(summary.sleep.value) + summary.naps
        for (session in sessions) {
            if (now.isBefore(session.start) || !now.isBefore(session.end)) continue
            for (epoch in session.epochs) {
                val end = epoch.start.plus(epoch.duration)
                if (!now.isBefore(epoch.start) && now.isBefore(end)) return epoch.stage
            }
        }
        return null
    }
}

/**
 * Zone minutes treat each heart-rate sample as one second.
 * The strap module passes the beats it has seen during the live session, about one per second.
 */
internal class ProfileLiveLoad(
    private val store: Store,
    private val memory: Memory,
) : LiveStrainEstimator {
    override fun load(elapsed: Duration, heartRatesBpm: List<Int>): LiveLoad {
        val profile = memory.profile.value
        val date = store.time.now().atZone(store.time.zone()).toLocalDate()
        val seconds = IntArray(ZoneMinutes.ZONE_COUNT)
        for (bpm in heartRatesBpm) {
            if (!SignalBounds.plausibleBpm(bpm)) continue
            val index = zoneIndex(bpm, profile, date)
            if (index in seconds.indices) seconds[index]++
        }
        val zones = ZoneMinutes(seconds.map { it / 60 })
        if (heartRatesBpm.isEmpty()) {
            return LiveLoad(
                strain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                zoneMinutes = zones,
            )
        }
        val calories = if (profile.weightKg == null) {
            Metric.absent<Int>(AbsentReason.NOT_ENOUGH_DATA)
        } else {
            Metric.of(liveCalories(zones, profile.weightKg), Confidence.LOW)
        }
        return LiveLoad(
            strain = Metric.of(liveStrain(zones), Confidence.LOW),
            calories = calories,
            zoneMinutes = zones,
        )
    }
}

private fun Activity.row(date: String, userEdited: Boolean): ActivityEntity = ActivityEntity(
    id = id,
    localDate = date,
    source = source.name,
    userEdited = userEdited,
    suppressed = false,
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    payload = ActivityCodec.encode(this),
)
