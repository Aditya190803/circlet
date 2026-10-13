package app.circlet.analytics.motion

import app.circlet.core.StepReading
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L

/** A gap longer than this is not spread: the steps go to the minute of the later reading. */
private const val MAX_SPREAD_MINUTES = 60L

/**
 * Steps per epoch minute from a phone's cumulative step counter.
 *
 * Each reading after the first adds its rise over the one before to the minutes
 * from the earlier reading's minute to its own, split evenly. A counter that
 * drops restarted from zero, so the new value is the rise. Readings at the same
 * instant keep the last one. The first reading only sets the baseline.
 */
fun phoneStepsPerMinute(readings: List<StepReading>): Map<Long, Double> {
    val ordered = readings.sortedBy { it.epochMs }.fold(ArrayList<StepReading>()) { kept, reading ->
        if (kept.isNotEmpty() && kept.last().epochMs == reading.epochMs) kept[kept.lastIndex] = reading else kept.add(reading)
        kept
    }
    val minutes = TreeMap<Long, Double>()
    for (i in 1 until ordered.size) {
        val before = ordered[i - 1]
        val after = ordered[i]
        val rise = if (after.count >= before.count) after.count - before.count else after.count
        if (rise == 0L) continue
        val first = Math.floorDiv(before.epochMs, MINUTE_MS)
        val last = Math.floorDiv(after.epochMs, MINUTE_MS)
        if (last - first > MAX_SPREAD_MINUTES) {
            minutes.merge(last, rise.toDouble(), Double::plus)
        } else {
            val share = rise.toDouble() / (last - first + 1)
            for (minute in first..last) minutes.merge(minute, share, Double::plus)
        }
    }
    return minutes
}

/**
 * The phone's step count for local [date], or null when [readings] cannot give one:
 * fewer than two readings, or none inside the day.
 */
fun phoneStepsOnDay(readings: List<StepReading>, zone: ZoneId, date: LocalDate): Int? {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    if (readings.size < 2 || readings.none { it.epochMs in start until end }) return null
    val total = phoneStepsPerMinute(readings)
        .filterKeys { it * MINUTE_MS in start until end }
        .values.sum()
    return total.roundToInt()
}
