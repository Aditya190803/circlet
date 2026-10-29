package app.circlet.strap.link

import app.circlet.core.CoreError
import app.circlet.core.Outcome
import app.circlet.core.StrapError
import app.circlet.core.StrapGeneration
import app.circlet.strap.wire.ProtocolWire
import java.time.Duration
import java.time.Instant

// Ported from OpenStrap edge/lib/ble/ble_engine.dart — the gen4 `_doConnect` order
// (bond, MTU 247, discover, subscribe command responses then events then data)
// and `_bootstrapSetClock` before `initPackets` in protocol/lib/src/commands.dart.
// GET_BATTERY follows hello because opcode 0x1A is the battery reading the UI shows.
/**
 * Opens the discovered generation's session. Gen 4 always sets its clock;
 * Gen 5 reads HELLO first and only corrects a drifting clock.
 */
internal class SessionBringUp(
    private val transport: GattTransport,
    private val queue: GattOperationQueue,
    private val wire: ProtocolWire,
    private val seq: Seq,
    private val gap: Duration = SESSION_GAP,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    suspend fun run(
        address: String,
        now: Instant,
        autoConnect: Boolean,
        trace: MutableList<String>,
        currentTime: () -> Instant = { now },
        afterHistorical: suspend () -> Unit = {},
        beforeHistorical: suspend () -> Unit = {},
    ): Outcome<Unit> {
        seq.reset()
        wire.reset()
        trace += "connect"
        connectFailure(transport.connect(address, autoConnect))?.let { return it }

        trace += "bond"
        if (!transport.bonded(address)) {
            when (transport.bond(address)) {
                BondStatus.FAILED -> return strap(StrapError.BondFailed)
                BondStatus.HELD -> return strap(StrapError.Rejected("The strap is connected to another app"))
                BondStatus.BONDED -> Unit
            }
        }

        trace += "mtu"
        // A phone that refuses 247 still carries commands and acks at the default MTU.
        queue.submit { transport.requestMtu(247) }

        trace += "discover"
        when (val found = queue.submit { transport.discover() }) {
            is QueueOutcome.Done -> if (!found.value) {
                return strap(StrapError.Rejected("This device is not a WHOOP 4.0 or 5.0 / MG"))
            }
            QueueOutcome.TimedOut -> return strap(StrapError.TimedOut)
            is QueueOutcome.Failed -> return strap(StrapError.LinkLost)
        }

        wire.generation = transport.strapGeneration
        wire.reset()
        if (wire.generation == StrapGeneration.GEN5) sleep(600)

        for ((step, role) in listOf(
            "subscribe-commands" to NotifyRole.COMMANDS,
            "subscribe-events" to NotifyRole.EVENTS,
            "subscribe-data" to NotifyRole.DATA,
        )) {
            trace += step
            when (queue.submit { transport.subscribe(role) }) {
                is QueueOutcome.Done -> Unit
                QueueOutcome.TimedOut -> return strap(StrapError.TimedOut)
                is QueueOutcome.Failed -> return strap(StrapError.LinkLost)
            }
        }

        if (wire.generation == StrapGeneration.GEN5) {
            sleep(500)
            return gen5(currentTime, trace, beforeHistorical, afterHistorical)
        }

        val frames = listOf(
            "set-clock" to wire.setClock(seq.live(), now.epochSecond),
            "get-hello" to wire.hello(0),
            "get-battery" to wire.battery(seq.live()),
            "get-advertising-name" to wire.advertisingName(1),
            "get-data-range" to wire.dataRange(2),
            "get-alarm" to wire.alarm(3),
        )
        for ((name, frame) in frames) {
            trace += name
            if (!write(frame)) return strap(StrapError.LinkLost)
            if (!gap.isZero) sleep(gap.toMillis())
        }
        beforeHistorical()
        trace += "send-historical"
        if (!write(wire.historical(4))) return strap(StrapError.LinkLost)
        afterHistorical()
        return Outcome.Ok(Unit)
    }

    // Ported from OpenStrap edge/lib/ble/ble_engine.dart — _bootstrapAfterRegistration,
    // _gen5ClockContract and sendInit: HELLO owns the initial clock reading.
    private suspend fun gen5(
        now: () -> Instant,
        trace: MutableList<String>,
        beforeHistorical: suspend () -> Unit,
        afterHistorical: suspend () -> Unit,
    ): Outcome<Unit> {
        trace += "get-hello"
        val hello = wire.exchange(wire.hello(seq.live()), ::write)?.hello
            ?: return strap(StrapError.TimedOut)
        if (hello.locked == true) return strap(LOCKED_STRAP)
        if (!hello.identityValid) return strap(StrapError.Rejected("Invalid strap identity"))
        if (hello.revision != 1 || hello.clockMillis == null ||
            kotlin.math.abs(now().toEpochMilli() - hello.clockMillis) >= 2_000
        ) {
            trace += "set-clock"
            val reply = wire.exchange(wire.setClockAt(seq.live(), now()), ::write)
            if (reply?.result != 1) return strap(StrapError.TimedOut)
        }
        trace += "get-advertising-name"
        wire.exchange(wire.advertisingName(seq.live()), ::write)
        trace += "get-data-range"
        if (!write(wire.dataRange(seq.live()))) return strap(StrapError.LinkLost)
        sleep(120)
        beforeHistorical()
        trace += "send-historical"
        if (!write(wire.historical(4))) return strap(StrapError.LinkLost)
        afterHistorical()
        return Outcome.Ok(Unit)
    }

    private suspend fun write(frame: ByteArray): Boolean =
        queue.submit { transport.write(frame) } is QueueOutcome.Done
}

private fun strap(error: StrapError): Outcome<Unit> = Outcome.Err(CoreError.Strap(error))

internal val LOCKED_STRAP = StrapError.AccountLocked

/** Live commands use 0xA0 and up. Batch acks use 5 and up, after the init sequences 0..4. */
internal class Seq {
    private var liveSeq = 0xA0
    private var ackSeq = 5

    fun reset() {
        liveSeq = 0xA0
        ackSeq = 5
    }

    fun live(): Int {
        val value = liveSeq
        liveSeq = (liveSeq + 1) and 0xFF
        if (liveSeq < 0xA0) liveSeq = 0xA0
        return value
    }

    fun ack(): Int {
        val value = ackSeq
        ackSeq = (ackSeq + 1) and 0xFF
        if (ackSeq < 5) ackSeq = 5
        return value
    }
}
