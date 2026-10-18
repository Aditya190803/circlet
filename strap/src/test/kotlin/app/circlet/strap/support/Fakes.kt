package app.circlet.strap.support

import app.circlet.core.Activity
import app.circlet.core.DoubleTapAction
import app.circlet.core.DoubleTapPolicy
import app.circlet.core.FoundStrap
import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.core.SampleSink
import app.circlet.core.SleepStageEstimate
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapEvent
import app.circlet.core.StrapEventSink
import app.circlet.core.StrapPorts
import app.circlet.core.StrapPrefs
import app.circlet.core.StrapState
import app.circlet.core.WorkoutStore
import app.circlet.protocol.FrameReassembler
import app.circlet.protocol.Frames
import app.circlet.strap.StrapHost
import app.circlet.strap.link.BondStatus
import app.circlet.strap.link.ConnectStatus
import app.circlet.strap.link.ConnectionEvent
import app.circlet.strap.link.GattNotice
import app.circlet.strap.link.GattTransport
import app.circlet.strap.link.NotifyRole
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal class MemoryPrefs : StrapPrefs {
    var address: String? = null
    var name: String? = null
    var synced: Instant? = null
    var stored: List<StrapAlarm> = emptyList()

    override suspend fun pairedAddress(): String? = address
    override suspend fun setPairedAddress(address: String?) { this.address = address }
    override suspend fun strapName(): String? = name
    override suspend fun setStrapName(name: String?) { this.name = name }
    override suspend fun lastSync(): Instant? = synced
    override suspend fun setLastSync(at: Instant?) { synced = at }
    override suspend fun alarms(): List<StrapAlarm> = stored
    override suspend fun setAlarms(alarms: List<StrapAlarm>) { stored = alarms }
}

internal class RecordingSink : SampleSink {
    val batches = mutableListOf<Pair<List<Sample>, List<RawRecord>>>()

    /** When set, [commit] suspends until this is completed. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun commit(samples: List<Sample>, rawRecords: List<RawRecord>) {
        gate?.await()
        batches += samples.toList() to rawRecords.map { RawRecord(it.epochSecond, it.bytes.copyOf()) }
    }
}

internal class FakeHost : StrapHost {
    val paired = mutableListOf<Pair<String, Boolean>>()
    var forgotten = 0

    override fun onPaired(address: String, askToAssociate: Boolean) {
        paired += address to askToAssociate
    }

    override fun onForgotten() {
        forgotten++
    }

    override fun notify(state: StrapState) = Unit
}

internal class FakeTransport : GattTransport {
    override var connectionGeneration: Long = 0
        private set
    private val noteFlow = MutableSharedFlow<GattNotice>(extraBufferCapacity = 64)
    private val eventFlow = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    private val hitFlow = MutableSharedFlow<FoundStrap>(extraBufferCapacity = 16)

    override val notifications: Flow<GattNotice> = noteFlow
    override val connections: Flow<ConnectionEvent> = eventFlow
    override val scanHits: Flow<FoundStrap> = hitFlow

    val writes = mutableListOf<ByteArray>()
    val connects = mutableListOf<Boolean>()
    var bondCalls = 0
    var bluetooth = true
    var scanPermission = true
    var connectPermission = true
    var alreadyBonded = true
    var bondStatus = BondStatus.BONDED
    var connectStatus: ConnectStatus = ConnectStatus.Up
    var autoConnectGate: CompletableDeferred<Unit>? = null
    var discoverValue = true
    var discoverDelayMs = 0L
    var mtuFails = false
    var failOpcode = -1
    var failuresRemaining: Int? = null
    var delayedOpcode = -1
    var writeDelayMs = 0L

    fun emit(role: NotifyRole, frame: ByteArray) {
        check(noteFlow.tryEmit(GattNotice(role, frame, connectionGeneration)))
    }

    fun down(status: Int = 8) {
        check(eventFlow.tryEmit(ConnectionEvent.Down(status)))
    }

    fun hit(strap: FoundStrap) {
        check(hitFlow.tryEmit(strap))
    }

    override fun bluetoothEnabled(): Boolean = bluetooth
    override fun hasScanPermission(): Boolean = scanPermission
    override fun hasConnectPermission(): Boolean = connectPermission

    override suspend fun startScan() = Unit
    override suspend fun stopScan() = Unit

    override suspend fun connect(address: String, autoConnect: Boolean): ConnectStatus {
        connectionGeneration++
        connects += autoConnect
        if (autoConnect) autoConnectGate?.await()
        return connectStatus
    }

    override suspend fun disconnect() = Unit
    override suspend fun requestMtu(mtu: Int) {
        if (mtuFails) error("mtu refused")
    }

    override suspend fun discover(): Boolean {
        if (discoverDelayMs > 0) delay(discoverDelayMs)
        return discoverValue
    }

    override suspend fun subscribe(role: NotifyRole) = Unit
    override suspend fun write(frame: ByteArray) {
        if (opcodeOf(frame) == delayedOpcode) delay(writeDelayMs)
        if (failOpcode >= 0 && opcodeOf(frame) == failOpcode && failuresRemaining != 0) {
            failuresRemaining = failuresRemaining?.minus(1)
            error("write refused")
        }
        writes += frame.copyOf()
    }

    override fun bonded(address: String): Boolean = alreadyBonded
    override suspend fun bond(address: String): BondStatus {
        bondCalls++
        return bondStatus
    }
}

internal class UnusedWorkouts : WorkoutStore {
    override suspend fun insert(activity: Activity) = error("unused")
    override suspend fun update(activity: Activity) = error("unused")
    override suspend fun delete(id: String) = error("unused")
    override suspend fun get(id: String): Activity? = error("unused")
}

internal fun testPorts(sink: SampleSink, prefs: StrapPrefs): StrapPorts = StrapPorts(
    samples = sink,
    events = object : StrapEventSink {
        override suspend fun record(event: StrapEvent) = Unit
    },
    prefs = prefs,
    workouts = UnusedWorkouts(),
    sleepStage = SleepStageEstimate.UNKNOWN,
    doubleTap = DoubleTapPolicy { DoubleTapAction.MARK_MOMENT },
)

internal fun innerOf(frame: ByteArray): ByteArray = FrameReassembler().feed(frame).single()

internal fun opcodeOf(frame: ByteArray): Int = innerOf(frame)[2].toInt() and 0xFF

internal fun opcodes(frames: List<ByteArray>): List<Int> = frames.map { opcodeOf(it) }

internal fun u32le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

/** Metadata inner. Kind 1 start, 2 end, 3 complete. An end token sits at inner[13:21]. */
internal fun metaFrame(kind: Int, token: ByteArray = ByteArray(8)): ByteArray {
    val inner = ByteArray(21)
    inner[0] = 0x31
    inner[2] = kind.toByte()
    if (kind == 2) token.copyInto(inner, 13)
    return Frames.wrap(inner)
}

/** A historical record too short to decode. The raw bytes are still stored. */
internal fun recordFrame(): ByteArray = Frames.wrap(byteArrayOf(0x2F, 0x01))
