package app.circlet.data.codec

import app.circlet.core.GravityVector
import app.circlet.core.OpticalChannels
import app.circlet.core.StrapGeneration
import app.circlet.core.Sample
import app.circlet.data.db.SampleEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian u16 milliseconds. Values outside 0..65535 are clamped; real beats sit inside that. */
internal fun packRr(intervals: List<Int>): ByteArray? {
    if (intervals.isEmpty()) return null
    val out = ByteArray(intervals.size * 2)
    for (i in intervals.indices) {
        val value = intervals[i].coerceIn(0, 65535)
        out[i * 2] = (value and 0xFF).toByte()
        out[i * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
    }
    return out
}

internal fun unpackRr(bytes: ByteArray?): List<Int> {
    if (bytes == null || bytes.size < 2) return emptyList()
    val count = bytes.size / 2
    return List(count) { index ->
        (bytes[index * 2].toInt() and 0xFF) or ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8)
    }
}

internal fun packGravity(vector: GravityVector?): ByteArray? {
    if (vector == null) return null
    return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(vector.x)
        putFloat(vector.y)
        putFloat(vector.z)
    }.array()
}

internal fun unpackGravity(bytes: ByteArray?): GravityVector? {
    if (bytes == null || bytes.size < 12) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return GravityVector(buffer.float, buffer.float, buffer.float)
}

internal fun Sample.toEntity(): SampleEntity {
    val channels = optical
    return SampleEntity(
        epochSecond = epochSecond,
        heartRate = heartRateBpm,
        rr = packRr(rrIntervalsMs),
        gravity = packGravity(gravity),
        green = channels?.green,
        red = channels?.red,
        infrared = channels?.infrared,
        ambient = channels?.ambient,
        skinTempCount = skinTempCount,
        generation = generation.name,
        skinTemperatureCentiC = skinTemperatureCentiC,
        bandState = bandState,
    )
}

/** Decoded fields only. The archive table keeps [Sample.rawRecord]. */
internal fun SampleEntity.toSample(): Sample {
    val channels = if (green == null && red == null && infrared == null && ambient == null) {
        null
    } else {
        OpticalChannels(green, red, infrared, ambient)
    }
    return Sample(
        epochSecond = epochSecond,
        heartRateBpm = heartRate,
        rrIntervalsMs = unpackRr(rr),
        gravity = unpackGravity(gravity),
        optical = channels,
        rawRecord = null,
        skinTempCount = skinTempCount,
        generation = StrapGeneration.valueOf(generation),
        skinTemperatureCentiC = skinTemperatureCentiC,
        bandState = bandState,
    )
}
