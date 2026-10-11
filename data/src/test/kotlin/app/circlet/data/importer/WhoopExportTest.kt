package app.circlet.data.importer

import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopExportTest {
    private val header = "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm)," +
        "Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm)," +
        "Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min)," +
        "In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min)," +
        "Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %"

    private val full = "2026-09-30 23:10:00,2026-10-01 23:40:00,UTC+05:30,67,52,61,33.4,96.5,12.4,2310,171,68," +
        "2026-09-30 23:10:00,2026-10-01 06:55:00,88,14.2,421,465,220,95,106,44,478,35,91,82"
    private val noSleep = "2026-10-01 23:40:00,2026-10-02 23:00:00,UTC+05:30,,,,,,8.1,1900,140,70,,,,,,,,,,,,,,"
    private val blank = "2026-10-03 23:00:00,,UTC+05:30,,,,,,,,,,,,,,,,,,,,,,,"

    @Test
    fun aFullRowBecomesTheDayTheUserWokeOn() {
        val day = parseWhoopCycles("$header\n$full").single()
        assertEquals(LocalDate.of(2026, 10, 1), day.date)
        assertEquals(67, day.recovery.score.value)
        assertEquals(52, day.heart.resting.bpm.value)
        assertEquals(61.0, day.heart.hrv.rmssdMs.value!!, 0.0)
        assertEquals(14.2, day.heart.respiratoryRate.breathsPerMinute.value!!, 0.0)
        assertEquals(12.4, day.strain.score.value!!, 0.0)
        assertEquals(2310, day.calories.value)
        assertEquals(33.4, day.skinTemperature!!.importedCelsius!!, 0.0)
        val sleep = day.sleep.value!!
        assertEquals(Duration.ofMinutes(421), sleep.timeAsleep)
        assertEquals(Duration.ofMinutes(465), sleep.timeInBed)
        assertEquals(91, sleep.efficiency.value)
        // 23:10 at +05:30 is 17:40 UTC.
        assertEquals("2026-09-30T17:40:00Z", sleep.start.toString())
        assertEquals(88, day.sleepPerformance.percent.value)
        assertEquals(Duration.ofMinutes(478), day.sleepNeed.duration.value)
        assertEquals(82.0, day.sleepConsistency.index.value!!, 0.0)
    }

    @Test
    fun missingValuesStayAbsentAndEmptyRowsAreDropped() {
        val days = parseWhoopCycles("$header\n$full\n$noSleep\n$blank")
        assertEquals(2, days.size)
        val second = days[1]
        assertEquals(LocalDate.of(2026, 10, 2), second.date)
        assertNull(second.sleep.value)
        assertNull(second.recovery.score.value)
        assertEquals(8.1, second.strain.score.value!!, 0.0)
    }

    @Test
    fun columnsAreFoundByNameWhateverTheirOrder() {
        val csv = "Day Strain,Cycle start time,Recovery score %\n9.5,2026-10-05 22:00:00,40"
        val day = parseWhoopCycles(csv).single()
        assertEquals(LocalDate.of(2026, 10, 6), day.date)
        assertEquals(40, day.recovery.score.value)
        assertEquals(9.5, day.strain.score.value!!, 0.0)
    }

    @Test
    fun theCyclesFileIsFoundInsideTheExportZip() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("my_whoop_data/workouts.csv"))
                zip.write("Workout start time\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("my_whoop_data/physiological_cycles.csv"))
                zip.write("$header\n$full".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val csv = cyclesCsvFrom(bytes)
        assertNotNull(csv)
        assertEquals(1, parseWhoopCycles(csv!!).size)
        assertNotNull(cyclesCsvFrom("$header\n$full".toByteArray()))
        assertNull(cyclesCsvFrom("not,a,whoop,file\n1,2,3,4".toByteArray()))
        assertTrue(parseWhoopCycles("").isEmpty())
    }

    @Test
    fun quotedFieldsAreSplitCorrectly() {
        assertEquals(listOf("a", "b,c", "say \"hi\"", ""), splitCsvLine("a,\"b,c\",\"say \"\"hi\"\"\","))
    }
}
