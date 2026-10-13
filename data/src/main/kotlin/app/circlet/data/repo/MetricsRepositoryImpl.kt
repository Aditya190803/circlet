package app.circlet.data.repo

import app.circlet.data.importer.IMPORTED_ALGORITHM_VERSION
import app.circlet.core.CoreError
import app.circlet.core.DaySummary
import app.circlet.core.HrvSpotCheck
import app.circlet.core.MetricKind
import app.circlet.core.MetricsRepository
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.SleepEdit
import app.circlet.core.TrendPoint
import app.circlet.core.TrendRange
import app.circlet.core.WeeklyRecap
import app.circlet.core.rejectedSleepWindow
import app.circlet.data.codec.SpotCodec
import app.circlet.data.pipeline.RecomputePipeline
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

internal class MetricsRepositoryImpl(
    private val store: Store,
    private val pipeline: RecomputePipeline,
) : MetricsRepository {

    override fun observeDay(date: LocalDate): Flow<DaySummary> = store.generation.flatMapLatest {
        flow {
            noteStale(date)
            emitAll(
                combine(
                    store.database.days().observe(date.toString()),
                    store.database.activities().observe(date.toString()),
                ) { row, activities -> assemble(date, row, activities) },
            )
        }
    }.distinctUntilChanged()

    override fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DaySummary>> {
        if (to < from) return flowOf(emptyList())
        val dates = ArrayList<LocalDate>()
        var date = from
        while (!date.isAfter(to)) {
            dates += date
            date = date.plusDays(1)
        }
        if (dates.size == 1) return observeDay(dates.first()).map { listOf(it) }
        return combine(dates.map { observeDay(it) }) { days -> days.toList() }
    }

    override suspend fun trend(metric: MetricKind, range: TrendRange): Outcome<List<TrendPoint>> = storage {
        val today = store.time.now().atZone(store.time.zone()).toLocalDate()
        val from = when (range) {
            TrendRange.WEEK -> today.minusDays(6)
            TrendRange.MONTH -> today.minusDays(29)
        }
        settle(from, today)
        Outcome.Ok(store.insightEngine.trend(loadRange(from, today), metric))
    }

    override suspend fun records(): Outcome<List<PersonalRecord>> = storage {
        settle(null, null)
        Outcome.Ok(store.insightEngine.records(loadStoredDays()))
    }

    override suspend fun weeklyRecap(weekStart: LocalDate): Outcome<WeeklyRecap> {
        if (weekStart.dayOfWeek != DayOfWeek.MONDAY) {
            return Outcome.Err(CoreError.Invalid("week starts on Monday"))
        }
        return storage {
            settle(weekStart, weekStart.plusDays(6))
            Outcome.Ok(store.insightEngine.weeklyRecap(weekStart, loadRange(weekStart, weekStart.plusDays(6))))
        }
    }

    override fun observeSpotChecks(date: LocalDate): Flow<List<HrvSpotCheck>> = store.generation.flatMapLatest {
        store.database.spotChecks().observe(date.toString()).map { rows ->
            rows.map { SpotCodec.decode(it.payload) }
        }
    }

    override fun observeSleepEdit(date: LocalDate): Flow<SleepEdit?> =
        store.database.sleepEdits().observe(date.toString()).map { it?.toEdit() }.distinctUntilChanged()

    override suspend fun saveSleepEdit(date: LocalDate, edit: SleepEdit?): Outcome<Unit> {
        val window = edit?.window
        if (window != null) {
            val rejected = rejectedSleepWindow(window.first, window.second, store.time.now())
            if (rejected != null) return Outcome.Err(CoreError.Invalid(rejected))
        }
        return storage {
            val zone = store.time.zone()
            val iso = date.toString()
            store.lock.withLock {
                store.io {
                    val dao = store.database.sleepEdits()
                    if (edit == null || edit.isEmpty) dao.delete(iso) else dao.upsert(edit.toEntity(iso))
                    store.database.dirty().mark(iso)
                    if (window != null) {
                        for (instant in window.toList()) {
                            val touched = instant.atZone(zone).toLocalDate().toString()
                            if (touched != iso) store.database.dirty().mark(touched)
                        }
                    }
                }
            }
            pipeline.nudge()
            Outcome.Ok(Unit)
        }
    }

    internal suspend fun noteStale(date: LocalDate) {
        val iso = date.toString()
        val stale = store.io {
            val row = store.database.days().get(iso)
            row != null && row.algorithmVersion != IMPORTED_ALGORITHM_VERSION &&
                row.algorithmVersion != store.dayEngine.algorithmVersion
        }
        if (stale) store.io { store.database.dirty().mark(iso) }
        val dirty = stale || store.io { store.database.dirty().generation(iso) != null }
        if (dirty) pipeline.nudge()
    }

    /** Mark version-mismatched rows dirty, then compute the oldest dirty day first. */
    internal suspend fun settle(from: LocalDate?, to: LocalDate?) {
        store.io {
            val rows = if (from == null || to == null) {
                store.database.days().all()
            } else {
                store.database.days().range(from.toString(), to.toString())
            }
            rows.filter { it.algorithmVersion != IMPORTED_ALGORITHM_VERSION && it.algorithmVersion != store.dayEngine.algorithmVersion }
                .forEach { store.database.dirty().mark(it.date) }
        }
        pipeline.recomputeDirty()
    }

    internal suspend fun loadRange(from: LocalDate, to: LocalDate): List<DaySummary> {
        if (to < from) return emptyList()
        return store.io {
            val rows = store.database.days().range(from.toString(), to.toString()).associateBy { it.date }
            val activities = store.database.activities().range(from.toString(), to.toString()).groupBy { it.localDate }
            buildList {
                var date = from
                while (!date.isAfter(to)) {
                    add(assemble(date, rows[date.toString()], activities[date.toString()].orEmpty()))
                    date = date.plusDays(1)
                }
            }
        }
    }

    internal suspend fun loadStoredDays(): List<DaySummary> = store.io {
        val activities = store.database.activities().all().groupBy { it.localDate }
        store.database.days().all().map { row ->
            assemble(LocalDate.parse(row.date), row, activities[row.date].orEmpty())
        }
    }
}

private suspend inline fun <T> storage(block: suspend () -> Outcome<T>): Outcome<T> = try {
    block()
} catch (error: Exception) {
    Outcome.Err(CoreError.Storage(error.message ?: "storage failed"))
}
