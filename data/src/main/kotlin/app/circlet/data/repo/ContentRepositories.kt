package app.circlet.data.repo

import app.circlet.core.AlarmController
import app.circlet.core.CoreError
import app.circlet.core.JournalEntry
import app.circlet.core.JournalRepository
import app.circlet.core.JournalTag
import app.circlet.core.OnboardingState
import app.circlet.core.Outcome
import app.circlet.core.Profile
import app.circlet.core.ProfileRepository
import app.circlet.core.ScheduledAlarm
import app.circlet.core.SettingsRepository
import app.circlet.core.StrapAlarm
import app.circlet.core.TagCorrelation
import app.circlet.core.UserSettings
import app.circlet.core.validate
import app.circlet.data.codec.journalTags
import app.circlet.data.codec.journalTagsJson
import app.circlet.data.db.JournalEntryEntity
import app.circlet.data.db.JournalTagEntity
import app.circlet.data.db.Keys
import app.circlet.data.db.KvEntity
import app.circlet.data.pipeline.RecomputePipeline
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

internal class JournalRepositoryImpl(
    private val store: Store,
    private val memory: Memory,
    private val metrics: MetricsRepositoryImpl,
) : JournalRepository {
    override val tags: StateFlow<List<JournalTag>> = memory.tags

    override fun observeEntry(date: LocalDate): Flow<JournalEntry?> = store.generation.flatMapLatest {
        store.database.journal().observeEntry(date.toString()).map { row -> row?.toEntry() }
    }

    override suspend fun saveTag(tag: JournalTag): Outcome<JournalTag> {
        if (tag.id.isBlank() || tag.name.isBlank()) {
            return Outcome.Err(CoreError.Invalid("tag needs an id and a name"))
        }
        val stored = store.io {
            val existing = store.database.journal().tag(tag.id)
            val row = JournalTagEntity(
                id = tag.id,
                name = tag.name,
                builtIn = existing?.builtIn == true || tag.builtIn,
            )
            store.database.journal().upsertTag(row)
            JournalTag(row.id, row.name, row.builtIn)
        }
        memory.tags.value = store.io { store.database.journal().tags().map { JournalTag(it.id, it.name, it.builtIn) } }
        return Outcome.Ok(stored)
    }

    override suspend fun deleteTag(id: String): Outcome<Unit> {
        val tag = memory.tags.value.find { it.id == id }
            ?: return Outcome.Err(CoreError.Invalid("no tag with that id"))
        if (tag.builtIn) return Outcome.Err(CoreError.Invalid("built-in tags stay in the list"))
        store.io {
            store.database.journal().deleteTag(id)
            val entries = store.database.journal().entries()
            for (entry in entries) {
                val ids = journalTags(entry.tagIds)
                if (id !in ids) continue
                store.database.journal().upsertEntry(entry.copy(tagIds = journalTagsJson(ids - id)))
            }
        }
        memory.tags.value = memory.tags.value.filter { it.id != id }
        return Outcome.Ok(Unit)
    }

    override suspend fun saveEntry(entry: JournalEntry): Outcome<Unit> {
        val known = memory.tags.value.map { it.id }.toSet()
        if (entry.tagIds.any { it !in known }) return Outcome.Err(CoreError.Invalid("unknown tag"))
        store.io {
            store.database.journal().upsertEntry(
                JournalEntryEntity(entry.date.toString(), entry.note, journalTagsJson(entry.tagIds)),
            )
        }
        return Outcome.Ok(Unit)
    }

    override suspend fun correlations(): Outcome<List<TagCorrelation>> = try {
        metrics.settle(null, null)
        val days = metrics.loadStoredDays()
        val entries = store.io { store.database.journal().entries().map { it.toEntry() } }
        Outcome.Ok(store.insightEngine.correlations(days, memory.tags.value, entries))
    } catch (error: Exception) {
        Outcome.Err(CoreError.Storage(error.message ?: "correlations failed"))
    }
}

internal class ProfileRepositoryImpl(
    private val store: Store,
    private val memory: Memory,
    private val pipeline: RecomputePipeline,
) : ProfileRepository {
    override val profile: StateFlow<Profile> = memory.profile

    override suspend fun update(profile: Profile): Outcome<Profile> {
        when (val valid = profile.validate()) {
            is Outcome.Err -> return valid
            is Outcome.Ok -> {
                store.io {
                    store.database.profile().upsert(
                        app.circlet.data.db.ProfileEntity(payload = app.circlet.data.codec.ProfileCodec.encode(valid.value)),
                    )
                    store.database.days().dates().forEach { store.database.dirty().mark(it) }
                }
                memory.profile.value = valid.value
                memory.refreshChecks()
                pipeline.nudge()
                return valid
            }
        }
    }
}

internal class SettingsRepositoryImpl(
    private val store: Store,
    private val memory: Memory,
) : SettingsRepository {
    override val settings: StateFlow<UserSettings> = memory.settings

    override suspend fun update(settings: UserSettings): Outcome<UserSettings> {
        store.io {
            val kv = store.database.kv()
            kv.put(KvEntity(Keys.MASS, settings.mass.name))
            kv.put(KvEntity(Keys.CLOCK, settings.clock.name))
            kv.put(KvEntity(Keys.DOUBLE_TAP, settings.doubleTap.name))
            kv.put(KvEntity(Keys.HR_BROADCAST, settings.heartRateBroadcast.toString()))
            kv.put(KvEntity(Keys.OFF_WRIST_ALERTS, settings.offWristAlerts.toString()))
            kv.put(KvEntity(Keys.LOW_BATTERY_ALERTS, settings.lowBatteryAlerts.toString()))
            kv.put(KvEntity(Keys.LOW_BATTERY_THRESHOLD, settings.lowBatteryThreshold.toString()))
            putOrClear(kv, Keys.QUIET_START, settings.quietStartMinute)
            putOrClear(kv, Keys.QUIET_END, settings.quietEndMinute)
            kv.put(KvEntity(Keys.PHONE_STEPS, settings.countPhoneSteps.toString()))
            kv.put(KvEntity(Keys.ZONE_BUZZ, settings.zoneBuzz.toString()))
        }
        memory.settings.value = settings
        return Outcome.Ok(settings)
    }
}

private fun putOrClear(kv: app.circlet.data.db.KvDao, key: String, value: Int?) {
    if (value == null) kv.delete(key) else kv.put(KvEntity(key, value.toString()))
}

internal class OnboardingStateImpl(
    private val store: Store,
    private val memory: Memory,
) : OnboardingState {
    override val completed: StateFlow<Boolean> = memory.onboardingCompleted
    override val checks: StateFlow<app.circlet.core.OnboardingChecks> = memory.checks

    override suspend fun setCompleted(completed: Boolean): Outcome<Unit> {
        store.io { store.database.kv().put(KvEntity(Keys.ONBOARDING, completed.toString())) }
        memory.onboardingCompleted.value = completed
        memory.refreshChecks()
        return Outcome.Ok(Unit)
    }

    fun refresh() = memory.refreshChecks()
}

internal class AlarmControllerImpl(
    private val store: Store,
    private val memory: Memory,
) : AlarmController {
    override val alarms: StateFlow<List<StrapAlarm>> = memory.alarms
    override val next: StateFlow<ScheduledAlarm?> = memory.nextAlarm

    override suspend fun set(alarm: StrapAlarm): Outcome<Unit> {
        if (alarm.id.isBlank()) return Outcome.Err(CoreError.Invalid("alarm needs an id"))
        if (alarm.smartWake.isNegative) return Outcome.Err(CoreError.Invalid("smart wake cannot be negative"))
        store.ready.await()
        store.lock.withLock { store.io { store.database.alarms().upsert(alarm.toEntity()) } }
        reload()
        return Outcome.Ok(Unit)
    }

    override suspend fun disable(id: String): Outcome<Unit> {
        store.ready.await()
        val found = store.lock.withLock { store.io { store.database.alarms().get(id) != null } }
        if (!found) return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        store.lock.withLock { store.io { store.database.alarms().disable(id) } }
        reload()
        return Outcome.Ok(Unit)
    }

    override suspend fun remove(id: String): Outcome<Unit> {
        store.ready.await()
        val found = store.lock.withLock { store.io { store.database.alarms().get(id) != null } }
        if (!found) return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        store.lock.withLock { store.io { store.database.alarms().delete(id) } }
        reload()
        return Outcome.Ok(Unit)
    }

    override suspend fun setSmartWake(id: String, window: Duration): Outcome<Unit> {
        if (window.isNegative) return Outcome.Err(CoreError.Invalid("smart wake cannot be negative"))
        store.ready.await()
        val found = store.lock.withLock { store.io { store.database.alarms().get(id) != null } }
        if (!found) return Outcome.Err(CoreError.Invalid("no alarm with that id"))
        store.lock.withLock { store.io { store.database.alarms().smartWake(id, window.toMillis()) } }
        reload()
        return Outcome.Ok(Unit)
    }

    private suspend fun reload() {
        memory.alarms.value = store.lock.withLock { store.io { store.database.alarms().all().map { it.toAlarm() } } }
        memory.publishNext()
    }
}

private fun JournalEntryEntity.toEntry(): JournalEntry = JournalEntry(
    date = LocalDate.parse(date),
    tagIds = journalTags(tagIds),
    note = note,
)
