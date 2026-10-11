// Ported from OpenStrap protocol/lib/src/records.dart — parseR24, FirmwareAwareR24Decoder.
package app.circlet.protocol

/**
 * One decoded historical biometric record.
 *
 * [gravity] is empty when this layout has no accelerometer field. An empty list
 * is absence, not a still wrist. Optical counts are zero when this layout does
 * not carry them; zero is that sentinel, not a measured darkness.
 * [heartRate] of zero means the strap reported no pulse.
 *
 * Three OpenStrap fields are omitted because that decoder marks them as
 * misidentified: the u16 straddling the float at offset 32 and the exponent
 * byte at offset 51. The u16 at offset 68 is exposed as [R24.skinTempCountRaw]
 * instead: OpenStrap's protocol audit disputes the temperature reading (on one
 * large capture it moves several counts a second, which skin cannot), so it is
 * a relative count only, never degrees, and whether it behaves as temperature
 * on this firmware still needs a real strap to confirm.
 */
data class R24(
    val version: Int,
    val epochSecond: Long,
    val subSeconds: Int,
    val counter: Long,
    val heartRate: Int,
    val rrIntervalsMs: List<Int>,
    val greenPpg: Int,
    val gravity: List<Double>,
    val red: Int,
    val infrared: Int,
    val ambient: Int,
    /**
     * The uncalibrated u16 at inner offset 68. This is a relative count, not
     * degrees, and its temperature interpretation is unconfirmed: OpenStrap's
     * protocol audit found the word moving several counts a second on a large
     * capture, which a skin temperature cannot do. Zero is the absent sentinel
     * for layouts that do not carry the word. Stored so a nightly settled mean
     * can be compared with this user's own nights; never converted to °C.
     */
    val skinTempCountRaw: Int = 0,
    val rawTail: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is R24) return false
        return version == other.version &&
            epochSecond == other.epochSecond &&
            subSeconds == other.subSeconds &&
            counter == other.counter &&
            heartRate == other.heartRate &&
            rrIntervalsMs == other.rrIntervalsMs &&
            greenPpg == other.greenPpg &&
            gravity == other.gravity &&
            red == other.red &&
            infrared == other.infrared &&
            ambient == other.ambient &&
            skinTempCountRaw == other.skinTempCountRaw &&
            rawTail.contentEquals(other.rawTail)
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + epochSecond.hashCode()
        h = 31 * h + subSeconds
        h = 31 * h + counter.hashCode()
        h = 31 * h + heartRate
        h = 31 * h + rrIntervalsMs.hashCode()
        h = 31 * h + greenPpg
        h = 31 * h + gravity.hashCode()
        h = 31 * h + red
        h = 31 * h + infrared
        h = 31 * h + ambient
        h = 31 * h + skinTempCountRaw
        h = 31 * h + rawTail.contentHashCode()
        return h
    }
}

private const val LEGACY_MIN_LENGTH = 89
private const val SHORT_MIN_LENGTH = 72
private const val V25_MIN_LENGTH = 75
private const val RR_SLOTS = 4
internal const val MIN_RR_MS = 200
internal const val MAX_RR_MS = 2500

private val HR_OFFSET = mapOf(
    7 to 27,
    9 to 17,
    12 to 17,
    18 to 14,
    24 to 17,
)

internal val KNOWN_RECORD_VERSIONS = HR_OFFSET.keys + 25

private fun isRecordPacket(inner: ByteArray): Boolean {
    if (inner.isEmpty()) return false
    val t = u8(inner, 0)
    return t == 0x2F || t == 0x28
}

/**
 * Hardware-validated decoder. Records shorter than 89 bytes come back null;
 * [FirmwareAwareR24Decoder] retries those at the 72-byte floor.
 */
internal fun parseR24(inner: ByteArray): R24? {
    if (inner.size < 2 || !isRecordPacket(inner)) return null
    val version = u8(inner, 1)
    if (version == 25) return parseV25(inner)
    val trusted = version == 24 || version == 12
    val hrOffset = HR_OFFSET[version] ?: 17
    return parseV24(inner, version, hrOffset, validate = !trusted, minLength = LEGACY_MIN_LENGTH)
}

private fun parseV25(inner: ByteArray): R24? {
    if (inner.size < V25_MIN_LENGTH) return null
    return R24(
        version = 25,
        epochSecond = u32(inner, 7),
        subSeconds = 0,
        counter = u32(inner, 3),
        heartRate = 0,
        rrIntervalsMs = emptyList(),
        greenPpg = 0,
        gravity = emptyList(),
        red = 0,
        infrared = 0,
        ambient = 0,
        rawTail = if (inner.size > 13) inner.copyOfRange(13, inner.size) else ByteArray(0),
    )
}

private fun plausible(gravity: List<Double>, hr: Int): Boolean {
    if (hr !in 25..230 || gravity.size != 3) return false
    val mag = gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]
    return mag >= 0.25 && mag <= 3.24
}

private fun parseV24(
    inner: ByteArray,
    version: Int,
    hrOffset: Int,
    validate: Boolean,
    minLength: Int,
): R24? {
    if (inner.size < minLength || hrOffset >= inner.size) return null
    val declared = if (validate) 0 else u8(inner, 18)
    val intervals = ArrayList<Int>(RR_SLOTS)
    if (declared <= RR_SLOTS) {
        var i = 0
        while (i < declared && 19 + 2 * i + 2 <= inner.size) {
            val v = i16(inner, 19 + 2 * i)
            if (v in MIN_RR_MS..MAX_RR_MS) intervals.add(v)
            i++
        }
    }
    val hr = u8(inner, hrOffset)
    val gravity = listOf(
        roundPlaces(f32(inner, 36), 4),
        roundPlaces(f32(inner, 40), 4),
        roundPlaces(f32(inner, 44), 4),
    )
    if (gravity.any { !it.isFinite() }) return null
    if (validate && !plausible(gravity, hr)) return null
    val optical = !validate
    return R24(
        version = version,
        epochSecond = u32(inner, 7),
        subSeconds = u16(inner, 11),
        counter = u32(inner, 3),
        heartRate = hr,
        rrIntervalsMs = intervals,
        greenPpg = if (optical) u16(inner, 29) else 0,
        gravity = gravity,
        red = if (optical) u16(inner, 64) else 0,
        infrared = if (optical) u16(inner, 66) else 0,
        ambient = if (optical) u16(inner, 70) else 0,
        skinTempCountRaw = if (optical) u16(inner, 68) else 0,
        rawTail = if (inner.size > 13) inner.copyOfRange(13, inner.size) else ByteArray(0),
    )
}

private fun shortFrame(inner: ByteArray): R24? {
    if (inner.size < 2) return null
    val version = u8(inner, 1)
    if (version == 25) return null
    val trusted = version == 24 || version == 12
    val hrOffset = HR_OFFSET[version] ?: 17
    return parseV24(inner, version, hrOffset, validate = !trusted, minLength = SHORT_MIN_LENGTH)
}

/**
 * Tries the 89-byte decoder first, then the 72-byte floor, and remembers which
 * one worked for each version byte. One instance per connection.
 */
class FirmwareAwareR24Decoder {
    private val detected = HashMap<Int, String>()

    val detectedStrategies: Map<Int, String> get() = detected.toMap()

    fun decode(inner: ByteArray): R24? {
        if (inner.size < 2 || !isRecordPacket(inner)) return null
        val version = u8(inner, 1)
        if (version == 25) return parseR24(inner)
        val known = detected[version]
        if (known != null) {
            val hit = strategy(known, inner)
            if (hit != null) return hit
        }
        for (name in listOf("legacy_89b", "short_frame_72b")) {
            val hit = strategy(name, inner)
            if (hit != null) {
                detected[version] = name
                return hit
            }
        }
        return null
    }

    fun reset() {
        detected.clear()
    }

    private fun strategy(name: String, inner: ByteArray): R24? = when (name) {
        "legacy_89b" -> parseR24(inner)
        "short_frame_72b" -> shortFrame(inner)
        else -> parseR24(inner)
    }
}

/** Stateless walk of the same chain, for a caller that does not keep a session decoder. */
internal fun decodeR24(inner: ByteArray): R24? = FirmwareAwareR24Decoder().decode(inner)
