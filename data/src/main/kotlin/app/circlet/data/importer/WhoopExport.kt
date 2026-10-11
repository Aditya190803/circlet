package app.circlet.data.importer

import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.Metric
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.SleepConsistency
import app.circlet.core.SleepDebt
import app.circlet.core.SleepKind
import app.circlet.core.SleepNeed
import app.circlet.core.SleepPerformance
import app.circlet.core.SleepSession
import app.circlet.core.emptyDay
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

// Reads the "physiological_cycles.csv" file from WHOOP's own data export (Settings, Data
// export in the official app), either on its own or inside the export zip. Each row is
// one day as WHOOP scored it. Columns are matched by name, so a reordered or extended
// export still reads; a value that is blank or does not parse is left absent.

/** Marks a stored day that came from an import and has no samples to recompute from. */
const val IMPORTED_ALGORITHM_VERSION: Int = -1

private const val CYCLES_FILE = "physiological_cycles.csv"
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The cycles CSV text from an export zip or a bare CSV, or null when neither holds one. */
fun cyclesCsvFrom(bytes: ByteArray): String? {
    val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    if (!isZip) {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        return if (text.lineSequence().firstOrNull()?.contains("Cycle start time", ignoreCase = true) == true) text else null
    }
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (!entry.isDirectory && entry.name.substringAfterLast('/').equals(CYCLES_FILE, ignoreCase = true)) {
                return zip.readBytes().toString(Charsets.UTF_8).removePrefix("﻿")
            }
        }
    }
}

/** One CSV record split into fields, honouring quotes and doubled quotes. */
internal fun splitCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                out += field.toString()
                field.setLength(0)
            }
            else -> field.append(c)
        }
        i++
    }
    out += field.toString()
    return out
}

private fun zoneOf(text: String?): ZoneId {
    val trimmed = text?.trim().orEmpty().removePrefix("UTC").removePrefix("GMT")
    return runCatching { if (trimmed.isEmpty() || trimmed == "Z") ZoneOffset.UTC else ZoneOffset.of(trimmed) }
        .getOrDefault(ZoneOffset.UTC)
}

private fun stamp(text: String?): LocalDateTime? =
    text?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it, STAMP) }.getOrNull() }

/**
 * The days in a cycles CSV, oldest first. A day belongs to the date the user woke on,
 * as WHOOP attributes it; a cycle with no sleep uses the date twelve hours after it began.
 * When two cycles land on one date, the later row wins.
 */
fun parseWhoopCycles(csv: String): List<DaySummary> {
    val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size < 2) return emptyList()
    val header = splitCsvLine(lines.first()).map { it.trim().lowercase() }
    fun column(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
        header.indexOfFirst { it.startsWith(name) }.takeIf { it >= 0 }
    } ?: -1

    val cStart = column("cycle start time")
    val cZone = column("cycle timezone")
    val cRecovery = column("recovery score")
    val cResting = column("resting heart rate")
    val cHrv = column("heart rate variability")
    val cSkin = column("skin temp")
    val cStrain = column("day strain")
    val cEnergy = column("energy burned")
    val cMaxHr = column("max hr")
    val cAvgHr = column("average hr")
    val cOnset = column("sleep onset")
    val cWake = column("wake onset")
    val cPerformance = column("sleep performance")
    val cResp = column("respiratory rate")
    val cAsleep = column("asleep duration")
    val cInBed = column("in bed duration")
    val cNeed = column("sleep need")
    val cDebt = column("sleep debt")
    val cEfficiency = column("sleep efficiency")
    val cConsistency = column("sleep consistency")
    if (cStart < 0) return emptyList()

    val days = LinkedHashMap<LocalDate, DaySummary>()
    for (line in lines.drop(1)) {
        val cells = splitCsvLine(line)
        fun text(index: Int): String? = cells.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
        fun number(index: Int): Double? = text(index)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        fun <T> metric(value: T?): Metric<T> =
            if (value != null) Metric.of(value, Confidence.MEDIUM) else Metric.absent(AbsentReason.NOT_ENOUGH_DATA)

        val start = stamp(text(cStart)) ?: continue
        val zone = zoneOf(text(cZone))
        val onset = stamp(text(cOnset))
        val wake = stamp(text(cWake))
        val date = (wake ?: start.plusHours(12)).toLocalDate()

        val recovery = number(cRecovery)?.roundToInt()?.takeIf { it in 0..100 }
        val strain = number(cStrain)?.takeIf { it in 0.0..21.0 }
        val asleep = number(cAsleep)?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) }
        if (recovery == null && strain == null && asleep == null) continue

        val base = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA, CalibrationStatus.READY)
        val session = if (asleep != null && onset != null && wake != null && wake.isAfter(onset)) {
            SleepSession(
                id = "whoop-$date",
                kind = SleepKind.MAIN,
                start = onset.atZone(zone).toInstant(),
                end = wake.atZone(zone).toInstant(),
                epochs = emptyList(),
                cycles = emptyList(),
                latency = Duration.ZERO,
                efficiency = metric(number(cEfficiency)?.roundToInt()?.takeIf { it in 0..100 }),
                wakeEvents = emptyList(),
                timeInBed = number(cInBed)?.let { Duration.ofMinutes(it.toLong()) } ?: Duration.between(onset, wake),
                timeAsleep = asleep,
            )
        } else {
            null
        }
        val skinCelsius = number(cSkin)?.takeIf { it in 20.0..45.0 }
        days[date] = base.copy(
            skinTemperature = skinCelsius?.let {
                SkinTemperature(
                    deviation = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                    baselineNights = 0,
                    flag = SkinTempFlag.NOT_ENOUGH_BASELINE,
                    importedCelsius = it,
                )
            },
            sleep = if (session != null) Metric.of(session, Confidence.MEDIUM) else Metric.absent(AbsentReason.NO_SLEEP),
            recovery = base.recovery.copy(score = metric(recovery)),
            strain = base.strain.copy(score = metric(strain)),
            heart = base.heart.copy(
                resting = RestingHeartRate(metric(number(cResting)?.roundToInt()?.takeIf { it in 25..120 })),
                hrv = base.heart.hrv.copy(rmssdMs = metric(number(cHrv)?.takeIf { it in 1.0..400.0 })),
                respiratoryRate = RespiratoryRate(metric(number(cResp)?.takeIf { it in 4.0..40.0 })),
                averageBpm = metric(number(cAvgHr)?.roundToInt()?.takeIf { it in 25..230 }),
                maxBpm = metric(number(cMaxHr)?.roundToInt()?.takeIf { it in 25..230 }),
            ),
            calories = metric(number(cEnergy)?.roundToInt()?.takeIf { it > 0 }),
            sleepNeed = SleepNeed(metric(number(cNeed)?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) }), null),
            sleepDebt = SleepDebt(metric(number(cDebt)?.takeIf { it >= 0 }?.let { Duration.ofMinutes(it.toLong()) })),
            sleepPerformance = SleepPerformance(metric(number(cPerformance)?.roundToInt()?.takeIf { it in 0..100 })),
            sleepConsistency = SleepConsistency(metric(number(cConsistency)?.takeIf { it in 0.0..100.0 })),
        )
    }
    return days.values.sortedBy { it.date }
}
