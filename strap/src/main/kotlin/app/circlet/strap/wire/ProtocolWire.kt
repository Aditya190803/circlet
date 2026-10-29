// Ported from OpenStrap protocol/lib/src/band.dart, gen5_records.dart and control.dart — profile dispatch, Gen5V18Decoder, Gen5HelloInfo.
package app.circlet.strap.wire

import app.circlet.core.GravityVector
import app.circlet.core.OpticalChannels
import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.protocol.BandCommands
import app.circlet.protocol.BandProfile
import app.circlet.protocol.gen5Hello
import app.circlet.protocol.decodeGen5Summary
import app.circlet.protocol.clockEpoch
import app.circlet.core.StrapGeneration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import app.circlet.protocol.Commands
import app.circlet.protocol.Decoder
import app.circlet.protocol.FirmwareAwareR24Decoder
import app.circlet.protocol.FrameReassembler
import app.circlet.protocol.MetadataKind
import app.circlet.protocol.Packet
import app.circlet.protocol.R24
import app.circlet.protocol.StrapEventId
import app.circlet.protocol.Uuids
import app.circlet.protocol.advertisingName
import app.circlet.protocol.batteryPercent
import app.circlet.protocol.dataRange as readDataRange
import app.circlet.protocol.hello
import app.circlet.strap.link.NotifyRole

// The only file that talks to :protocol. The opcodes below are the harmful set from
// AGENTS.md rule 3. LOCK_DEVICE has no published opcode in the OpenStrap references,
// so there is no builder for it. None of these opcodes has a command method.
internal object Harmful {
    val OPCODES: Set<Int> = setOf(
        0x19, // FORCE_TRIM
        0x99, // TOGGLE_PERSISTENT_R20
        0x9A, // TOGGLE_PERSISTENT_R21
        0x78, // SET_FF_VALUE (Gen 5 deep-buffer configuration)
        0x77, // SET_DEVICE_CONFIG_VALUE
        0x6B, // ENABLE_OPTICAL_DATA (persistent history on Gen 5)
        0x1D, // REBOOT_STRAP
        0x20, // POWER_CYCLE_STRAP
        0x2D, // ENTER_BLE_DFU
        0x24, // START_FIRMWARE_LOAD
        0x25, // LOAD_FIRMWARE_DATA
        0x26, // PROCESS_FIRMWARE_IMAGE
        0x53, // VERIFY_FIRMWARE_IMAGE
        0x8E, // START_FIRMWARE_LOAD_NEW
        0x8F, // LOAD_FIRMWARE_DATA_NEW
        0x90, // PROCESS_FIRMWARE_IMAGE_NEW
        0x9B, 0x9C, 0x9D, 0x9E, 0x9F, // Gen 5 certificate/unlock flow
    )

    fun ensure(opcode: Int) {
        check(opcode !in OPCODES) { "refusing harmful opcode 0x${opcode.toString(16)}" }
    }
}

internal enum class MetaKind { START, END, COMPLETE }

internal enum class StrapSignal {
    BATTERY,
    CHARGING_ON,
    CHARGING_OFF,
    WRIST_ON,
    WRIST_OFF,
    DOUBLE_TAP,
    PROMPT,
    ALARM_FIRED,
    CLOCK_LOST,
}

internal sealed interface Inbound {
    data class HelloInfo(val serial: String?, val firmware: String?, val battery: Double?, val charging: Boolean? = null, val onWrist: Boolean? = null,
        val clockMillis: Long? = null, val revision: Int? = null, val identityValid: Boolean = true,
        val locked: Boolean? = null,
    ) : Inbound
    data class Battery(val percent: Double) : Inbound
    data class Name(val name: String) : Inbound
    data class Sampled(val sample: Sample?, val raw: RawRecord) : Inbound
    data class Heart(val bpm: Int) : Inbound
    data class HistoryReply(val opcode: Int, val sequence: Int, val result: Int) : Inbound
    data class Meta(val kind: MetaKind, val token: ByteArray?, val expectedPacketCount: Long? = null) : Inbound
    data object Traffic : Inbound
    data class Signal(val signal: StrapSignal, val epochSecond: Long, val battery: Double?, val charging: Boolean?) : Inbound

    /** Seconds of history the strap says it is holding. Null when the reply has no clock range. */
    data class Range(val storedSeconds: Long?) : Inbound
    data object Ignore : Inbound
}

/**
 * Binds the link to `:protocol`.
 * Session bytes are [Commands]. Incoming chunks are [FrameReassembler] then [Decoder].
 */
/** GATT ids from [Uuids]. The rest of the module does not import `:protocol`. */
internal object StrapUuids {
    val SERVICES = BandProfile.entries.map { it.service }
    fun profile(generation: StrapGeneration) = if (generation == StrapGeneration.GEN5) BandProfile.Gen5 else BandProfile.Gen4
    fun generation(service: java.util.UUID): StrapGeneration? = BandProfile.fromService(service)?.let {
        if (it == BandProfile.Gen5) StrapGeneration.GEN5 else StrapGeneration.GEN4
    }
    val SERVICE = Uuids.SERVICE
    val CMD_TO = Uuids.CMD_TO_STRAP
    val CMD_FROM = Uuids.CMD_FROM_STRAP
    val EVENTS = Uuids.EVENTS_FROM_STRAP
    val DATA = Uuids.DATA_FROM_STRAP
    val CCCD = Uuids.CCCD
}

internal data class WireReply(val result: Int, val hello: Inbound.HelloInfo?, val clock: Long?)

internal class ProtocolWire(var generation: StrapGeneration = StrapGeneration.GEN4) {
    val profile get() = StrapUuids.profile(generation)
    private val builders get() = BandCommands(profile)
    private val pending = mutableMapOf<Pair<Int, Int>, CompletableDeferred<WireReply>>()

    suspend fun exchange(frame: ByteArray, write: suspend (ByteArray) -> Boolean): WireReply? {
        val inner = FrameReassembler(profile).feed(frame).single()
        val key = (inner[2].toInt() and 255) to (inner[1].toInt() and 255)
        val reply = CompletableDeferred<WireReply>()
        check(pending.put(key, reply) == null)
        try {
            if (!write(frame)) return null
            return withTimeoutOrNull(5_000) { reply.await() }
        } finally {
            pending.remove(key)
        }
    }

    fun cancelPending() {
        pending.values.forEach { it.complete(WireReply(0, null, null)) }
        pending.clear()
    }

    private var commands = FrameReassembler(profile)
    private var events = FrameReassembler(profile)
    private var data = FrameReassembler(profile)

    /** One decoder per connection. [reset] clears the layout it has learned. */
    private val records = FirmwareAwareR24Decoder()

    fun reset() {
        cancelPending()
        dataFailures = 0
        commands = FrameReassembler(profile)
        events = FrameReassembler(profile)
        data = FrameReassembler(profile)
        records.reset()
    }

    fun feed(role: NotifyRole, chunk: ByteArray): List<Inbound> {
        val asm = when (role) {
            NotifyRole.COMMANDS -> commands
            NotifyRole.EVENTS -> events
            NotifyRole.DATA -> data
        }
        val resyncsBefore = asm.resyncs
        val inbound = asm.feed(chunk).flatMap { inner ->
            val packet = Decoder.decode(inner, profile)
            if (packet == null && role == NotifyRole.DATA) dataFailures++
            if (packet is Packet.Response) {
                val helloInfo = if (packet.opcode == 0x91 && packet.result == 1) gen5HelloInfo(packet.body) else null
                val clock = if (packet.result == 1) clockEpoch(packet.opcode, packet.body) else null
                if (packet.result != 2) pending[packet.opcode to packet.originSeq]?.complete(WireReply(packet.result, helloInfo, clock))
            }
            val type = inner.firstOrNull()?.toInt()?.and(255)
            val counted = type == 0x30 || type == 0x32 || type in 0x35..0x37
            if (counted && generation == StrapGeneration.GEN5) listOf(Inbound.Traffic, interpret(packet)) else listOf(interpret(packet))
        }
        if (role == NotifyRole.DATA) dataFailures += asm.resyncs - resyncsBefore
        return inbound
    }

    /**
     * Frames on the data characteristic that failed their CRC or could not be parsed.
     * A history drain compares this before and after a packet.
     */
    var dataFailures: Int = 0
        private set

    fun setClock(seq: Int, epochSeconds: Long): ByteArray = vetted(builders.setClock(seq, epochSeconds))

    fun setClockAt(seq: Int, at: java.time.Instant): ByteArray =
        vetted(builders.setClock(seq, at.epochSecond, (at.nano.toLong() * 32768 / 1_000_000_000).toInt()))

    fun getClock(seq: Int): ByteArray = vetted(builders.getClock(seq))

    fun historyFailure(seq: Int): ByteArray = vetted(builders.historyFailure(seq))

    fun hello(seq: Int): ByteArray = vetted(builders.getHello(seq))

    fun battery(seq: Int): ByteArray = vetted(builders.getBattery(seq))

    fun linkValid(seq: Int): ByteArray = vetted(builders.linkValid(seq))

    fun advertisingName(seq: Int): ByteArray = vetted(builders.getAdvertisingName(seq))

    fun dataRange(seq: Int): ByteArray = vetted(builders.getDataRange(seq))

    fun alarm(seq: Int): ByteArray = vetted(builders.getAlarm(seq))

    fun historical(seq: Int): ByteArray = vetted(builders.sendHistoricalData(seq))

    fun abort(seq: Int): ByteArray = vetted(builders.abortHistorical(seq))

    fun historyAck(seq: Int, token: ByteArray): ByteArray = vetted(builders.historyAck(seq, token))

    fun realtime(seq: Int, on: Boolean): ByteArray = vetted(builders.toggleRealtimeHr(seq, on))

    fun broadcast(seq: Int, on: Boolean): ByteArray = vetted(builders.toggleGenericHrProfile(seq, on))

    fun haptics(seq: Int, pattern: Int): ByteArray = vetted(builders.runHaptics(seq, pattern))

    fun testBuzz(seq: Int): ByteArray = haptics(seq, app.circlet.protocol.Haptics.SHORT_PULSE)

    fun findBuzz(seq: Int): ByteArray = runAlarm(seq)

    fun stopHaptics(seq: Int): ByteArray = vetted(builders.stopHaptics(seq))

    fun setAlarm(seq: Int, epochSeconds: Long): ByteArray = vetted(builders.setAlarm(seq, epochSeconds))

    fun runAlarm(seq: Int): ByteArray = vetted(builders.runAlarm(seq))

    fun disableAlarm(seq: Int): ByteArray = vetted(builders.disableAlarm(seq))

    fun rename(seq: Int, name: String): ByteArray = vetted(builders.setAdvertisingName(seq, name))

    fun vetted(frame: ByteArray): ByteArray {
        val inner = FrameReassembler(profile).feed(frame).firstOrNull()
            ?: error("refusing an unreadable frame")
        val opcode = if (inner.size > 2) inner[2].toInt() and 0xFF else error("refusing a short frame")
        Harmful.ensure(opcode)
        return frame
    }

    private fun interpret(packet: Packet?): Inbound = when (packet) {
        null -> Inbound.Ignore
        is Packet.Response -> response(packet)
        is Packet.Historical -> sampled(packet)
        is Packet.Realtime -> Inbound.Heart(packet.heartRate)
        is Packet.Event -> signal(packet)
        is Packet.Metadata -> Inbound.Meta(
            when (packet.kind) {
                MetadataKind.START -> MetaKind.START
                MetadataKind.END -> MetaKind.END
                MetadataKind.COMPLETE -> MetaKind.COMPLETE
            },
            packet.token,
            packet.expectedPacketCount,
        )
        is Packet.Other -> Inbound.Ignore
    }

    private fun response(packet: Packet.Response): Inbound = when (packet.opcode) {
        0x16, 0x17 -> Inbound.HistoryReply(packet.opcode, packet.originSeq, packet.result)
        0x91 -> if (packet.result == 1 && generation == StrapGeneration.GEN5) gen5HelloInfo(packet.body) ?: Inbound.Ignore else Inbound.Ignore
        0x23 -> {
            val info = hello(packet.body)
            Inbound.HelloInfo(info.serial, info.firmware, info.batteryPercent)
        }
        0x1A -> batteryPercent(packet.body)?.let { Inbound.Battery(it) } ?: Inbound.Ignore
        0x4C, 0x8D -> if (packet.result == 1) {
            advertisingName(packet.body)?.let { Inbound.Name(it) } ?: Inbound.Ignore
        } else {
            Inbound.Ignore
        }
        0x22 -> {
            val range = readDataRange(packet.body) ?: return Inbound.Ignore
            val oldest = range.oldestEpochSeconds
            val newest = range.newestEpochSeconds
            val stored = if (oldest != null && newest != null) newest - oldest else null
            Inbound.Range(stored)
        }
        else -> Inbound.Ignore
    }

    private fun gen5HelloInfo(body: ByteArray): Inbound.HelloInfo? {
        val info = gen5Hello(body) ?: return null
        return Inbound.HelloInfo(info.serial, info.firmware, info.batteryPercent, info.charging,
            info.onWrist, info.clockMillis, info.revision, info.identityValid, info.locked)
    }

    private fun signal(packet: Packet.Event): Inbound {
        val kind = when (packet.id) {
            StrapEventId.BATTERY_LEVEL -> StrapSignal.BATTERY
            StrapEventId.CHARGING_ON -> StrapSignal.CHARGING_ON
            StrapEventId.CHARGING_OFF -> StrapSignal.CHARGING_OFF
            StrapEventId.WRIST_ON -> StrapSignal.WRIST_ON
            StrapEventId.WRIST_OFF -> StrapSignal.WRIST_OFF
            StrapEventId.DOUBLE_TAP -> StrapSignal.DOUBLE_TAP
            StrapEventId.HIGH_FREQ_SYNC_PROMPT -> StrapSignal.PROMPT
            StrapEventId.STRAP_DRIVEN_ALARM_EXECUTED,
            StrapEventId.APP_DRIVEN_ALARM_EXECUTED,
            -> StrapSignal.ALARM_FIRED
            StrapEventId.RTC_LOST -> StrapSignal.CLOCK_LOST
            else -> return Inbound.Ignore
        }
        val battery = if (kind == StrapSignal.BATTERY) eventBattery(packet.payload) else null
        val charging = if (kind == StrapSignal.BATTERY) eventCharging(packet.payload) else null
        return Inbound.Signal(kind, packet.epochSecond, battery, charging)
    }

    private fun sampled(packet: Packet.Historical): Inbound {
        val gen5 = if (generation == StrapGeneration.GEN5) decodeGen5Summary(packet.raw) else null
        val decoded = if (generation == StrapGeneration.GEN5) gen5?.record else records.decode(packet.raw)
        val epoch = decoded?.epochSecond ?: epochAt(packet.raw)
        val raw = RawRecord(epoch, packet.raw.copyOf(), generation)
        return Inbound.Sampled(decoded?.let {
            sampleOf(it, packet.raw).copy(
                generation = generation,
                skinTemperatureCentiC = gen5?.skinTemperatureCentiC,
                bandState = gen5?.bandState,
            )
        }, raw)
    }

    private fun sampleOf(record: R24, raw: ByteArray): Sample {
        val gravity = if (record.gravity.size == 3) {
            GravityVector(
                record.gravity[0].toFloat(),
                record.gravity[1].toFloat(),
                record.gravity[2].toFloat(),
            )
        } else {
            null
        }
        return Sample(
            epochSecond = record.epochSecond,
            heartRateBpm = record.heartRate,
            rrIntervalsMs = record.rrIntervalsMs,
            gravity = gravity,
            optical = opticalOf(record),
            rawRecord = raw.toList(),
            // Zero is the layout-absent sentinel, never a measured count.
            skinTempCount = record.skinTempCountRaw.takeIf { it > 0 },
        )
    }

    private fun opticalOf(record: R24): OpticalChannels? {
        if (record.greenPpg == 0 && record.red == 0 && record.infrared == 0 && record.ambient == 0) return null
        return OpticalChannels(
            green = record.greenPpg,
            red = record.red,
            infrared = record.infrared,
            ambient = record.ambient,
        )
    }

    private fun epochAt(raw: ByteArray): Long {
        if (raw.size < 11) return 0L
        return (raw[7].toLong() and 0xFF) or
            ((raw[8].toLong() and 0xFF) shl 8) or
            ((raw[9].toLong() and 0xFF) shl 16) or
            ((raw[10].toLong() and 0xFF) shl 24)
    }

    // Ported from OpenStrap protocol/lib/src/control.dart — BATTERY_LEVEL body:
    // soc is a u16 of tenths at body[1], charging is body[9], and a short body is not a reading.
    private fun eventBattery(payload: ByteArray): Double? {
        if (payload.size < 12) return null
        val v = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
        val pct = v / 10.0
        return pct.takeIf { it in 0.0..100.0 }
    }

    private fun eventCharging(payload: ByteArray): Boolean? {
        if (payload.size < 12) return null
        return payload[9].toInt() != 0
    }
}
