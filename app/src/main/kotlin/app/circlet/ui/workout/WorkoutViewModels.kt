package app.circlet.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.Activity
import app.circlet.core.DaySummary
import app.circlet.core.LiveWorkout
import app.circlet.core.MetricsRepository
import app.circlet.core.Outcome
import app.circlet.core.Sport
import app.circlet.core.TimeSource
import app.circlet.core.WorkoutController
import app.circlet.ui.design.words
import app.circlet.ui.state.Loadable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkoutsPage(
    val activities: List<Activity>,
    val selected: Set<String>,
    val mergeable: Boolean,
)

class WorkoutsViewModel(
    private val metrics: MetricsRepository,
    private val workouts: WorkoutController,
    private val time: TimeSource,
) : ViewModel() {
    private val day = MutableStateFlow<LocalDate?>(null)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val loaded: Flow<Loadable<List<Activity>>> = day.flatMapLatest<LocalDate?, Loadable<List<Activity>>> { date ->
        if (date == null) {
            flowOf(Loadable.Loading)
        } else {
            metrics.observeDay(date)
                .map<DaySummary, Loadable<List<Activity>>> { Loadable.Ready(it.activities) }
                .catch { emit(Loadable.Failed("This day could not be read.")) }
        }
    }

    val state: StateFlow<Loadable<WorkoutsPage>> = combine(loaded, selected) { result, picked ->
        when (result) {
            is Loadable.Ready -> {
                val activities = newestFirst(result.value)
                val ids = activities.map { it.id }.toSet()
                val live = picked.intersect(ids)
                Loadable.Ready(
                    WorkoutsPage(
                        activities = activities,
                        selected = live,
                        mergeable = canMerge(activities, live, time.zone()),
                    ),
                )
            }
            is Loadable.Failed -> result
            Loadable.Loading -> Loadable.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    fun setDate(date: LocalDate) {
        if (day.value == date) return
        day.value = date
        selected.value = emptySet()
    }

    fun toggle(id: String) {
        selected.update { if (id in it) it - id else it + id }
    }

    fun merge() {
        val page = (state.value as? Loadable.Ready)?.value ?: return
        if (!page.mergeable) return
        val ids = page.selected.toList()
        viewModelScope.launch {
            when (val result = workouts.merge(ids)) {
                is Outcome.Ok -> {
                    selected.value = emptySet()
                    messageChannel.send("MERGED")
                }
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}

class ActivityDetailViewModel(
    private val metrics: MetricsRepository,
    private val workouts: WorkoutController,
) : ViewModel() {
    private val target = MutableStateFlow<Pair<String, LocalDate>?>(null)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    private val deletedChannel = Channel<Unit>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()
    val deleted: Flow<Unit> = deletedChannel.receiveAsFlow()

    val state: StateFlow<Loadable<Activity>> = target.flatMapLatest<Pair<String, LocalDate>?, Loadable<Activity>> { open ->
        if (open == null) {
            flowOf(Loadable.Loading)
        } else {
            metrics.observeDay(open.second)
                .map<DaySummary, Loadable<Activity>> { day ->
                    val found = day.activities.firstOrNull { it.id == open.first }
                    if (found == null) Loadable.Failed("This activity is no longer recorded for this day.") else Loadable.Ready(found)
                }
                .catch { emit(Loadable.Failed("This activity could not be read.")) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    fun open(id: String, date: LocalDate) {
        target.value = id to date
    }

    fun delete() {
        val id = target.value?.first ?: return
        viewModelScope.launch {
            when (val result = workouts.delete(id)) {
                is Outcome.Ok -> deletedChannel.send(Unit)
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}

class StartWorkoutViewModel(private val workouts: WorkoutController) : ViewModel() {
    private val queryFlow = MutableStateFlow("")
    val query: StateFlow<String> = queryFlow
    val sports: StateFlow<List<Sport>> = queryFlow
        .map { filterSports(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), filterSports(""))
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    private val startedChannel = Channel<Unit>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()
    val started: Flow<Unit> = startedChannel.receiveAsFlow()

    fun setQuery(text: String) {
        queryFlow.value = text
    }

    fun start(sport: Sport) {
        viewModelScope.launch {
            when (val result = workouts.start(sport)) {
                is Outcome.Ok -> startedChannel.send(Unit)
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}

sealed interface LiveUi {
    data object Loading : LiveUi
    data object Idle : LiveUi
    data class Recording(val live: LiveWorkout, val elapsed: Duration) : LiveUi
}

class LiveWorkoutViewModel(
    private val workouts: WorkoutController,
    private val time: TimeSource,
) : ViewModel() {
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    private val finishedChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()
    val finished: Flow<String> = finishedChannel.receiveAsFlow()

    private val ticks: Flow<Instant> = flow {
        while (true) {
            emit(time.now())
            delay(1_000)
        }
    }

    val state: StateFlow<LiveUi> = combine(workouts.live, ticks) { live, now ->
        if (live == null) {
            LiveUi.Idle
        } else {
            LiveUi.Recording(live, Duration.between(live.startedAt, now))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUi.Loading)

    fun stop() {
        viewModelScope.launch {
            when (val result = workouts.stop()) {
                is Outcome.Ok -> finishedChannel.send(result.value.id)
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}
