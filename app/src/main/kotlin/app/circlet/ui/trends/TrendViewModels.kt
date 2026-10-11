package app.circlet.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricsRepository
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.TimeSource
import app.circlet.core.TrendRange
import app.circlet.core.WeeklyRecap
import app.circlet.ui.design.words
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.today
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

private const val READ_FAILED = "Circlet could not read your history from its storage."
private const val STOP_AFTER_MS = 5_000L

private fun <T> Flow<T>.loadable(failure: String): Flow<Loadable<T>> =
    map<T, Loadable<T>> { Loadable.Ready(it) }
        .onStart { emit(Loadable.Loading) }
        .catch { emit(Loadable.Failed(failure)) }

/** One metric's row on the overview: the latest real reading and its average over the last 30 days. */
data class KindRow(
    val kind: MetricKind,
    val latest: Double?,
    val average: Double?,
    val points: List<Float?>,
)

data class TrendsUiState(
    val today: LocalDate,
    val month: YearMonth,
    val canShowNext: Boolean,
    /** Recovery score by date for the displayed month. Days without a score are absent from the map. */
    val calendar: Loadable<Map<LocalDate, Double?>>,
    val rows: Loadable<List<KindRow>>,
) {
    companion object {
        fun initial(today: LocalDate) = TrendsUiState(
            today = today,
            month = YearMonth.from(today),
            canShowNext = false,
            calendar = Loadable.Loading,
            rows = Loadable.Loading,
        )
    }
}

/** The month calendar and the per-metric overview. */
class TrendsViewModel(private val metrics: MetricsRepository, time: TimeSource) : ViewModel() {
    private val today: LocalDate = time.today()
    private val month = MutableStateFlow(YearMonth.from(today))

    private val calendar: Flow<Loadable<Map<LocalDate, Double?>>> = month.flatMapLatest { shown ->
        metrics.observeRange(shown.atDay(1), minOf(shown.atEndOfMonth(), today))
            .map { days -> days.associate { it.date to it.metricValue(MetricKind.RECOVERY).value } }
            .loadable(READ_FAILED)
    }

    private val overview: Flow<Loadable<List<KindRow>>> = metrics
        .observeRange(currentWindow(TrendRange.MONTH, today).from, today)
        .map { days -> overviewRows(days) }
        .loadable(READ_FAILED)

    val state: StateFlow<TrendsUiState> = combine(month, calendar, overview) { shown, cal, rows ->
        TrendsUiState(
            today = today,
            month = shown,
            canShowNext = canShowMonthAfter(shown, today),
            calendar = cal,
            rows = rows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), TrendsUiState.initial(today))

    /** Moves the calendar by [months]. Never past the month containing today. */
    fun shiftMonth(months: Long) {
        month.update { shiftMonth(it, months, today) }
    }

    private fun overviewRows(days: List<DaySummary>): List<KindRow> {
        val ordered = days.sortedBy { it.date }
        return MetricKind.values().map { kind ->
            val values = ordered.map { it.metricValue(kind).value }
            KindRow(
                kind = kind,
                latest = values.lastOrNull { it != null },
                average = values.averageOrNull(),
                points = values.map { it?.toFloat() },
            )
        }
    }
}

data class MetricTrendUiState(
    val range: TrendRange,
    val content: Loadable<MetricTrend>,
)

/** One metric over a week or a month, with its equal previous period for comparison. */
class MetricTrendViewModel(private val metrics: MetricsRepository, time: TimeSource) : ViewModel() {
    private val today: LocalDate = time.today()
    private val kind = MutableStateFlow<MetricKind?>(null)
    private val range = MutableStateFlow(TrendRange.WEEK)

    val state: StateFlow<MetricTrendUiState> = combine(kind, range) { k, r -> k to r }
        .flatMapLatest { (k, r) ->
            if (k == null) {
                flowOf(MetricTrendUiState(r, Loadable.Loading))
            } else {
                trend(k, r).map { MetricTrendUiState(r, it) }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_AFTER_MS),
            MetricTrendUiState(TrendRange.WEEK, Loadable.Loading),
        )

    /** Sets the metric the screen shows. The screen calls this when it opens and when the kind changes. */
    fun open(metric: MetricKind) {
        kind.value = metric
    }

    fun select(newRange: TrendRange) {
        range.value = newRange
    }

    private fun trend(metric: MetricKind, selected: TrendRange): Flow<Loadable<MetricTrend>> {
        val previous = previousWindow(selected, today)
        return metrics.observeRange(previous.from, today)
            .map { days ->
                val byDate = days.associate { it.date to it.metricValue(metric).value }
                metricTrend(metric, selected, today) { byDate[it] }
            }
            .loadable(READ_FAILED)
    }
}

data class WeeklyRecapUiState(
    val weekStart: LocalDate,
    val canShowNext: Boolean,
    val content: Loadable<WeeklyRecap>,
)

/** One week at a time, from the current week back. */
class WeeklyRecapViewModel(private val metrics: MetricsRepository, time: TimeSource) : ViewModel() {
    private val today: LocalDate = time.today()
    private val week = MutableStateFlow(currentWeekStart(today))
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val state: StateFlow<WeeklyRecapUiState> = week.flatMapLatest { shown ->
        flow {
            val canNext = canShowWeekAfter(shown, today)
            emit(WeeklyRecapUiState(shown, canNext, Loadable.Loading))
            emit(WeeklyRecapUiState(shown, canNext, load(shown)))
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_AFTER_MS),
        WeeklyRecapUiState(currentWeekStart(today), canShowWeekAfter(currentWeekStart(today), today), Loadable.Loading),
    )

    /** Moves by [weeks]. Never past the current week. */
    fun shiftWeek(weeks: Long) {
        week.update { shiftWeek(it, weeks, today) }
    }

    private suspend fun load(weekStart: LocalDate): Loadable<WeeklyRecap> =
        when (val result = metrics.weeklyRecap(weekStart)) {
            is Outcome.Ok -> Loadable.Ready(result.value)
            is Outcome.Err -> {
                val words = result.error.words()
                messageChannel.send(words)
                Loadable.Failed(words)
            }
        }
}

data class RecordsUiState(val content: Loadable<List<RecordGroup>>)

/** Personal records, grouped by kind. */
class RecordsViewModel(private val metrics: MetricsRepository) : ViewModel() {
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val state: StateFlow<RecordsUiState> = flow {
        emit(RecordsUiState(Loadable.Loading))
        emit(RecordsUiState(load()))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), RecordsUiState(Loadable.Loading))

    private suspend fun load(): Loadable<List<RecordGroup>> =
        when (val result = metrics.records()) {
            is Outcome.Ok -> Loadable.Ready(groupRecords(result.value))
            is Outcome.Err -> {
                val words = result.error.words()
                messageChannel.send(words)
                Loadable.Failed(words)
            }
        }
}
