package app.circlet.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.AbsentReason
import app.circlet.core.DayEngine
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.InsightEngine
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.MetricKind
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.TagCorrelation
import app.circlet.core.TrendPoint
import app.circlet.core.WeeklyRecap
import app.circlet.core.emptyDay
import app.circlet.data.codec.DayCodec
import app.circlet.data.db.DaySummaryEntity
import app.circlet.data.db.openMemoryDatabase
import app.circlet.data.importer.IMPORTED_ALGORITHM_VERSION
import app.circlet.data.importer.parseWhoopCycles
import app.circlet.data.repo.MetricsRepositoryImpl
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Twenty imported WHOOP cycles sit before the 60-day window. Fourteen strap rows inside
 * that window have no recovery score. The day being scored must still see the imports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImportBaselineTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 15)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val header = "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm)," +
        "Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm)," +
        "Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min)," +
        "In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min)," +
        "Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %"

    @Test
    fun anImportOlderThanSixtyDaysStillReachesTheStrapDay() = runBlocking {
        val opened = open()
        try {
            strapDays(opened, version = 1)
            val file = File(context.cacheDir, "physiological_cycles.csv")
            file.writeText(cyclesCsv())
            val imported = opened.data.portability.importWhoopExport(file.absolutePath)
            assertTrue(imported.toString(), imported is Outcome.Ok)
            assertEquals(20, (imported as Outcome.Ok).value.imported)
            opened.data.recomputeNow()
            assertImportedHistory(opened.engine.history)
            assertEquals(IMPORTED_ALGORITHM_VERSION, opened.data.database.days().get("2026-01-10")!!.algorithmVersion)
        } finally {
            opened.close()
        }
    }

    @Test
    fun storedImportsCountOnTheNextStartWithoutImportingAgain() = runBlocking {
        val opened = open()
        try {
            val now = opened.time.now().toEpochMilli()
            for (day in parseWhoopCycles(cyclesCsv())) {
                opened.data.database.days().upsert(
                    DaySummaryEntity(day.date.toString(), IMPORTED_ALGORITHM_VERSION, now, DayCodec.encode(day)),
                )
            }
            strapDays(opened, version = 5)
            (opened.data.metrics as MetricsRepositoryImpl).noteStale(today)
            opened.data.recomputeNow()
            assertImportedHistory(opened.engine.history)
            assertEquals(6, opened.data.database.days().get(today.toString())!!.algorithmVersion)
            assertEquals(IMPORTED_ALGORITHM_VERSION, opened.data.database.days().get("2026-01-01")!!.algorithmVersion)
            assertEquals(6, opened.data.database.days().get(today.minusDays(1).toString())!!.algorithmVersion)
        } finally {
            opened.close()
        }
    }

    private fun assertImportedHistory(history: List<DaySummary>?) {
        val days = checkNotNull(history)
        assertTrue(days.count { it.recovery.score.present } >= 14)
        assertTrue(days.any { it.date.isBefore(today.minusDays(60)) })
        val imported = days.single { it.date == LocalDate.of(2026, 1, 10) }
        assertEquals(50, imported.recovery.score.value)
        assertEquals(40.0, imported.heart.hrv.rmssdMs.value!!, 0.0)
        assertEquals(45, imported.heart.resting.bpm.value)
        assertEquals(13.0, imported.heart.respiratoryRate.breathsPerMinute.value!!, 0.0)
        assertEquals(70, imported.sleepPerformance.percent.value)
        val recent = days.single { it.date == today.minusDays(1) }
        assertNull(recent.recovery.score.value)
    }

    private fun strapDays(opened: Opened, version: Int) {
        val now = opened.time.now().toEpochMilli()
        for (ago in 0..14) {
            val date = today.minusDays(ago.toLong())
            val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
            opened.data.database.days().upsert(
                DaySummaryEntity(date.toString(), version, now, DayCodec.encode(day)),
            )
        }
    }

    private fun cyclesCsv(): String {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val rows = (1..20).joinToString("\n") { dayOfMonth ->
            val date = LocalDate.of(2026, 1, dayOfMonth)
            fun at(value: LocalDateTime) = value.format(stamp)
            listOf(
                at(date.minusDays(1).atTime(23, 10)),
                at(date.atTime(23, 0)),
                "UTC",
                40 + dayOfMonth,
                45 + dayOfMonth % 5,
                30 + dayOfMonth,
                "",
                "",
                "",
                "",
                "",
                "",
                at(date.minusDays(1).atTime(23, 20)),
                at(date.atTime(7, 0)),
                60 + dayOfMonth,
                "${(120 + dayOfMonth) / 10}.${(120 + dayOfMonth) % 10}",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            ).joinToString(",")
        }
        return "$header\n$rows\n"
    }

    private suspend fun open(): Opened {
        val time = app.circlet.core.ManualTimeSource(Instant.parse("2026-06-15T12:00:00Z"), zone)
        val engine = HistoryEngine(today)
        val data = DataModule.open(
            context = context,
            dayEngine = engine,
            insightEngine = QuietInsights(),
            time = time,
            database = openMemoryDatabase(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.IO,
            debounceMs = 50,
            startPipeline = false,
        )
        data.awaitReady()
        return Opened(data, engine, time)
    }

    private class Opened(
        val data: CircletData,
        val engine: HistoryEngine,
        val time: app.circlet.core.ManualTimeSource,
    ) {
        fun close() = data.close()
    }

    private class HistoryEngine(private val target: LocalDate) : DayEngine {
        override val algorithmVersion: Int = 6
        var history: List<DaySummary>? = null

        override fun computeDay(input: DayInput): DaySummary {
            if (input.date == target) history = input.history
            return emptyDay(input.date, AbsentReason.NOT_ENOUGH_DATA)
        }
    }
}
