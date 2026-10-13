package app.circlet.data.export

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.Metric
import app.circlet.core.Sport
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {
    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val date = LocalDate.of(2026, 3, 2)

    @Test
    fun `fields with commas quotes or line breaks are quoted`() {
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\",\"x\ny\"", csvLine(listOf("a", "b,c", "say \"hi\"", "x\ny")))
        assertEquals("plain,", csvLine(listOf("plain", "")))
    }

    @Test
    fun `an empty day has empty fields, never zero`() {
        val csv = daysCsv(listOf(emptyDay(date, AbsentReason.NOT_WORN)))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals("date,recovery_pct,hrv_ms,resting_hr,resp_rate,sleep_min,sleep_performance_pct,strain,kcal,steps", lines[0])
        assertEquals("2026-03-02,,,,,,,,,", lines[1])
    }

    @Test
    fun `present metrics are written with fixed decimals`() {
        val base = emptyDay(date, AbsentReason.NOT_WORN)
        val day = base.copy(
            recovery = base.recovery.copy(score = Metric.of(63, Confidence.HIGH)),
            strain = base.strain.copy(score = Metric.of(12.345, Confidence.HIGH)),
            calories = Metric.of(2310, Confidence.MEDIUM),
        )
        assertEquals("2026-03-02,63,,,,,,12.3,2310,", daysCsv(listOf(day)).trimEnd().split("\r\n")[1])
    }

    @Test
    fun `workouts carry local time with offset and active minutes`() {
        val start = Instant.parse("2026-03-02T01:00:00Z")
        val activity = Activity(
            id = "a1",
            source = ActivitySource.MANUAL,
            sport = Sport.RUNNING,
            sportConfidence = null,
            start = start,
            end = start.plusSeconds(1800),
            strain = Metric.of(8.04, Confidence.HIGH),
            averageHeartRateBpm = Metric.of(140, Confidence.HIGH),
            maxHeartRateBpm = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
            calories = Metric.of(300, Confidence.HIGH),
            zoneMinutes = ZoneMinutes.ZERO,
            heartRateRecovery = null,
            pausedSeconds = 300,
        )
        val row = workoutsCsv(listOf(activity), zone).trimEnd().split("\r\n")[1]
        assertEquals("RUNNING,2026-03-02T06:30:00+05:30,2026-03-02T07:00:00+05:30,25.0,8.0,140,,300", row)
    }

    @Test
    fun `heart rate keeps only the last 30 days and skips empty minutes`() {
        val base = emptyDay(date, AbsentReason.NOT_WORN)
        val minutes = List(1440) { if (it == 1) 61 else null }
        val recent = base.copy(heartRateByMinute = minutes)
        val old = recent.copy(date = date.minusDays(30))
        val lines = heartRateCsv(listOf(old, recent), date, zone).trimEnd().split("\r\n")
        assertEquals(listOf("minute,bpm", "2026-03-02T00:01:00+05:30,61"), lines)
    }

    @Test
    fun `the zip holds the three files`() {
        val bytes = csvZip(listOf(emptyDay(date, AbsentReason.NOT_WORN)), date, zone)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("days.csv", "workouts.csv", "heart_rate.csv"), names)
    }
}
