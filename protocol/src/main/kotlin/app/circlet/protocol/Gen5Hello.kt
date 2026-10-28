// Ported from OpenStrap protocol/lib/src/control.dart — Gen5HelloInfo.parse, clock response.
package app.circlet.protocol

data class Gen5Hello(
    val revision: Int,
    val batteryPercent: Double?,
    val charging: Boolean,
    val epochSecond: Long,
    val subSeconds: Long,
    val serial: String,
    val firmware: String,
    val onWrist: Boolean,
    val opticalDiscriminator: Long,
    val locked: Boolean? = null,
    val lockCounterSeconds: Long? = null,
) {
    val clockMillis: Long get() = epochSecond * 1000 + subSeconds * 1000 / 32768
    val identityValid: Boolean get() = serial.isNotEmpty() && serial.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
}

fun gen5Hello(body: ByteArray): Gen5Hello? {
    if (body.size < 104) return null
    val serialBytes = body.copyOfRange(14, 25).takeWhile { it != 0.toByte() }
    val serial = if (serialBytes.all { (it.toInt() and 255) in 0x20..0x7E }) {
        serialBytes.toByteArray().toString(Charsets.US_ASCII)
    } else ""
    return Gen5Hello(
        revision = u8(body, 0),
        batteryPercent = (u32(body, 1) / 10).takeIf { it <= 100 }?.toDouble(),
        charging = u8(body, 5) and 1 != 0,
        epochSecond = u32(body, 6),
        subSeconds = u32(body, 10),
        serial = serial,
        firmware = "${u8(body, 91)}.${u8(body, 92)}.${u8(body, 93)}.${u32(body, 94)}",
        onWrist = u8(body, 102) == 1,
        opticalDiscriminator = u32(body, 87),
        // Official-app cross-check: revision 3 adds lock status at 103 and counter at 107.
        locked = if (u8(body, 0) == 3 && body.size >= 111) u8(body, 103) and 0x40 != 0 else null,
        lockCounterSeconds = if (u8(body, 0) == 3 && body.size >= 111) u32(body, 107) else null,
    )
}

fun clockEpoch(opcode: Int, body: ByteArray): Long? {
    val offset = if (opcode == 0x93) 1 else 0
    if (opcode != 0x0B && opcode != 0x93) return null
    if (body.size < offset + 4 || (opcode == 0x93 && u8(body, 0) != 1)) return null
    return u32(body, offset)
}
