// Ported from OpenStrap analytics/lib/src/onehz/types.dart.
// The 1 Hz substrate's own envelopes. This is not core `Metric`: OpenStrap carries
// a 0..1 confidence and a method tier, and the day-facing types map onto core at
// the package boundary. Absent input is a null value and confidence 0.

package app.circlet.analytics.foundations

/** `need_baseline:have=<H>,need=<N>` — the only note a short baseline may carry. */
fun needBaselineNote(have: Int, need: Int): String = "need_baseline:have=$have,need=$need"

/** Confidence tier of a published method on this substrate. */
object Tier {
    const val AUTH = "AUTH"
    const val HIGH = "HIGH"
    const val ESTIMATE = "ESTIMATE"
    const val RELATIVE = "RELATIVE"
    val all: Set<String> = setOf(AUTH, HIGH, ESTIMATE, RELATIVE)
}

/** One ranked contributor. [contribution] is a signed standardized weight. */
data class Driver(
    val label: String,
    val contribution: Double,
    val detail: String? = null,
) {
    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "label" to label,
            "contribution" to round6(contribution),
        )
        if (detail != null) m["detail"] = detail
        return m
    }
}

/**
 * OpenStrap's metric envelope.
 * [value] null means the inputs were absent or insufficient, and then [confidence] is 0.
 */
data class OsMetric<T>(
    val value: T?,
    val confidence: Double,
    val tier: String,
    val inputsUsed: List<String>,
    val drivers: List<Driver>? = null,
    val note: String? = null,
) {
    val present: Boolean get() = value != null

    fun toJson(encode: ((T) -> Any?)? = null): Map<String, Any?> {
        val encoded = if (value == null) {
            null
        } else if (encode != null) {
            encode(value)
        } else {
            value
        }
        val bad = encoded is Double && !encoded.isFinite()
        val m = linkedMapOf<String, Any?>(
            "value" to if (encoded == null || bad) "—" else encoded,
            "confidence" to round6(if (bad) 0.0 else confidence),
            "tier" to tier,
            "inputs_used" to inputsUsed,
        )
        if (drivers != null) m["drivers"] = drivers.map { it.toJson() }
        if (note != null) m["note"] = note
        return m
    }

    companion object {
        fun <T> absent(
            tier: String,
            inputsUsed: List<String>,
            note: String? = null,
        ): OsMetric<T> = OsMetric(
            value = null,
            confidence = 0.0,
            tier = tier,
            inputsUsed = inputsUsed,
            drivers = null,
            note = note,
        )
    }
}

/**
 * Beat-to-beat intervals. [tsMs] is the end of each interval.
 * Pulse-derived: this is PRV, not ECG HRV.
 */
data class RrSeries(
    val tsMs: List<Double>,
    val rrMs: List<Double>,
) {
    init {
        require(tsMs.size == rrMs.size)
    }

    val length: Int get() = rrMs.size
    fun isEmpty(): Boolean = rrMs.isEmpty()

    /** Cumulative beat times in milliseconds, anchored at [t0Ms]. */
    fun beatTimesMs(t0Ms: Double = 0.0): List<Double> {
        val out = ArrayList<Double>(rrMs.size)
        var t = t0Ms
        for (rr in rrMs) {
            t += rr
            out.add(t)
        }
        return out
    }
}

/** 1 Hz heart rate. `hr == 0` is off-skin, never bradycardia. */
data class HrSample(val tsMs: Double, val hr: Double) {
    val valid: Boolean get() = hr > 0.0
}

/** 1 Hz gravity vector, in g. */
data class AccelSample(
    val tsMs: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val valid: Boolean = true,
)

/** Relative ADC count. No absolute unit. */
data class AdcSample(
    val tsMs: Double,
    val adc: Double,
    val valid: Boolean = true,
)

/** One nightly aggregate. A null field is a night that did not measure that quantity. */
data class NightlyRecord(
    val date: String,
    val rhr: Double? = null,
    val lnRmssd: Double? = null,
    val rmssd: Double? = null,
    val sdnn: Double? = null,
    val skinTempZ: Double? = null,
    val respRate: Double? = null,
    val coverage: Double? = null,
)

/** Sex constant for Banister TRIMP. Distinct from the profile's sex, which can be unspecified. */
enum class BanisterSex {
    MALE,
    FEMALE,
}
