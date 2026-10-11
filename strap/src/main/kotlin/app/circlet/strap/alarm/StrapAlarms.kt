package app.circlet.strap.alarm

import app.circlet.core.AlarmController
import app.circlet.core.CoreError
import app.circlet.core.DoubleTapAction
import app.circlet.core.Outcome
import app.circlet.core.ScheduledAlarm
import app.circlet.core.SleepStageEstimate
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapPrefs
import app.circlet.core.TimeSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AlarmRadio {
    suspend fun arm(at: Instant): Outcome<Unit>
    suspend fun disarm(): Outcome<Unit>
    suspend fun buzz(): Outcome<Unit>
    suspend fun stop(): Outcome<Unit>
    fun connected(): Boolean
}

/**
 * The phone keeps the weekly list. Gen 4's rev-1 alarm is a single instant, so the
 * soonest occurrence is what gets written to the strap, again after each firing and
 * on every connect.
 */
internal class StrapAlarms(
    private val prefs: StrapPrefs,
    private val time: TimeSource,
    private val sleepStage: SleepStageEstimate,
    private val doubleTap: app.circlet.core.DoubleTapPolicy,
    private val radio: AlarmRadio,
) : AlarmController {
    private val alarmsState = MutableStateFlow<List<StrapAlarm>>(emptyList())
    private val nextState = MutableStateFlow<ScheduledAlarm?>(null)
    private val gate = Mutex()
    private val buzzedEarly = HashSet<String>()
    private var armedFor: Instant? = null

    override val alarms: StateFlow<List<StrapAlarm>> = alarmsState
    override val next: StateFlow<ScheduledAlarm?> = nextState

    suspend fun load() = gate.withLock {
        alarmsState.value = prefs.alarms()
        publishNext()
    }

    fun watch(scope: CoroutineScope, sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
        scope.launch {
            while (isActive) {
                tick()
                sleep(15_000)
            }
        }
    }

    suspend fun onConnected() = gate.withLock { armSoonest() }

    suspend fun onFired() = gate.withLock {
        armedFor = null
        armSoonest()
    }

    suspend fun onDoubleTap() {
        if (doubleTap.current() == DoubleTapAction.STOP_ALARM) radio.stop()
    }

    override suspend fun set(alarm: StrapAlarm): Outcome<Unit> = gate.withLock {
        if (alarm.id.isBlank()) return@withLock invalid("Alarm id is empty")
        if (alarm.smartWake.isNegative) return@withLock invalid("Smart wake cannot be negative")
        alarmsState.value = alarmsState.value.filter { it.id != alarm.id } + alarm
        prefs.setAlarms(alarmsState.value)
        armSoonest()
        Outcome.Ok(Unit)
    }

    override suspend fun disable(id: String): Outcome<Unit> = gate.withLock {
        val current = alarmsState.value
        if (current.none { it.id == id }) return@withLock invalid("No alarm with that id")
        alarmsState.value = current.map { if (it.id == id) it.copy(enabled = false) else it }
        prefs.setAlarms(alarmsState.value)
        armSoonest()
        Outcome.Ok(Unit)
    }

    override suspend fun remove(id: String): Outcome<Unit> = gate.withLock {
        val current = alarmsState.value
        if (current.none { it.id == id }) return@withLock invalid("No alarm with that id")
        alarmsState.value = current.filter { it.id != id }
        prefs.setAlarms(alarmsState.value)
        armSoonest()
        Outcome.Ok(Unit)
    }

    override suspend fun setSmartWake(id: String, window: Duration): Outcome<Unit> = gate.withLock {
        if (window.isNegative) return@withLock invalid("Smart wake cannot be negative")
        val current = alarmsState.value
        if (current.none { it.id == id }) return@withLock invalid("No alarm with that id")
        alarmsState.value = current.map { if (it.id == id) it.copy(smartWake = window) else it }
        prefs.setAlarms(alarmsState.value)
        publishNext()
        Outcome.Ok(Unit)
    }

    internal suspend fun tick() = gate.withLock {
        val decision = decideTick(
            alarms = alarmsState.value,
            armedFor = armedFor,
            now = time.now(),
            zone = time.zone(),
            stage = sleepStage.current(),
            connected = radio.connected(),
            alreadyBuzzed = { alarm, at -> key(alarm, at) in buzzedEarly },
        )
        when (decision) {
            AlarmTick.None -> publishNext()
            AlarmTick.Disarm -> {
                armedFor = null
                if (radio.connected()) radio.disarm()
                publishNext()
            }
            is AlarmTick.Rearm -> {
                armedFor = decision.at
                if (radio.connected()) radio.arm(decision.at)
                publishNext()
            }
            is AlarmTick.FireEarly -> {
                buzzedEarly += key(decision.alarm, decision.occurrence)
                if (radio.connected()) {
                    radio.buzz()
                    if (decision.then != null) radio.arm(decision.then)
                    else radio.disarm()
                }
                armedFor = decision.then
                publishNext()
            }
        }
    }

    private suspend fun armSoonest() {
        val upcoming = AlarmScheduler.next(alarmsState.value, time.now(), time.zone())
        armedFor = upcoming?.at
        nextState.value = upcoming
        if (!radio.connected()) return
        if (upcoming == null) radio.disarm() else radio.arm(upcoming.at)
    }

    private fun publishNext() {
        nextState.value = AlarmScheduler.next(alarmsState.value, time.now(), time.zone())
    }

    private fun key(alarm: StrapAlarm, at: Instant): String = "${alarm.id}@${at.epochSecond}"

    private fun invalid(detail: String): Outcome<Unit> = Outcome.Err(CoreError.Invalid(detail))
}
