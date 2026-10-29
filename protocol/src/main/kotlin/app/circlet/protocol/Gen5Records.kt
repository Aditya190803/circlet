// Ported from OpenStrap protocol/lib/src/gen5_records.dart — Gen5V18Decoder.decode.
package app.circlet.protocol

import kotlin.math.abs

data class Gen5Summary(
    val record: R24,
    val skinTemperatureCentiC: Int?,
    val bandState: Int,
    val dynamicAccelerationG: Double?,
)

fun decodeGen5Summary(inner: ByteArray): Gen5Summary? {
    if (inner.size < 109 || u8(inner, 0) != 0x2F || u8(inner, 1) != 18) return null
    val hr = u8(inner, 14).takeIf { it in 25..230 } ?: 0
    val count = u8(inner, 15)
    val rr = if (count <= 4) List(count) { i16(inner, 16 + it * 2) }.filter { it in MIN_RR_MS..MAX_RR_MS } else emptyList()
    val gravity = listOf(37, 41, 45).map { roundPlaces(f32(inner, it), 4) }
        .takeIf { axes -> axes.all { it.isFinite() && abs(it) <= 16.0 } } ?: emptyList()
    val dynamic = roundPlaces(f32(inner, 33), 4).takeIf { it.isFinite() && it in 0.0..16.0 }
    return Gen5Summary(
        record = R24(
            version = 18,
            epochSecond = u32(inner, 7),
            subSeconds = u16(inner, 11),
            counter = u32(inner, 3),
            heartRate = hr,
            rrIntervalsMs = rr,
            greenPpg = 0,
            gravity = gravity,
            red = 0,
            infrared = 0,
            ambient = 0,
            rawTail = inner.copyOfRange(13, inner.size),
        ),
        skinTemperatureCentiC = i16(inner, 65).takeUnless { it == -5000 },
        bandState = (u8(inner, 73) ushr 4) and 3,
        dynamicAccelerationG = dynamic,
    )
}
