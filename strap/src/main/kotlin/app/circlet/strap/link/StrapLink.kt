package app.circlet.strap.link

import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.Haptic
import app.circlet.core.LinkState
import app.circlet.core.Outcome
import app.circlet.core.StrapController
import app.circlet.core.StrapError
import app.circlet.core.StrapEvent
import app.circlet.core.StrapPorts
import app.circlet.core.StrapState
import app.circlet.core.SyncProgress
import app.circlet.core.TimeSource
import app.circlet.strap.StrapHost
import app.circlet.strap.alarm.AlarmRadio
import app.circlet.strap.sync.DrainSchedule
import app.circlet.strap.sync.DrainTrigger
import app.circlet.strap.sync.EndResult
import app.circlet.strap.sync.HistoryDrain
import app.circlet.strap.wire.Inbound
import app.circlet.strap.wire.MetaKind
import app.circlet.strap.wire.ProtocolWire
import app.circlet.strap.wire.StrapSignal
import app.circlet.strap.workout.HeartDemand
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

// Ported from OpenStrap edge/lib/ble/ble_engine.dart — session, drain, live heart rate,
// and the reconnect loop — and edge/android EdgeTrackingService's keep-alive behaviour.
internal class StrapLink(
    private val transport: GattTransport,
    private val queue: GattOperationQueue,
    private val ports: StrapPorts,
    private val time: TimeSource,
    private val scope: CoroutineScope,
    private val host: StrapHost,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : StrapController, AlarmRadio, HeartDemand {
    private val stateFlow = MutableStateFlow(StrapState())
    private val scanningFlow = MutableStateFlow(false)
    private val resultsFlow = MutableStateFlow<List<FoundStrap>>(emptyList())
    private val eventFlow = MutableSharedFlow<StrapEvent>(extraBufferCapacity = 32)

    override val state: StateFlow<StrapState> = stateFlow
    override val scanning: StateFlow<Boolean> = scanningFlow
    override val scanResults: StateFlow<List<FoundStrap>> = resultsFlow
    override val events: Flow<StrapEvent> = eventFlow

    private val wire = ProtocolWire()
    private val seq = Seq()
    private val drain = HistoryDrain(ports.samples)
    private val bringUp = SessionBringUp(transport, queue, wire, seq, sleep = sleep)
    private val user = Mutex()

    /** True once a strap address is stored and this process should keep the link up. */
    private var keep = false
    private var opening = false
    private var foregroundHeart = false
    private var workoutHeart = false
    private var heartOn = false
    private var samplesPulled = 0L
    private var drainAttempt = 0
    private var lastDrainStart: Instant? = null
    private var lastRx: Instant = time.now()
    private var heartListener: (Int?) -> Unit = {}

    var onSessionReady: suspend () -> Unit = {}
    var onDoubleTap: suspend () -> Unit = {}
    var onAlarmFired: suspend () -> Unit = {}

    private var reconnectJob: Job? = null
    private var periodicJob: Job? = null
    private var livenessJob: Job? = null
    private var idleJob: Job? = null
    private var stallJob: Job? = null
    private var retryJob: Job? = null

    init {
        scope.launch {
            transport.scanHits.collect { hit ->
                resultsFlow.value = (resultsFlow.value.filter { it.address != hit.address } + hit)
                    .sortedByDescending { it.rssiDbm }
            }
        }
        scope.launch {
            transport.connections.collect { event ->
                when (event) {
                    ConnectionEvent.AdapterOn -> onBluetoothOn()
                    ConnectionEvent.AdapterOff -> onBluetoothOff()
                    is ConnectionEvent.Down -> onLinkDown()
                }
            }
        }
        scope.launch {
            transport.notifications.collect { notice ->
                try {
                    lastRx = time.now()
                    for (inbound in wire.feed(notice.role, notice.bytes)) onInbound(inbound)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // One bad packet, or a sink that threw, must not end the session.
                }
            }
        }
    }

    /** Loads the stored strap and connects when one is paired. Call once, after alarms are loaded. */
    suspend fun start() = restore()

    override suspend fun startScan(): Outcome<Unit> {
        if (!transport.bluetoothEnabled()) return strap(StrapError.BluetoothOff)
        if (!transport.hasScanPermission()) return strap(StrapError.PermissionMissing)
        resultsFlow.value = emptyList()
        transport.startScan()
        scanningFlow.value = true
        return Outcome.Ok(Unit)
    }

    override suspend fun stopScan(): Outcome<Unit> {
        transport.stopScan()
        scanningFlow.value = false
        return Outcome.Ok(Unit)
    }

    override suspend fun pair(strap: FoundStrap): Outcome<Unit> {
        if (!user.tryLock()) return busy()
        try {
            if (!transport.bluetoothEnabled()) return strap(StrapError.BluetoothOff)
            if (!transport.hasConnectPermission()) return strap(StrapError.PermissionMissing)
            transport.stopScan()
            scanningFlow.value = false
            publish { it.copy(link = LinkState.CONNECTING, name = strap.name, error = null) }
            val outcome = open(strap.address, autoConnect = false)
            if (outcome is Outcome.Err) {
                publish {
                    it.copy(
                        link = LinkState.DISCONNECTED,
                        error = (outcome.error as? CoreError.Strap)?.error ?: StrapError.LinkLost,
                    )
                }
                transport.disconnect()
                return outcome
            }
            ports.prefs.setPairedAddress(strap.address)
            ports.prefs.setStrapName(strap.name)
            keep = true
            host.onPaired(strap.address, askToAssociate = true)
            sessionReady()
            return Outcome.Ok(Unit)
        } finally {
            user.unlock()
        }
    }

    override suspend fun forget(): Outcome<Unit> {
        if (!user.tryLock()) return busy()
        try {
            keep = false
            reconnectJob?.cancel()
            periodicJob?.cancel()
            livenessJob?.cancel()
            cancelDrainJobs()
            drain.onDisconnect()
            transport.disconnect()
            ports.prefs.setPairedAddress(null)
            ports.prefs.setStrapName(null)
            foregroundHeart = false
            workoutHeart = false
            heartOn = false
            host.onForgotten()
            publish { StrapState() }
            return Outcome.Ok(Unit)
        } finally {
            user.unlock()
        }
    }

    override suspend fun syncNow(): Outcome<Unit> {
        if (ports.prefs.pairedAddress().isNullOrBlank()) return notPaired()
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        if (drain.open) return Outcome.Ok(Unit)
        requestDrain(DrainTrigger.MANUAL)
        return Outcome.Ok(Unit)
    }

    override suspend fun setLiveHeartRate(enabled: Boolean): Outcome<Unit> {
        if (ports.prefs.pairedAddress().isNullOrBlank()) return notPaired()
        foregroundHeart = enabled
        reconcileHeart()
        return Outcome.Ok(Unit)
    }

    override suspend fun buzz(haptic: Haptic): Outcome<Unit> {
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        val frame = when (haptic) {
            Haptic.TEST -> wire.testBuzz(seq.live())
            Haptic.FIND -> wire.findBuzz(seq.live())
        }
        return write(frame)
    }

    override suspend fun setBroadcast(enabled: Boolean): Outcome<Unit> {
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        return write(wire.broadcast(seq.live(), enabled))
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        if (name.isEmpty() || name.length > 20 || name.any { it.code !in 0x20..0x7E }) {
            return Outcome.Err(CoreError.Invalid("Name must be 1 to 20 ASCII characters"))
        }
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        val wrote = write(wire.rename(seq.live(), name))
        if (wrote is Outcome.Ok) {
            ports.prefs.setStrapName(name)
            publish { it.copy(name = name) }
        }
        return wrote
    }

    override suspend fun arm(at: Instant): Outcome<Unit> {
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        return write(wire.setAlarm(seq.live(), at.epochSecond))
    }

    override suspend fun disarm(): Outcome<Unit> {
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        return write(wire.disableAlarm(seq.live()))
    }

    override suspend fun buzz(): Outcome<Unit> = buzz(Haptic.FIND)

    override suspend fun stop(): Outcome<Unit> {
        if (stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        return write(wire.stopHaptics(seq.live()))
    }

    override fun connected(): Boolean = stateFlow.value.link == LinkState.CONNECTED

    override suspend fun setWorkout(on: Boolean): Outcome<Unit> {
        if (on && ports.prefs.pairedAddress().isNullOrBlank()) return notPaired()
        if (on && stateFlow.value.link != LinkState.CONNECTED) return strap(StrapError.LinkLost)
        workoutHeart = on
        reconcileHeart()
        return Outcome.Ok(Unit)
    }

    override fun listen(listener: (Int?) -> Unit) {
        heartListener = listener
    }

    private suspend fun restore() {
        val address = ports.prefs.pairedAddress()
        val name = ports.prefs.strapName()
        val last = ports.prefs.lastSync()
        if (address.isNullOrBlank()) {
            keep = false
            host.onForgotten()
            publish { StrapState() }
            return
        }
        keep = true
        host.onPaired(address, askToAssociate = false)
        publish { it.copy(link = LinkState.DISCONNECTED, name = name, lastSync = last) }
        kickReconnect()
    }

    private fun onBluetoothOn() {
        if (!keep || stateFlow.value.link == LinkState.UNPAIRED) return
        if (stateFlow.value.link == LinkState.CONNECTED || stateFlow.value.link == LinkState.CONNECTING) return
        kickReconnect()
    }

    private fun onBluetoothOff() {
        reconnectJob?.cancel()
        periodicJob?.cancel()
        livenessJob?.cancel()
        cancelDrainJobs()
        heartOn = false
        publish {
            it.copy(
                link = if (it.link == LinkState.UNPAIRED) LinkState.UNPAIRED else LinkState.DISCONNECTED,
                liveHeartRateBpm = null,
                sync = it.sync.copy(draining = false),
                error = StrapError.BluetoothOff,
            )
        }
    }

    private fun onLinkDown() {
        if (!keep || opening) return
        if (stateFlow.value.link == LinkState.UNPAIRED) return
        heartOn = false
        cancelDrainJobs()
        drain.onDisconnect()
        periodicJob?.cancel()
        livenessJob?.cancel()
        publish {
            it.copy(
                link = LinkState.RECONNECTING,
                liveHeartRateBpm = null,
                sync = it.sync.copy(draining = false),
                error = StrapError.LinkLost,
            )
        }
        kickReconnect()
    }

    private fun kickReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && keep) {
                val address = ports.prefs.pairedAddress() ?: return@launch
                if (!transport.bluetoothEnabled()) {
                    publish { it.copy(link = LinkState.DISCONNECTED, error = StrapError.BluetoothOff) }
                    return@launch
                }
                val started = time.now()
                publish { it.copy(link = LinkState.RECONNECTING, error = null) }
                val watch = launch {
                    sleep(ReconnectPolicy.STALE_ATTEMPT.toMillis())
                    transport.disconnect()
                }
                val outcome = open(address, autoConnect = true)
                watch.cancel()
                if (outcome is Outcome.Ok) {
                    sessionReady()
                    return@launch
                }
                attempt++
                if (!transport.bluetoothEnabled()) {
                    publish { it.copy(link = LinkState.DISCONNECTED, error = StrapError.BluetoothOff) }
                    return@launch
                }
                val running = Duration.between(started, time.now())
                val action = ReconnectPolicy.decide(
                    paired = true,
                    bluetoothOn = transport.bluetoothEnabled(),
                    connected = false,
                    attemptRunning = running,
                    silentFor = null,
                )
                if (action == ReconnectAction.WAIT_FOR_RADIO) return@launch
                sleep(app.circlet.strap.link.Backoff.delay(attempt).toMillis())
            }
        }
    }

    private suspend fun open(address: String, autoConnect: Boolean): Outcome<Unit> {
        opening = true
        try {
            return bringUp.run(
                address = address,
                now = time.now(),
                autoConnect = autoConnect,
                trace = mutableListOf(),
                beforeHistorical = { armDrainState() },
            )
        } finally {
            opening = false
        }
    }

    private suspend fun sessionReady() {
        lastRx = time.now()
        publish { it.copy(link = LinkState.CONNECTED, error = null) }
        armPeriodic()
        armLiveness()
        // The strap may still be streaming from the previous session. Force the command
        // so live heart rate is off unless something in this session asked for it.
        heartOn = !(foregroundHeart || workoutHeart)
        reconcileHeart()
        onSessionReady()
    }

    private fun armPeriodic() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                sleep(Duration.ofMinutes(15).toMillis())
                requestDrain(DrainTrigger.PERIODIC)
            }
        }
    }

    private fun armLiveness() {
        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive && stateFlow.value.link == LinkState.CONNECTED) {
                sleep(30_000)
                val silent = Duration.between(lastRx, time.now())
                val action = ReconnectPolicy.decide(
                    paired = true,
                    bluetoothOn = transport.bluetoothEnabled(),
                    connected = true,
                    attemptRunning = null,
                    silentFor = silent,
                )
                if (action == ReconnectAction.RENEW) {
                    transport.disconnect()
                    return@launch
                }
            }
        }
    }

    private suspend fun requestDrain(trigger: DrainTrigger) {
        if (stateFlow.value.link != LinkState.CONNECTED || drain.open) return
        if (!DrainSchedule.due(trigger, time.now(), lastDrainStart)) return
        lastDrainStart = time.now()
        drainAttempt = 0
        armDrainState()
        if (write(wire.historical(seq.live())) !is Outcome.Ok) retryDrain()
    }

    private fun armDrainState() {
        samplesPulled = 0
        drain.begin()
        lastDrainStart = time.now()
        publish { it.copy(sync = SyncProgress(samplesPulled, draining = true), error = null) }
        armStall()
        armIdle()
    }

    private fun armStall() {
        stallJob?.cancel()
        stallJob = scope.launch {
            sleep(10_000)
            if (drain.onStall() is EndResult.Retry) retryDrain()
        }
    }

    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            sleep(60_000)
            if (drain.onIdle() is EndResult.Retry) retryDrain()
        }
    }

    private fun retryDrain() {
        cancelDrainJobs()
        drain.abortOpen()
        drainAttempt++
        if (drainAttempt > 3) {
            drain.abandon()
            publish { it.copy(sync = stateFlow.value.sync.copy(draining = false), error = StrapError.TimedOut) }
            return
        }
        val wait = Backoff.delay(drainAttempt).toMillis()
        retryJob = scope.launch {
            sleep(wait)
            if (!keep || stateFlow.value.link != LinkState.CONNECTED || !drain.open) return@launch
            write(wire.abort(seq.live()))
            sleep(300)
            if (!drain.open) return@launch
            armStall()
            armIdle()
            publish { it.copy(sync = SyncProgress(samplesPulled, draining = true)) }
            write(wire.historical(seq.live()))
        }
    }

    private fun cancelDrainJobs() {
        idleJob?.cancel()
        stallJob?.cancel()
        retryJob?.cancel()
    }

    private suspend fun onInbound(inbound: Inbound) {
        when (inbound) {
            is Inbound.HelloInfo -> publish {
                it.copy(
                    serial = inbound.serial ?: it.serial,
                    firmware = inbound.firmware ?: it.firmware,
                    batteryPercent = inbound.battery ?: it.batteryPercent,
                )
            }
            is Inbound.Battery -> applyBattery(inbound.percent, time.now())
            is Inbound.Name -> {
                ports.prefs.setStrapName(inbound.name)
                publish { it.copy(name = inbound.name) }
            }
            is Inbound.Sampled -> {
                drain.onRecord(inbound.sample, inbound.raw)
                stallJob?.cancel()
                armIdle()
            }
            is Inbound.Heart -> onHeart(inbound.bpm)
            is Inbound.Meta -> onMeta(inbound)
            is Inbound.Signal -> onSignal(inbound)
            Inbound.Ignore -> Unit
        }
    }

    private suspend fun onMeta(meta: Inbound.Meta) {
        stallJob?.cancel()
        when (meta.kind) {
            MetaKind.START -> {
                drain.onStart()
                armIdle()
            }
            MetaKind.END -> {
                val token = meta.token ?: return
                when (val result = drain.onEnd(token)) {
                    is EndResult.Ack -> {
                        samplesPulled += result.committed
                        val now = time.now()
                        ports.prefs.setLastSync(now)
                        publish { it.copy(sync = SyncProgress(samplesPulled, draining = true), lastSync = now) }
                        if (write(wire.historyAck(seq.ack(), result.token)) is Outcome.Ok) drain.ackLanded()
                        armIdle()
                    }
                    is EndResult.AckAgain -> {
                        if (write(wire.historyAck(seq.ack(), result.token)) is Outcome.Ok) drain.ackLanded()
                    }
                    EndResult.Duplicate, EndResult.Ignored, EndResult.Completed, EndResult.Retry -> Unit
                }
            }
            MetaKind.COMPLETE -> {
                drain.onComplete()
                cancelDrainJobs()
                val now = time.now()
                ports.prefs.setLastSync(now)
                publish { it.copy(sync = SyncProgress(samplesPulled, draining = false), lastSync = now) }
            }
        }
    }

    private suspend fun onSignal(signal: Inbound.Signal) {
        if (!fresh(signal.epochSecond)) return
        val at = if (signal.epochSecond > 0) Instant.ofEpochSecond(signal.epochSecond) else time.now()
        when (signal.signal) {
            StrapSignal.BATTERY -> {
                signal.battery?.let { applyBattery(it, at) }
                signal.charging?.let { applyCharging(it, at) }
            }
            StrapSignal.CHARGING_ON -> applyCharging(true, at)
            StrapSignal.CHARGING_OFF -> applyCharging(false, at)
            StrapSignal.WRIST_ON -> applyWrist(true, at)
            StrapSignal.WRIST_OFF -> applyWrist(false, at)
            StrapSignal.DOUBLE_TAP -> {
                emit(StrapEvent.DoubleTap(at))
                onDoubleTap()
            }
            StrapSignal.PROMPT -> {
                emit(StrapEvent.HighFrequencySyncPrompt(at))
                requestDrain(DrainTrigger.PROMPT)
            }
            StrapSignal.ALARM_FIRED -> onAlarmFired()
            StrapSignal.CLOCK_LOST -> write(wire.setClock(seq.live(), time.now().epochSecond))
        }
    }

    private suspend fun applyBattery(percent: Double, at: Instant) {
        publish { it.copy(batteryPercent = percent) }
        emit(StrapEvent.Battery(at, percent))
    }

    private suspend fun applyCharging(charging: Boolean, at: Instant) {
        publish { it.copy(charging = charging) }
        emit(StrapEvent.Charging(at, charging))
    }

    private suspend fun applyWrist(on: Boolean, at: Instant) {
        publish { it.copy(onWrist = on, liveHeartRateBpm = if (on) it.liveHeartRateBpm else null) }
        emit(StrapEvent.Wrist(at, on))
        if (!on) heartListener(null)
    }

    private fun onHeart(bpm: Int) {
        if (!heartOn) return
        if (bpm == 0) {
            publish { it.copy(onWrist = false, liveHeartRateBpm = null) }
            heartListener(null)
            return
        }
        val measured = bpm.takeIf { it in 25..230 }
        publish { it.copy(liveHeartRateBpm = measured, onWrist = true) }
        heartListener(measured)
    }

    private suspend fun reconcileHeart() {
        val want = foregroundHeart || workoutHeart
        if (stateFlow.value.link != LinkState.CONNECTED) {
            if (!want) publish { it.copy(liveHeartRateBpm = null) }
            return
        }
        if (want == heartOn) return
        if (write(wire.realtime(seq.live(), want)) is Outcome.Ok) {
            heartOn = want
            if (!want) {
                publish { it.copy(liveHeartRateBpm = null) }
                heartListener(null)
            }
        }
    }

    private suspend fun write(frame: ByteArray): Outcome<Unit> = when (queue.submit { transport.write(frame) }) {
        is QueueOutcome.Done -> Outcome.Ok(Unit)
        QueueOutcome.TimedOut -> strap(StrapError.TimedOut)
        is QueueOutcome.Failed -> strap(StrapError.LinkLost)
    }

    private suspend fun emit(event: StrapEvent) {
        eventFlow.tryEmit(event)
        ports.events.record(event)
    }

    private fun fresh(epochSecond: Long): Boolean {
        if (epochSecond <= 0L) return true
        return abs(time.now().epochSecond - epochSecond) <= 1800
    }

    private fun publish(block: (StrapState) -> StrapState) {
        val next = block(stateFlow.value)
        stateFlow.value = next
        host.notify(next)
    }

    private fun strap(error: StrapError): Outcome<Unit> = Outcome.Err(CoreError.Strap(error))
    private fun notPaired(): Outcome<Unit> = Outcome.Err(CoreError.NotPaired)
    private fun busy(): Outcome<Unit> = Outcome.Err(CoreError.Busy)
}
