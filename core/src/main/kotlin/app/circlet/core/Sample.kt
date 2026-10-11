package app.circlet.core

import java.time.Instant
import kotlin.math.sqrt

/**
 * Device-frame acceleration from a 1 Hz record, in multiples of g.
 * Axes match the strap's decoded vector; this type does not rotate them.
 */
data class GravityVector(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Raw optical counts from a 1 Hz record. These are ADC counts, not a
 * percentage, and they are not SpO2 or skin temperature.
 * A null channel means that layout did not carry the field.
 */
data class OpticalChannels(
    val green: Int?,
    val red: Int?,
    val infrared: Int?,
    val ambient: Int?,
)

/**
 * One decoded 1 Hz record shared by protocol, analytics, and storage.
 *
 * [heartRateBpm] is beats per minute. Zero means the strap reported off-wrist,
 * not a measured rate. Null means the record had no heart-rate field.
 * [rrIntervalsMs] are beat-to-beat intervals in milliseconds, in order.
 * [rawRecord] is the original record body so a later decoder can re-read it.
 * [skinTempCount] is the uncalibrated u16 at inner offset 68. It is not degrees.
 * Null means that layout did not carry the word. OpenStrap's protocol audit found
 * the word moving several counts a second on a large capture, which a skin
 * temperature cannot; it is stored so a nightly settled mean can be compared
 * with this user's own nights, and it is never converted to °C.
 */
data class Sample(
    /** Unix epoch second of the record. */
    val epochSecond: Long,
    val heartRateBpm: Int?,
    val rrIntervalsMs: List<Int>,
    val gravity: GravityVector?,
    val optical: OpticalChannels?,
    val rawRecord: List<Byte>?,
    val skinTempCount: Int? = null,
)

/** Measured heart rate in bpm, or null when the sample is off-wrist, missing, or implausible. */
fun Sample.measurementBpm(): Int? = heartRateBpm?.takeIf(SignalBounds::plausibleBpm)

/** True when every stored RR interval sits inside [SignalBounds]. */
fun Sample.rrIntervalsInRange(): Boolean = rrIntervalsMs.all(SignalBounds::plausibleRr)

/**
 * Something the strap reported that is not a 1 Hz sample.
 * [at] is when the phone observed the event.
 */
sealed interface StrapEvent {
    val at: Instant

    /** Battery percentage, 0–100, already divided by ten from the strap's u16. */
    data class Battery(override val at: Instant, val percent: Double) : StrapEvent

    data class Charging(override val at: Instant, val charging: Boolean) : StrapEvent

    data class Wrist(override val at: Instant, val onWrist: Boolean) : StrapEvent

    data class DoubleTap(override val at: Instant) : StrapEvent

    /** The strap asked the phone to drain history soon. */
    data class HighFrequencySyncPrompt(override val at: Instant) : StrapEvent
}

// Ported from OpenStrap protocol/lib/src/records.dart: kMinRrMs, kMaxRrMs,
// and _physiologicallyPlausible. Those are the bounds the Gen 4 decoder uses
// to decide a beat or a gravity vector is real.
/**
 * Shared physiological bounds for a 1 Hz sample.
 * Heart rate is beats per minute. RR intervals are milliseconds.
 * Gravity magnitude is multiples of g.
 */
object SignalBounds {
    const val MIN_RR_MS = 200
    const val MAX_RR_MS = 2500
    const val MIN_BPM = 25
    const val MAX_BPM = 230

    /** The strap's own "no optical pulse" code. This is not a measurement. */
    fun offWrist(bpm: Int): Boolean = bpm == 0

    fun plausibleBpm(bpm: Int): Boolean = bpm in MIN_BPM..MAX_BPM

    fun plausibleRr(intervalMs: Int): Boolean = intervalMs in MIN_RR_MS..MAX_RR_MS

    /** Magnitude between 0.5 g and 1.8 g, compared on the square so the gate matches the decoder. */
    fun plausibleGravity(vector: GravityVector): Boolean {
        val x = vector.x.toDouble()
        val y = vector.y.toDouble()
        val z = vector.z.toDouble()
        val magSq = x * x + y * y + z * z
        return magSq >= 0.25 && magSq <= 3.24
    }

    fun magnitudeG(vector: GravityVector): Double {
        val x = vector.x.toDouble()
        val y = vector.y.toDouble()
        val z = vector.z.toDouble()
        return sqrt(x * x + y * y + z * z)
    }
}
