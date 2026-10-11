package app.circlet.ui.alarm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.AlarmController
import app.circlet.core.Haptic
import app.circlet.core.LinkState
import app.circlet.core.Outcome
import app.circlet.core.ScheduledAlarm
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapController
import app.circlet.core.TimeSource
import app.circlet.ui.design.words
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AlarmUiState {
    object Loading : AlarmUiState

    data class Ready(
        val now: Instant,
        val alarms: List<StrapAlarm>,
        val next: ScheduledAlarm?,
        val link: LinkState,
    ) : AlarmUiState
}

/** A new alarm: 07:00 on weekdays, smart wake off, switched on. */
fun newAlarm(): StrapAlarm = StrapAlarm(
    id = UUID.randomUUID().toString(),
    time = LocalTime.of(7, 0),
    weekdays = WEEK_ORDER.take(5).toSet(),
    smartWake = Duration.ZERO,
    enabled = true,
)

class AlarmViewModel(
    private val alarms: AlarmController,
    private val strap: StrapController,
    private val time: TimeSource,
) : ViewModel() {
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** Ticks once a minute so the countdown in the header stays current. */
    private val minuteTicks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000L)
        }
    }

    val state: StateFlow<AlarmUiState> = combine(alarms.alarms, strap.state, minuteTicks) { list, strapState, _ ->
        val now = time.now()
        AlarmUiState.Ready(
            now = now,
            alarms = list,
            next = earliestNext(list, now, time.zone()),
            link = strapState.link,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmUiState.Loading)

    /** Saves an alarm as given. [onSaved] runs only on success, so the editor can close. */
    fun save(alarm: StrapAlarm, onSaved: () -> Unit) =
        report("Alarm saved.", onSaved) { alarms.set(alarm) }

    /** Switches an alarm on (re-saved enabled) or off (through disable). */
    fun remove(id: String, onRemoved: () -> Unit) =
        report("Alarm deleted.", onRemoved) { alarms.remove(id) }

    fun toggle(alarm: StrapAlarm, on: Boolean) =
        report(null) { if (on) alarms.set(alarm.copy(enabled = true)) else alarms.disable(alarm.id) }

    fun setSmartWake(id: String, window: Duration) =
        report("Smart wake saved.") { alarms.setSmartWake(id, window) }

    fun buzz() = report("Buzz sent.") { strap.buzz(Haptic.TEST) }

    private fun report(done: String?, onOk: () -> Unit = {}, call: suspend () -> Outcome<*>) {
        viewModelScope.launch {
            when (val result = call()) {
                is Outcome.Ok -> {
                    done?.let { messageChannel.send(it) }
                    onOk()
                }
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
        }
    }
}

/** Weekday set used when a day list is needed but none was chosen. Kept here so the editor and tests agree. */
val DEFAULT_WEEKDAYS: Set<DayOfWeek> = WEEK_ORDER.take(5).toSet()
