package app.circlet.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.Haptic
import app.circlet.core.StrapController
import app.circlet.core.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val BREATHE_PACES = listOf(4.5, 5.5, 6.0, 7.0)
val BREATHE_MINUTES = listOf(2, 5, 10)
private const val DEFAULT_PACE = 2
private const val DEFAULT_LENGTH = 1
private const val TICK_MS = 33L

/**
 * What the breathing screen shows. [startBpm] is the first live reading of the session and [bpm] the latest;
 * both are null while the strap has sent none.
 */
data class BreatheUi(
    val paceIndex: Int = DEFAULT_PACE,
    val lengthIndex: Int = DEFAULT_LENGTH,
    val buzz: Boolean = false,
    val running: Boolean = false,
    val finished: Boolean = false,
    val elapsedMs: Long = 0,
    val bpm: Int? = null,
    val startBpm: Int? = null,
) {
    val breathsPerMin: Double get() = BREATHE_PACES[paceIndex]
    val totalMs: Long get() = BREATHE_MINUTES[lengthIndex] * 60_000L
    val remainingMs: Long get() = (totalMs - elapsedMs).coerceAtLeast(0)
}

/** Runs one paced-breathing session and follows the strap's live heart rate while it lasts. */
class BreatheViewModel(private val strap: StrapController, private val time: TimeSource) : ViewModel() {
    private val state = MutableStateFlow(BreatheUi())
    val ui: StateFlow<BreatheUi> = state.asStateFlow()
    private var session: Job? = null

    fun setPace(index: Int) = state.update { if (it.running) it else it.copy(paceIndex = index) }

    fun setLength(index: Int) = state.update { if (it.running) it else it.copy(lengthIndex = index) }

    fun setBuzz(on: Boolean) = state.update { it.copy(buzz = on) }

    fun start() {
        if (state.value.running) return
        state.update { it.copy(running = true, finished = false, elapsedMs = 0, bpm = null, startBpm = null) }
        session = viewModelScope.launch {
            strap.setLiveHeartRate(true)
            try {
                run()
            } finally {
                withContext(NonCancellable) { strap.setLiveHeartRate(false) }
            }
        }
    }

    fun stop() {
        session?.cancel()
        state.update { it.copy(running = false) }
    }

    private suspend fun run() {
        val begin = time.now().toEpochMilli()
        var lastIndex = -1
        var lastInhaling = true
        while (true) {
            val elapsed = time.now().toEpochMilli() - begin
            val current = state.value
            if (elapsed >= current.totalMs) {
                state.update { it.copy(running = false, finished = true, elapsedMs = it.totalMs) }
                return
            }
            val live = strap.state.value.liveHeartRateBpm?.takeIf { it > 0 }
            state.update { it.copy(elapsedMs = elapsed, bpm = live, startBpm = it.startBpm ?: live) }
            val phase = phaseAt(elapsed, current.breathsPerMin)
            if (current.buzz && (phase.index != lastIndex || phase.inhaling != lastInhaling)) {
                viewModelScope.launch { strap.buzz(Haptic.TEST) }
            }
            lastIndex = phase.index
            lastInhaling = phase.inhaling
            delay(TICK_MS)
        }
    }

    override fun onCleared() {
        session?.cancel()
    }
}
