// Ported from OpenStrap protocol/lib/src/control.dart — decodeFrame,
// and protocol/lib/src/constants.dart — EventId.
package app.circlet.protocol

/** Wire ids this decoder names. Unknown ids stay numeric on [Packet.Event.wireId]. */
enum class StrapEventId(val wire: Int) {
    BATTERY_LEVEL(3),
    CHARGING_ON(7),
    CHARGING_OFF(8),
    WRIST_ON(9),
    WRIST_OFF(10),
    RTC_LOST(13),
    DOUBLE_TAP(14),
    BOOT(15),
    SET_RTC(16),
    TEMPERATURE_LEVEL(111),
    BATTERY_PACK_CONNECTED(21),
    BATTERY_PACK_REMOVED(22),
    BLE_BONDED(31),
    TRIM_ALL_DATA(26),
    TRIM_ALL_DATA_ENDED(27),
    FLASH_INIT_COMPLETE(28),
    STRAP_DRIVEN_ALARM_SET(56),
    STRAP_DRIVEN_ALARM_EXECUTED(57),
    APP_DRIVEN_ALARM_EXECUTED(58),
    STRAP_DRIVEN_ALARM_DISABLED(59),
    HAPTICS_FIRED(60),
    EXTENDED_BATTERY_INFORMATION(63),
    HIGH_FREQ_SYNC_PROMPT(96),
    HIGH_FREQ_SYNC_ENABLED(97),
    HIGH_FREQ_SYNC_DISABLED(98),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(id: Int): StrapEventId? = byWire[id]
    }
}

enum class MetadataKind { START, END, COMPLETE }

sealed interface Packet {
    data class Response(
        val opcode: Int,
        val originSeq: Int,
        val result: Int,
        val body: ByteArray,
    ) : Packet {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return opcode == other.opcode && originSeq == other.originSeq &&
                result == other.result && body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var h = opcode
            h = 31 * h + originSeq
            h = 31 * h + result
            h = 31 * h + body.contentHashCode()
            return h
        }
    }

    class Historical(
        val version: Int,
        val record: R24?,
        val raw: ByteArray,
    ) : Packet {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Historical) return false
            return version == other.version && record == other.record && raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var h = version
            h = 31 * h + (record?.hashCode() ?: 0)
            h = 31 * h + raw.contentHashCode()
            return h
        }
    }

    data class Realtime(
        val epochSecond: Long,
        val heartRate: Int,
        val rrMs: List<Int>,
    ) : Packet

    /**
     * [id] is the named event when [wireId] is one we know. Unknown ids keep
     * [id] null and the numeric [wireId], so the event is not dropped.
     */
    class Event(
        val id: StrapEventId?,
        val wireId: Int,
        val epochSecond: Long,
        val payload: ByteArray,
    ) : Packet {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Event) return false
            return id == other.id && wireId == other.wireId &&
                epochSecond == other.epochSecond && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var h = id?.hashCode() ?: 0
            h = 31 * h + wireId
            h = 31 * h + epochSecond.hashCode()
            h = 31 * h + payload.contentHashCode()
            return h
        }
    }

    class Metadata(
        val kind: MetadataKind,
        val token: ByteArray?,
        val expectedPacketCount: Long? = null,
    ) : Packet {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Metadata) return false
            return kind == other.kind && expectedPacketCount == other.expectedPacketCount &&
                ((token == null && other.token == null) ||
                    (token != null && other.token != null && token.contentEquals(other.token)))
        }

        override fun hashCode(): Int = 31 * (31 * kind.hashCode() + (token?.contentHashCode() ?: 0)) +
            (expectedPacketCount?.hashCode() ?: 0)
    }

    data class Other(val type: Int) : Packet
}

/**
 * Decode one CRC-valid inner packet.
 * Historical records go through [FirmwareAwareR24Decoder]'s chain without keeping
 * session state; hold a [FirmwareAwareR24Decoder] when one connection should remember
 * which length a version byte used.
 */
object Decoder {
    fun decode(inner: ByteArray, profile: BandProfile = BandProfile.Gen4): Packet? {
        if (inner.isEmpty()) return null
        return when (u8(inner, 0)) {
            0x24 -> response(inner)
            0x2F -> historical(inner, profile)
            0x28 -> realtime(inner)
            0x2B -> rawRealtime(inner)
            0x30 -> event(inner)
            0x31 -> metadata(inner)
            else -> Packet.Other(u8(inner, 0))
        }
    }

    private fun response(inner: ByteArray): Packet? {
        if (inner.size < 5) return null
        val body = if (inner.size > 5) inner.copyOfRange(5, inner.size) else ByteArray(0)
        return Packet.Response(u8(inner, 2), u8(inner, 3), u8(inner, 4), body)
    }

    private fun historical(inner: ByteArray, profile: BandProfile): Packet {
        if (inner.size < 2) return Packet.Other(0x2F)
        val record = if (profile == BandProfile.Gen5) decodeGen5Summary(inner)?.record else decodeR24(inner)
        return Packet.Historical(u8(inner, 1), record, inner.copyOf())
    }

    private fun realtime(inner: ByteArray): Packet {
        if (inner.size < 9) return Packet.Other(0x28)
        return Packet.Realtime(u32(inner, 2), u8(inner, 8), rrOf(inner))
    }

    private fun rawRealtime(inner: ByteArray): Packet {
        if (inner.size < 18 || u8(inner, 1) != 10) return Packet.Other(0x2B)
        return Packet.Realtime(u32(inner, 7), u8(inner, 17), rrOf(inner))
    }

    private fun rrOf(inner: ByteArray): List<Int> = realtimeRr(inner)?.intervals ?: emptyList()

    private fun event(inner: ByteArray): Packet {
        if (inner.size < 4) return Packet.Other(0x30)
        val id = u16(inner, 2)
        val epoch = if (inner.size >= 8) u32(inner, 4) else 0L
        val payload = eventPayload(inner)
        return Packet.Event(StrapEventId.fromWire(id), id, epoch, payload)
    }

    private fun eventPayload(inner: ByteArray): ByteArray {
        if (inner.size < 12) return ByteArray(0)
        val len = u16(inner, 10)
        val start = 12
        val end = minOf(inner.size, start + len)
        if (end <= start) return ByteArray(0)
        return inner.copyOfRange(start, end)
    }

    private fun metadata(inner: ByteArray): Packet {
        if (inner.size < 3) return Packet.Other(0x31)
        val kind = when (u8(inner, 2)) {
            1 -> MetadataKind.START
            2 -> MetadataKind.END
            3 -> MetadataKind.COMPLETE
            else -> return Packet.Other(0x31)
        }
        val token = if (kind == MetadataKind.END && inner.size >= 21) {
            inner.copyOfRange(13, 21)
        } else {
            null
        }
        val count = if (kind == MetadataKind.END && inner.size >= 13) u32(inner, 9) else null
        return Packet.Metadata(kind, token, count)
    }
}
