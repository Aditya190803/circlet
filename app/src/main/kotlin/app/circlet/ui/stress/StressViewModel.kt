package app.circlet.ui.stress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.MetricsRepository
import app.circlet.core.StressPoint
import app.circlet.core.TimeSource
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

sealed interface StressUiState {
    object Loading : StressUiState

    /**
     * The chosen day. [score] carries its own absent reason when the day has no
     * stress. [bands] is null when the timeline is empty, so no minutes are shown.
     * [week] is the seven days ending on [date], oldest first.
     */
    data class Ready(
        val date: LocalDate,
        val score: Metric<Double>,
        val timeline: List<StressPoint>,
        val dayStart: Instant,
        val bands: BandMinutes?,
        val week: List<DaySummary>,
    ) : StressUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class StressViewModel(
    private val metrics: MetricsRepository,
    private val time: TimeSource,
    initialDate: LocalDate,
) : ViewModel() {
    private val selected = MutableStateFlow(initialDate)

    /** Switches the day shown. The screen calls this when its date parameter changes. */
    fun select(date: LocalDate) {
        selected.value = date
    }

    val state: StateFlow<StressUiState> = selected
        .flatMapLatest { date ->
            combine(
                metrics.observeDay(date),
                metrics.observeRange(date.minusDays(6), date),
            ) { day, week ->
                val zone = time.zone()
                val timeline = day.stress.timeline
                StressUiState.Ready(
                    date = date,
                    score = day.stress.score,
                    timeline = timeline,
                    dayStart = date.atStartOfDay(zone).toInstant(),
                    bands = if (timeline.isEmpty()) null else bandMinutes(timeline),
                    week = week,
                )
            }
        }
        .stateIn<StressUiState>(viewModelScope, SharingStarted.WhileSubscribed(5_000), StressUiState.Loading)
}
