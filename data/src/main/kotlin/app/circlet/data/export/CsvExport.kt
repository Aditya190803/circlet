package app.circlet.data.export

import app.circlet.core.Activity
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.reading
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val HEART_RATE_DAYS = 30L
private val OFFSET_TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** One CSV line without the line break: fields are quoted when they hold a comma, quote or line break (RFC 4180). */
internal fun csvLine(fields: List<String>): String = fields.joinToString(",") { field ->
    if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

private fun csv(header: List<String>, rows: List<List<String>>): String =
    (listOf(header) + rows).joinToString("") { csvLine(it) + "\r\n" }

/** A number with [digits] decimals, or an empty field when it is absent. */
private fun num(value: Double?, digits: Int): String =
    if (value == null) "" else String.format(Locale.ROOT, "%.${digits}f", value)

private fun DaySummary.field(kind: MetricKind, digits: Int): String = num(reading(kind).value, digits)

private fun Metric<Int>.field(): String = value?.toString() ?: ""

private fun Instant.local(zone: ZoneId): String = OFFSET_TIME.format(atZone(zone).withNano(0))

internal fun daysCsv(days: List<DaySummary>): String = csv(
    listOf(
        "date", "recovery_pct", "hrv_ms", "resting_hr", "resp_rate", "sleep_min",
        "sleep_performance_pct", "strain", "kcal", "steps",
    ),
    days.sortedBy { it.date }.map { day ->
        listOf(
            day.date.toString(),
            day.field(MetricKind.RECOVERY, 0),
            day.field(MetricKind.HRV, 1),
            day.field(MetricKind.RESTING_HEART_RATE, 0),
            day.field(MetricKind.RESPIRATORY_RATE, 1),
            day.field(MetricKind.SLEEP_DURATION, 0),
            day.field(MetricKind.SLEEP_PERFORMANCE, 0),
            day.field(MetricKind.STRAIN, 1),
            day.field(MetricKind.CALORIES, 0),
            day.field(MetricKind.STEPS, 0),
        )
    },
)

internal fun workoutsCsv(activities: List<Activity>, zone: ZoneId): String = csv(
    listOf("sport", "start", "end", "duration_min", "strain", "avg_bpm", "max_bpm", "kcal"),
    activities.sortedBy { it.start }.map { a ->
        val activeSeconds = (a.end.epochSecond - a.start.epochSecond - a.pausedSeconds).coerceAtLeast(0)
        listOf(
            a.sport.name,
            a.start.local(zone),
            a.end.local(zone),
            num(activeSeconds / 60.0, 1),
            num(a.strain.value, 1),
            a.averageHeartRateBpm.field(),
            a.maxHeartRateBpm.field(),
            a.calories.field(),
        )
    },
)

/** One row per minute that has a sample, for the last 30 days up to and including [today]. */
internal fun heartRateCsv(days: List<DaySummary>, today: LocalDate, zone: ZoneId): String {
    val first = today.minusDays(HEART_RATE_DAYS - 1)
    val rows = days.filter { !it.date.isBefore(first) && !it.date.isAfter(today) }
        .sortedBy { it.date }
        .flatMap { day ->
            val midnight = day.date.atStartOfDay(zone)
            day.heartRateByMinute.mapIndexedNotNull { index, bpm ->
                bpm?.let { listOf(OFFSET_TIME.format(midnight.plusMinutes(index.toLong())), it.toString()) }
            }
        }
    return csv(listOf("minute", "bpm"), rows)
}

/** The zip the export writes: days.csv, workouts.csv and heart_rate.csv. */
fun csvZip(days: List<DaySummary>, today: LocalDate, zone: ZoneId): ByteArray {
    val files = listOf(
        "days.csv" to daysCsv(days),
        "workouts.csv" to workoutsCsv(days.flatMap { it.activities }.distinctBy { it.id }, zone),
        "heart_rate.csv" to heartRateCsv(days, today, zone),
    )
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, text) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}
