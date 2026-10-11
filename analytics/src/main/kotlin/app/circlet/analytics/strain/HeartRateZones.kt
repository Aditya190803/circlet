package app.circlet.analytics.strain

import app.circlet.analytics.support.percentileSorted
import app.circlet.analytics.support.sampleCadenceSeconds
import kotlin.math.min

// Ported from OpenStrap lib/src/onehz/workout/hr_zones.dart.
// Display zones are the 50/60/70/80/90/100 percent bands, either of HRmax
// (Tanaka 2001 when no override) or of heart-rate reserve (Karvonen 1957)
// once 14 nights of resting heart rate exist.

internal data class DisplayZone(
    val number: Int,
    val lower: Double,
    val upper: Double,
    val lowerPct: Double,
    val upperPct: Double,
)

internal data class HeartRateZoneSet(
    val zones: List<DisplayZone>,
    val maxHr: Double,
    val source: String,
) {
    init {
        require(zones.size == 5)
    }

    /** Zone number 1..5, or 0 when below zone 1. */
    fun zoneNumber(bpm: Double): Int {
        for (zone in zones) {
            if (zone.number == 5) {
                if (bpm >= zone.lower) return 5
            } else if (bpm >= zone.lower && bpm < zone.upper) {
                return zone.number
            }
        }
        return 0
    }
}

internal data class TimeInHeartRateZone(
    val seconds: List<Double>,
    val belowZone1: Double,
) {
    init {
        require(seconds.size == 5)
    }

    fun secondsInZone(zone: Int): Double = if (zone in 1..5) seconds[zone - 1] else 0.0

    fun toRoundedMinuteMap(): Map<String, Int> = mapOf(
        "z1" to (seconds[0] / 60.0).let { kotlin.math.round(it).toInt() },
        "z2" to (seconds[1] / 60.0).let { kotlin.math.round(it).toInt() },
        "z3" to (seconds[2] / 60.0).let { kotlin.math.round(it).toInt() },
        "z4" to (seconds[3] / 60.0).let { kotlin.math.round(it).toInt() },
        "z5" to (seconds[4] / 60.0).let { kotlin.math.round(it).toInt() },
    )
}

internal data class HrPoint(val tsMs: Double, val hr: Double) {
    val valid: Boolean get() = hr > 0
}

internal object HeartRateZones {
    val zoneEdges = doubleArrayOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)
    const val RESERVE_MIN_DAYS = 14

    fun tanakaMaxHr(age: Double): Double = 208.0 - 0.7 * age

    fun zones(age: Double, maxHrOverride: Double? = null): HeartRateZoneSet =
        if (maxHrOverride != null) zonesFromMaxHr(maxHrOverride, "manual")
        else zonesFromMaxHr(tanakaMaxHr(age), "tanaka")

    fun zonesFromMaxHr(maxHr: Double, source: String = "manual"): HeartRateZoneSet {
        val built = ArrayList<DisplayZone>(5)
        for (i in 0 until 5) {
            val lo = zoneEdges[i]
            val hi = zoneEdges[i + 1]
            built.add(DisplayZone(i + 1, lo * maxHr, hi * maxHr, lo, hi))
        }
        return HeartRateZoneSet(built, maxHr, source)
    }

    fun reserveZones(
        restingHrHistory: List<Double>,
        maxHr: Double,
        minDays: Int = RESERVE_MIN_DAYS,
        source: String = "karvonen",
    ): HeartRateZoneSet? {
        val valid = restingHrHistory.filter { it.isFinite() && it > 0 }.sorted()
        if (valid.size < minDays) return null
        val rhr = percentileSorted(valid, 50.0) ?: return null
        if (!(maxHr > rhr)) return null
        val reserve = maxHr - rhr
        val built = ArrayList<DisplayZone>(5)
        for (i in 0 until 5) {
            val lo = zoneEdges[i]
            val hi = zoneEdges[i + 1]
            built.add(DisplayZone(i + 1, rhr + lo * reserve, rhr + hi * reserve, lo, hi))
        }
        return HeartRateZoneSet(built, maxHr, source)
    }

    fun timeInZone(hr: List<HrPoint>, zoneSet: HeartRateZoneSet): TimeInHeartRateZone? {
        // Day points arrive chronological; sorting them again is pure waste.
        val sorted = if (hr.isOrdered()) hr else hr.sortedBy { it.tsMs }
        val zoneSeconds = DoubleArray(5)
        var below = 0.0
        val tailSeconds = sampleCadenceSeconds(sorted.map { it.tsMs / 1000.0 }) ?: return null
        for (i in sorted.indices) {
            val sample = sorted[i]
            if (!sample.valid) continue
            val dur = if (i < sorted.size - 1) {
                boundedGapSeconds(sorted[i + 1].tsMs - sample.tsMs, tailSeconds)
            } else {
                tailSeconds
            }
            val zone = zoneSet.zoneNumber(sample.hr)
            if (zone >= 1) zoneSeconds[zone - 1] += dur else below += dur
        }
        return TimeInHeartRateZone(zoneSeconds.toList(), below)
    }

    private fun boundedGapSeconds(gapMs: Double, fallbackSeconds: Double): Double {
        val gapSeconds = gapMs / 1000.0
        return if (gapSeconds > 0) min(gapSeconds, fallbackSeconds) else fallbackSeconds
    }

    /** True when no point precedes an earlier one; such an input sorts to itself. */
    private fun List<HrPoint>.isOrdered(): Boolean {
        for (i in 1 until size) {
            if (this[i].tsMs < this[i - 1].tsMs) return false
        }
        return true
    }
}
