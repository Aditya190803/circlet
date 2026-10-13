package app.circlet.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.AbsentReason
import app.circlet.core.CoreError
import app.circlet.core.DayEngine
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.ManualTimeSource
import app.circlet.core.Outcome
import app.circlet.core.SleepEdit
import app.circlet.core.emptyDay
import app.circlet.data.backup.restoreSnapshot
import app.circlet.data.backup.snapshotBytes
import app.circlet.data.db.CircletMigrations
import app.circlet.data.db.openMemoryDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SleepEditStoreTest {
    private val zone = ZoneId.of("UTC")
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val time = ManualTimeSource(Instant.parse("2026-06-15T12:00:00Z"), zone)
    private val date = LocalDate.of(2026, 6, 15)

    @Test(timeout = 60_000)
    fun migrationTwoToThreeAddsAnEmptySleepEditsTable() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        try {
            CircletMigrations.MIGRATION_2_3.migrate(db)
            db.query("SELECT COUNT(*) FROM sleep_edits").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.execSQL("INSERT INTO sleep_edits VALUES ('2026-06-15', 100, 200, 0, '5,9')")
            db.query("SELECT start_epoch, end_epoch, deleted, rejected_naps FROM sleep_edits").use {
                assertTrue(it.moveToFirst())
                assertEquals(100L, it.getLong(0))
                assertEquals(200L, it.getLong(1))
                assertEquals(0, it.getInt(2))
                assertEquals("5,9", it.getString(3))
            }
            assertTrue(CircletMigrations.missingSteps().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test(timeout = 60_000)
    fun aSavedEditReachesTheEngineAndMarksEveryDateItTouches() = runBlocking {
        val opened = open()
        try {
            val bed = Instant.parse("2026-06-14T23:00:00Z")
            val wake = Instant.parse("2026-06-15T07:00:00Z")
            val nap = Instant.parse("2026-06-15T10:30:00Z")
            val edit = SleepEdit(start = bed, end = wake, rejectedNaps = setOf(nap))
            assertTrue(opened.data.metrics.saveSleepEdit(date, edit) is Outcome.Ok)
            assertEquals(edit, opened.data.metrics.observeSleepEdit(date).first())
            assertEquals(listOf("2026-06-14", "2026-06-15"), opened.data.database.dirty().dates())

            opened.data.recomputeNow()
            assertEquals(edit, opened.engine.seen.single { it.date == date }.sleepEdit)
            assertTrue(opened.data.database.dirty().dates().isEmpty())

            val crossing = SleepEdit(start = Instant.parse("2026-06-14T23:00:00Z"), end = Instant.parse("2026-06-15T06:00:00Z"))
            assertTrue(opened.data.metrics.saveSleepEdit(LocalDate.of(2026, 6, 14), crossing) is Outcome.Ok)
            assertEquals(listOf("2026-06-14", "2026-06-15"), opened.data.database.dirty().dates())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun aDeletedNightRoundTripsAndAnEmptyEditRestoresDetection() = runBlocking {
        val opened = open()
        try {
            val metrics = opened.data.metrics
            assertTrue(metrics.saveSleepEdit(date, SleepEdit(deleted = true)) is Outcome.Ok)
            assertEquals(SleepEdit(deleted = true), metrics.observeSleepEdit(date).first())
            assertTrue(metrics.saveSleepEdit(date, SleepEdit()) is Outcome.Ok)
            assertNull(metrics.observeSleepEdit(date).first())
            assertNull(opened.data.database.sleepEdits().get(date.toString()))
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun anUnusableWindowIsRejectedAndNothingIsStored() = runBlocking {
        val opened = open()
        try {
            val metrics = opened.data.metrics
            val bed = Instant.parse("2026-06-15T01:00:00Z")
            assertEquals(
                "Wake time has to be after bed time.",
                invalid(metrics.saveSleepEdit(date, SleepEdit(start = bed, end = bed))),
            )
            assertEquals(
                "Wake time cannot be in the future.",
                invalid(metrics.saveSleepEdit(date, SleepEdit(start = bed, end = time.now().plusSeconds(1)))),
            )
            assertEquals(
                "Sleep has to be shorter than 24 hours.",
                invalid(metrics.saveSleepEdit(date, SleepEdit(start = time.now().minusSeconds(24 * 3600), end = time.now()))),
            )
            assertNull(opened.data.database.sleepEdits().get(date.toString()))
            assertTrue(opened.data.database.dirty().dates().isEmpty())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun editsSurviveBackupAndAnOlderSnapshotRestoresWithoutThem() = runBlocking {
        val opened = open()
        try {
            val dao = opened.data.database.sleepEdits()
            opened.data.metrics.saveSleepEdit(date, SleepEdit(deleted = true))
            val withEdits = snapshotBytes(opened.data.database, context)

            dao.delete(date.toString())
            restoreSnapshot(opened.data.database, withEdits)
            assertTrue(dao.get(date.toString())!!.deleted)

            restoreSnapshot(opened.data.database, withoutSleepEdits(withEdits))
            assertNull(dao.get(date.toString()))
        } finally {
            opened.close()
        }
    }

    private fun withoutSleepEdits(bytes: ByteArray): ByteArray {
        val file = File.createTempFile("circlet-old", ".db")
        try {
            file.writeBytes(bytes)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE sleep_edits")
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun invalid(outcome: Outcome<*>): String = ((outcome as Outcome.Err).error as CoreError.Invalid).detail

    private suspend fun open(): Opened {
        val engine = SeeingEngine()
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
        return Opened(data, engine)
    }

    private class Opened(val data: CircletData, val engine: SeeingEngine) {
        fun close() = data.close()
    }

    private class SeeingEngine : DayEngine {
        override val algorithmVersion: Int = 1
        val seen = ArrayList<DayInput>()

        override fun computeDay(input: DayInput): DaySummary {
            seen += input
            return emptyDay(input.date, AbsentReason.NOT_ENOUGH_DATA)
        }
    }
}
