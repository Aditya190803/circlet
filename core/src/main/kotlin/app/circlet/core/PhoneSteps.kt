package app.circlet.core

import java.time.LocalDate

/**
 * One reading of the phone's cumulative step counter (Android TYPE_STEP_COUNTER).
 * [count] only grows until the phone restarts, then it begins again near zero.
 */
data class StepReading(val epochMs: Long, val count: Long)

/** The on-disk form of [StepReading]: one `epochMs,count` line, in a file per local date. */
object StepCsv {
    fun fileName(date: LocalDate): String = "$date.csv"

    fun line(reading: StepReading): String = "${reading.epochMs},${reading.count}"

    /** Lines that are not two non-negative numbers are skipped, so a torn write loses one reading at most. */
    fun parse(lines: Sequence<String>): List<StepReading> = lines.mapNotNull { text ->
        val parts = text.split(',')
        if (parts.size != 2) return@mapNotNull null
        val epochMs = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
        val count = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
        if (epochMs < 0 || count < 0) null else StepReading(epochMs, count)
    }.toList()
}
