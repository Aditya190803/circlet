// Ported from OpenStrap protocol/lib/src/band.dart — BandProfile, GattProfile.
package app.circlet.protocol

import java.util.UUID

enum class BandProfile(val headerLength: Int, val sizeOffset: Int, prefix: String, suffix: String) {
    Gen4(4, 1, "6108", "8d6d-82b8-614a-1c8cb0f8dcc6"),
    Gen5(8, 2, "fd4b", "cce1-4033-93ce-002d5875f58a"),
    ;

    val service: UUID = UUID.fromString("${prefix}0001-$suffix")
    val commandTo: UUID = UUID.fromString("${prefix}0002-$suffix")
    val commandFrom: UUID = UUID.fromString("${prefix}0003-$suffix")
    val events: UUID = UUID.fromString("${prefix}0004-$suffix")
    val data: UUID = UUID.fromString("${prefix}0005-$suffix")

    internal fun header(declared: Int): ByteArray = ByteArray(headerLength).also { out ->
        out[0] = 0xAA.toByte()
        out[sizeOffset] = declared.toByte()
        out[sizeOffset + 1] = (declared ushr 8).toByte()
        if (this == Gen4) {
            out[3] = crc8(out[1].toInt(), out[2].toInt()).toByte()
        } else {
            out[1] = 1
            out[5] = 1
            val sum = crc16Modbus(out.copyOfRange(0, 6))
            out[6] = sum.toByte()
            out[7] = (sum ushr 8).toByte()
        }
    }

    internal fun validHeader(header: ByteArray): Boolean = if (this == Gen4) {
        u8(header, 3) == crc8(u8(header, 1), u8(header, 2))
    } else {
        crc16Modbus(header.copyOfRange(0, 6)) == u16(header, 6)
    }

    companion object {
        fun fromService(uuid: UUID): BandProfile? = entries.firstOrNull { it.service == uuid }
    }
}
