package app.circlet.ui.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.DaySummary
import app.circlet.core.LinkState
import app.circlet.core.MetricsRepository
import app.circlet.core.Outcome
import app.circlet.core.StrapController
import app.circlet.core.StrapError
import app.circlet.core.StrapState
import app.circlet.core.TimeSource
import app.circlet.core.WellnessFlag
import app.circlet.ui.design.words
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something a screen is waiting for, has, or could not get. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Ready<T>)?.value

/** The one thing the day screens need to tell the user right now, if anything. */
/** Scored nights the recovery baseline needs before it stops being provisional. */
const val BASELINE_NIGHTS = 14

sealed interface Notice {
    data object NeedsPermission : Notice
    data object Unpaired : Notice
    data object BluetoothOff : Notice
    data object Searching : Notice
    data class StrapTrouble(val error: StrapError) : Notice
    data class CatchingUp(val minutes: Int) : Notice
    data object FirstNight : Notice
    data class Calibrating(val nights: Int, val left: Int) : Notice
    data class Wellness(val flag: WellnessFlag) : Notice
    data class EmptyDay(val today: Boolean, val reason: AbsentReason) : Notice
}

/**
 * Everything Home and the three detail screens draw for the selected date.
 * [history] is up to sixty days ending on [date], oldest first, and is empty while loading.
 */
data class DayUiState(
    val date: LocalDate,
    val today: LocalDate,
    val day: Loadable<DaySummary>,
    val history: List<DaySummary>,
    val strap: StrapState,
) {
    val isToday: Boolean get() = date == today
    val week: List<DaySummary> get() = history.takeLast(7)

    /** Days before [date], used for averages the day is compared against. */
    val earlier: List<DaySummary> get() = history.dropLast(1)

    val nightsRecorded: Int get() = history.count { it.sleep.present }

    /** Earlier nights that carry a recovery score: what the personal baseline is built from. */
    val baselineNights: Int get() = earlier.count { it.recovery.score.present }.coerceAtMost(BASELINE_NIGHTS)

    val baselineNightsLeft: Int get() = BASELINE_NIGHTS - baselineNights

    val notice: Notice?
        get() {
            val summary = day.valueOrNull ?: return null
            if (!isToday) {
                return if (summary.hasData) null else Notice.EmptyDay(today = false, reason = summary.emptyReason)
            }
            val error = strap.error
            return when {
                error == StrapError.PermissionMissing -> Notice.NeedsPermission
                error == StrapError.BluetoothOff -> Notice.BluetoothOff
                error == StrapError.AccountLocked -> Notice.StrapTrouble(error)
                strap.link == LinkState.UNPAIRED -> Notice.Unpaired
                strap.link != LinkState.CONNECTED -> Notice.Searching
                error != null -> Notice.StrapTrouble(error)
                strap.sync.draining && strap.sync.samplesPulled >= 600 ->
                    Notice.CatchingUp((strap.sync.samplesPulled / 60).toInt())
                summary.recovery.calibration == CalibrationStatus.CALIBRATING ->
                    if (nightsRecorded == 0) Notice.FirstNight else Notice.Calibrating(baselineNights, baselineNightsLeft)
                summary.wellness.isNotEmpty() -> Notice.Wellness(summary.wellness.first())
                !summary.hasData -> Notice.EmptyDay(today = true, reason = summary.emptyReason)
                else -> null
            }
        }
}

/** True when the strap delivered anything at all for the day. */
val DaySummary.hasData: Boolean
    get() = sleep.present || strain.score.present || heartRateByMinute.any { it != null }

private val DaySummary.emptyReason: AbsentReason
    get() = strain.score.absentReason ?: sleep.absentReason ?: AbsentReason.NOT_ENOUGH_DATA

private const val HISTORY_DAYS = 60L

/** The day state for whichever date [dates] carries. Shared by the view model and the debug gallery. */
@OptIn(ExperimentalCoroutinesApi::class)
fun dayUiState(
    metrics: MetricsRepository,
    strap: StrapController,
    time: TimeSource,
    dates: Flow<LocalDate>,
): Flow<DayUiState> = dates.flatMapLatest { date ->
    val day = metrics.observeDay(date)
        .map<DaySummary, Loadable<DaySummary>> { Loadable.Ready(it) }
        .onStart { emit(Loadable.Loading) }
        .catch { emit(Loadable.Failed("Circlet could not read this day from its storage.")) }
    val history = metrics.observeRange(date.minusDays(HISTORY_DAYS - 1), date)
        .onStart { emit(emptyList()) }
        .catch { emit(emptyList()) }
    combine(day, history, strap.state) { summary, days, strapState ->
        DayUiState(date, time.today(), summary, days, strapState)
    }
}

fun TimeSource.today(): LocalDate = now().atZone(zone()).toLocalDate()

class DayViewModel(
    private val metrics: MetricsRepository,
    private val strap: StrapController,
    private val time: TimeSource,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val epochDay = saved.getStateFlow(KEY_DATE, time.today().toEpochDay())
    private val attempt = MutableStateFlow(0)
    private val messageChannel = Channel<String>(Channel.BUFFERED)

    val state: StateFlow<DayUiState> = dayUiState(
        metrics, strap, time,
        combine(epochDay, attempt) { day, _ -> LocalDate.ofEpochDay(day) },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DayUiState(LocalDate.ofEpochDay(epochDay.value), time.today(), Loadable.Loading, emptyList(), strap.state.value),
    )

    val refreshing = MutableStateFlow(false)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun shift(days: Long) {
        val next = LocalDate.ofEpochDay(epochDay.value).plusDays(days)
        if (!next.isAfter(time.today())) saved[KEY_DATE] = next.toEpochDay()
    }

    fun retry() {
        attempt.value++
    }

    fun sync() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            val result = strap.syncNow()
            if (result is Outcome.Err) messageChannel.send(result.error.words())
            refreshing.value = false
        }
    }

    /** Live heart rate follows the foreground. A refusal is not worth interrupting the user for. */
    fun setLive(enabled: Boolean) {
        viewModelScope.launch { strap.setLiveHeartRate(enabled) }
    }

    private companion object {
        const val KEY_DATE = "date"
    }
}
