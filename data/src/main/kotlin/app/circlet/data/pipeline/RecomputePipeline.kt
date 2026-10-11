package app.circlet.data.pipeline

import app.circlet.core.ActivitySource
import app.circlet.core.DayInput
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.codec.DayCodec
import app.circlet.data.codec.ProfileCodec
import app.circlet.data.codec.defaultProfile
import app.circlet.data.codec.toSample
import app.circlet.data.db.DaySummaryEntity
import app.circlet.data.db.Keys
import app.circlet.data.health.HealthConnectGateway
import app.circlet.data.health.healthWrites
import app.circlet.data.health.writeMeasured
import app.circlet.data.repo.Store
import app.circlet.data.repo.assemble
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class RecomputePipeline(
    private val store: Store,
    private val health: HealthConnectGateway,
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val gate = kotlinx.coroutines.sync.Mutex()
    private val quiet = QuietPeriod(store.debounceMs)

    fun start() {
        store.scope.launch {
            for (signal in signals) {
                quiet.await(signals)
                recomputeDirty()
            }
        }
    }

    fun nudge() {
        signals.trySend(Unit)
    }

    suspend fun recomputeDirty() {
        gate.withLock {
            var attempts = 0
            while (attempts++ < 8) {
                val dates = store.io { store.database.dirty().dates() }
                if (dates.isEmpty()) return
                var retry = false
                for (iso in dates) {
                    if (recomputeOne(LocalDate.parse(iso)) == Step.RETRY) retry = true
                }
                if (!retry) return
            }
        }
    }

    private suspend fun recomputeOne(date: LocalDate): Step {
        val iso = date.toString()
        val captured = store.lock.withLock {
            store.io { capture(date) }
        } ?: return Step.DONE
        val computed = try {
            store.dayEngine.computeDay(captured.input)
        } catch (error: Exception) {
            return Step.FAILED
        }
        val stored = store.lock.withLock {
            store.io {
                val current = store.database.dirty().generation(iso)
                if (current != captured.generation) return@io false
                store.database.runInTransaction {
                    val blended = blendActivities(date, captured.storedActivities, computed.activities)
                    store.database.activities().deleteUnlockedAutos(iso)
                    if (blended.isNotEmpty()) store.database.activities().insert(blended)
                    val visible = blended.mapNotNull { row ->
                        if (row.suppressed) null else ActivityCodec.decode(row.payload)
                    }
                    val summary = computed.copy(date = date, activities = visible)
                    store.database.days().upsert(
                        DaySummaryEntity(
                            date = iso,
                            algorithmVersion = store.dayEngine.algorithmVersion,
                            computedAtMillis = store.time.now().toEpochMilli(),
                            payload = DayCodec.encode(summary),
                        ),
                    )
                    store.database.dirty().clear(iso, captured.generation)
                }
                true
            }
        }
        if (!stored) return Step.RETRY
        export(date)
        return Step.DONE
    }

    private fun capture(date: LocalDate): Captured? {
        val iso = date.toString()
        val generation = store.database.dirty().generation(iso) ?: return null
        val zone = store.time.zone()
        val window = sampleWindow(date, zone)
        val samples = store.database.samples().range(window.first, window.last).map { it.toSample() }
        val historyFrom = date.minusDays(60).toString()
        val historyTo = date.minusDays(1).toString()
        val historyRows = store.database.days().range(historyFrom, historyTo)
        val historyActivities = store.database.activities().range(historyFrom, historyTo).groupBy { it.localDate }
        val history = historyRows.map { row ->
            assemble(LocalDate.parse(row.date), row, historyActivities[row.date].orEmpty())
        }
        val storedActivities = store.database.activities().forDate(iso)
        val manual = storedActivities.mapNotNull { row ->
            if (row.suppressed) return@mapNotNull null
            if (row.source != ActivitySource.MANUAL.name && !row.userEdited) return@mapNotNull null
            ActivityCodec.decode(row.payload)
        }
        val profile = store.database.profile().get()?.let { ProfileCodec.decode(it.payload) } ?: defaultProfile()
        return Captured(
            generation = generation,
            storedActivities = storedActivities,
            input = DayInput(
                date = date,
                zone = zone,
                samples = samples,
                profile = profile,
                history = history,
                manualActivities = manual,
                now = store.time.now(),
                priorSkinTemp = history.mapNotNull { day ->
                    day.skinTemperature?.settledMeanCounts?.let { day.date to it }
                }.toMap(),
            ),
        )
    }

    private suspend fun export(date: LocalDate) {
        val enabled = store.io { store.database.kv().get(Keys.HEALTH_ENABLED) == "true" }
        if (!enabled) return
        val summary = store.io {
            assemble(
                date,
                store.database.days().get(date.toString()),
                store.database.activities().forDate(date.toString()),
            )
        }
        // The phone's copy is already stored. Health Connect is a second copy and must not fail the recompute.
        try {
            writeMeasured(health, healthWrites(summary, store.time.zone(), store.time.now()))
        } catch (error: Exception) {
            return
        }
    }

    private enum class Step { DONE, RETRY, FAILED }

    private data class Captured(
        val generation: Long,
        val storedActivities: List<app.circlet.data.db.ActivityEntity>,
        val input: DayInput,
    )
}
