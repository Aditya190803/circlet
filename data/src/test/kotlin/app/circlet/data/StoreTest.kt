package app.circlet.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.DayEngine
import app.circlet.core.DayInput
import app.circlet.core.DaySummary
import app.circlet.core.DoubleTapAction
import app.circlet.core.GravityVector
import app.circlet.core.InsightEngine
import app.circlet.core.JournalEntry
import app.circlet.core.JournalTag
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.OpticalChannels
import app.circlet.core.Outcome
import app.circlet.core.PersonalRecord
import app.circlet.core.Profile
import app.circlet.core.RawRecord
import app.circlet.core.Sample
import app.circlet.core.Sex
import app.circlet.core.SleepEpoch
import app.circlet.core.SleepKind
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.Sport
import app.circlet.core.StrapAlarm
import app.circlet.core.StrapEvent
import app.circlet.core.TagCorrelation
import app.circlet.core.TrendPoint
import app.circlet.core.UserSettings
import app.circlet.core.WeeklyRecap
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import app.circlet.data.codec.toSample
import app.circlet.data.codec.DayCodec
import app.circlet.data.db.CircletMigrations
import app.circlet.data.db.Keys
import app.circlet.data.db.openMemoryDatabase
import app.circlet.data.db.openNamedDatabase
import app.circlet.data.repo.MetricsRepositoryImpl
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreTest {
    private val zone = ZoneId.of("UTC")
    private val time = app.circlet.core.ManualTimeSource(Instant.parse("2026-06-15T12:00:00Z"), zone)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test(timeout = 60_000)
    fun gen5SamplesAndPacketKindsRoundTripWithoutMixingTemperatureUnits() = runBlocking {
        val opened = open()
        try {
            val epoch = time.now().epochSecond
            val row = sample(epoch, 70, listOf(800)).copy(
                generation = app.circlet.core.StrapGeneration.GEN5,
                skinTemperatureCentiC = 3057,
                bandState = 2,
            )
            val raws = listOf(18, 26).map { version ->
                RawRecord(epoch, byteArrayOf(0x2F, version.toByte(), 0, 1, 0, 0, 0), app.circlet.core.StrapGeneration.GEN5)
            }
            opened.data.ports.samples.commit(listOf(row), raws)
            val stored = opened.data.database.samples().range(epoch, epoch).single()
            val decoded = stored.toSample()
            assertEquals(row.copy(rawRecord = null), decoded)
            assertNull(stored.skinTempCount)
            opened.data.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_records WHERE epochSecond = $epoch").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        } finally { opened.close() }
    }

    @Test(timeout = 60_000)
    fun pairedGenerationSurvivesReopeningAndCanBeCleared() = runBlocking {
        val filename = "gen5-pairing-${System.nanoTime()}.db"
        val first = open(fileName = filename)
        first.data.ports.prefs.setPairedAddress("AA:BB:CC:DD:EE:01")
        first.data.ports.prefs.setPairedGeneration(app.circlet.core.StrapGeneration.GEN5)
        first.close()
        val second = open(fileName = filename)
        try {
            assertEquals(app.circlet.core.StrapGeneration.GEN5, second.data.ports.prefs.pairedGeneration())
            second.data.ports.prefs.setPairedGeneration(null)
            assertNull(second.data.ports.prefs.pairedGeneration())
        } finally { second.close() }
    }

    @Test(timeout = 60_000)
    fun migrationThreeToFourKeepsLegacyCountsAndRawBytes() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE samples (epochSecond INTEGER PRIMARY KEY NOT NULL, skinTempCount INTEGER)")
                    db.execSQL("CREATE TABLE raw_records (epochSecond INTEGER PRIMARY KEY NOT NULL, bytes BLOB NOT NULL)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO samples VALUES (1000, 935)")
            db.execSQL("INSERT INTO raw_records VALUES (1000, X'2F18')")
            CircletMigrations.MIGRATION_3_4.migrate(db)
            db.query("SELECT skinTempCount, generation, skinTemperatureCentiC, bandState FROM samples").use {
                assertTrue(it.moveToFirst())
                assertEquals(935, it.getInt(0))
                assertEquals("GEN4", it.getString(1))
                assertTrue(it.isNull(2)); assertTrue(it.isNull(3))
            }
            db.query("SELECT archiveKey, bytes FROM raw_records").use {
                assertTrue(it.moveToFirst())
                assertEquals("1000", it.getString(0))
                assertTrue(byteArrayOf(0x2F, 0x18).contentEquals(it.getBlob(1)))
            }
        } finally { helper.close() }
    }

    @Test(timeout = 60_000)
    fun rangeReadUsesThePrimaryKey() {
        val db = openMemoryDatabase(context)
        try {
            val cursor = db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM samples WHERE epochSecond BETWEEN 0 AND 10 ORDER BY epochSecond",
            )
            val plan = buildString {
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) append(cursor.getString(column)).append(' ')
                }
            }
            cursor.close()
            assertTrue(plan, plan.contains("PRIMARY KEY") || plan.contains("INDEX"))
            assertEquals("circlet", db.kv().get(Keys.SCHEMA))
            assertEquals("14", db.kv().get(Keys.RAW_RETENTION))
            assertTrue(db.journal().tags().any { it.id == "alcohol" && it.builtIn })
        } finally {
            db.close()
        }
    }

    @Test(timeout = 180_000)
    fun fullDayBatchedInsertAndRangeRead() = runBlocking {
        val opened = open()
        try {
            val date = LocalDate.of(2026, 6, 14)
            val start = date.atStartOfDay(zone).toEpochSecond()
            val samples = List(86_400) { index ->
                sample(start + index, heart = 50 + index % 15, rr = if (index == 12_345) listOf(812, 906) else emptyList())
            }
            opened.data.ports.samples.commit(samples, emptyList())
            val db = opened.data.database
            assertEquals(86_400L, db.samples().count(start, start + 86_399))
            val all = db.samples().range(start, start + 86_399)
            assertEquals(86_400, all.size)
            assertEquals(start, all.first().epochSecond)
            assertEquals(start + 86_399, all.last().epochSecond)
            assertEquals(listOf(812, 906), app.circlet.data.codec.unpackRr(all[12_345].rr))
            // Midnight through 23:59 includes the 18:00 boundary, so the next morning is dirty too.
            assertEquals(setOf(date.toString(), date.plusDays(1).toString()), db.dirty().dates().toSet())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun commitIsDurableAndKeepsTheExplicitRawBytes() = runBlocking {
        val opened = open()
        try {
            val epoch = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(zone).toEpochSecond()
            val row = sample(epoch, 61, listOf(1000)).copy(rawRecord = listOf(1, 2, 3))
            opened.data.ports.samples.commit(listOf(row), listOf(RawRecord(epoch, byteArrayOf(9, 9))))
            val db = opened.data.database
            assertEquals(1L, db.samples().count(epoch, epoch))
            assertTrue(byteArrayOf(9, 9).contentEquals(db.rawRecords().bytes(epoch)))
            opened.data.ports.events.record(StrapEvent.Battery(time.now(), 80.0))
            assertEquals("battery", db.strapEvents().all().single().kind)
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun recomputeRunsOldestFirstAndKeepsUserEdits() = runBlocking {
        val opened = open()
        try {
            val dates = listOf(LocalDate.of(2026, 6, 13), LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 12))
            val samples = dates.map { sample(it.atTime(12, 0).atZone(zone).toEpochSecond(), 70, emptyList()) }
            opened.data.ports.samples.commit(samples, emptyList())
            opened.data.recomputeNow()
            assertEquals(dates.sorted(), opened.engine.calls.toList())

            val kept = LocalDate.of(2026, 6, 11)
            val auto = opened.data.ports.workouts.get("auto-$kept")
            assertNotNull(auto)
            opened.data.ports.workouts.update(auto!!.copy(sport = Sport.CYCLING))
            opened.engine.version = 2
            (opened.data.metrics as MetricsRepositoryImpl).noteStale(kept)
            assertTrue(opened.data.database.dirty().dates().contains(kept.toString()))
            opened.data.recomputeNow()
            val edited = opened.data.ports.workouts.get("auto-$kept")
            assertEquals(Sport.CYCLING, edited!!.sport)
            assertEquals(2, opened.data.database.days().get(kept.toString())!!.algorithmVersion)

            opened.data.ports.workouts.delete("auto-$kept")
            opened.data.recomputeNow()
            assertNull(opened.data.ports.workouts.get("auto-$kept"))

            val manual = manual(LocalDate.of(2026, 6, 12))
            opened.data.ports.workouts.insert(manual)
            opened.data.recomputeNow()
            assertTrue(opened.engine.manuals.contains(manual.id))
            assertEquals(Sport.YOGA, opened.data.ports.workouts.get(manual.id)!!.sport)
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun throwingEngineDoesNotSpin() = runBlocking {
        val opened = open()
        try {
            opened.engine.fail = true
            val date = LocalDate.of(2026, 6, 15)
            opened.data.ports.samples.commit(listOf(sample(date.atTime(12, 0).atZone(zone).toEpochSecond(), 60, emptyList())), emptyList())
            opened.data.recomputeNow()
            assertEquals(0, opened.engine.calls.size)
            assertTrue(opened.data.database.dirty().dates().isNotEmpty())
            opened.engine.fail = false
            opened.data.recomputeNow()
            assertEquals(listOf(date), opened.engine.calls.toList())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 30_000)
    fun debounceCollapsesABurst() = runBlocking {
        val opened = open(debounceMs = 1_500, startPipeline = true)
        try {
            val first = LocalDate.of(2026, 6, 10)
            val second = LocalDate.of(2026, 6, 12)
            opened.data.ports.samples.commit(listOf(noon(first)), emptyList())
            opened.data.ports.samples.commit(listOf(noon(second)), emptyList())
            Thread.sleep(200)
            assertTrue(opened.engine.calls.isEmpty())
            val deadline = System.nanoTime() + 8_000_000_000L
            while (opened.engine.calls.size < 2 && System.nanoTime() < deadline) Thread.sleep(40)
            assertEquals(listOf(first, second), opened.engine.calls.toList())
            Thread.sleep(400)
            assertEquals(2, opened.engine.calls.size)
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 30_000)
    fun aDrainKeepsRecomputeWaitingUntilSampleCommitsGoQuiet() = runBlocking {
        val opened = open(debounceMs = 50, startPipeline = true, sampleQuietMs = 1_200)
        try {
            val day = LocalDate.of(2026, 6, 10)
            repeat(4) {
                opened.data.ports.samples.commit(listOf(noon(day)), emptyList())
                Thread.sleep(400)
            }
            assertTrue(opened.engine.calls.isEmpty())
            val deadline = System.nanoTime() + 8_000_000_000L
            while (opened.engine.calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(40)
            assertEquals(listOf(day), opened.engine.calls.toList())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun retentionDropsRawRecordsBeforeSamples() = runBlocking {
        val opened = open()
        try {
            val recent = time.now().epochSecond
            val old = time.now().minus(Duration.ofDays(20)).epochSecond
            opened.data.ports.samples.commit(
                listOf(sample(recent, 60, emptyList()), sample(old, 60, emptyList())),
                listOf(RawRecord(recent, byteArrayOf(1)), RawRecord(old, byteArrayOf(2))),
            )
            opened.data.setRetention(sampleDays = null, rawDays = 1)
            assertNotNull(opened.data.database.rawRecords().bytes(recent))
            assertNull(opened.data.database.rawRecords().bytes(old))
            assertEquals(1L, opened.data.database.samples().count(old, old))
            opened.data.setRetention(sampleDays = 1, rawDays = 14)
            assertEquals(0L, opened.data.database.samples().count(old, old))
            assertEquals(1L, opened.data.database.samples().count(recent, recent))
            assertEquals(14L, opened.data.rawRetentionDays())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun repositoriesPersistProfileJournalAlarmsAndSettings() = runBlocking {
        val opened = open()
        try {
            val data = opened.data
            assertTrue(data.journal.deleteTag("alcohol") is Outcome.Err)
            assertTrue(data.journal.saveTag(JournalTag("sauna", "Sauna", builtIn = false)) is Outcome.Ok)
            val today = LocalDate.of(2026, 6, 15)
            assertTrue(data.journal.saveEntry(JournalEntry(today, setOf("sauna"), "hot")) is Outcome.Ok)
            assertEquals("hot", data.database.journal().entry(today.toString())!!.note)
            val bad = Profile(null, 0, Sex.MALE, 180.0, 75.0, null, emptyList())
            assertTrue(data.profile.update(bad) is Outcome.Err)
            val good = Profile(null, 32, Sex.MALE, 180.0, 75.0, null, emptyList())
            assertTrue(data.profile.update(good) is Outcome.Ok)
            val settings = data.settings.settings.value.copy(doubleTap = DoubleTapAction.MARK_MOMENT)
            data.settings.update(settings)
            assertEquals(DoubleTapAction.MARK_MOMENT, data.ports.doubleTap.current())
            val load = data.ports.liveLoad.load(Duration.ofMinutes(30), List(1_800) { 160 })
            assertTrue(load.strain.value!! > 0.0)
            assertNotNull(load.calories.value)
            data.ports.prefs.setPairedAddress("AA:BB:CC:DD:EE:FF")
            data.ports.prefs.setStrapName("Band")
            assertEquals("AA:BB:CC:DD:EE:FF", data.ports.prefs.pairedAddress())
            assertEquals("Band", data.ports.prefs.strapName())
            val alarm = StrapAlarm("am", LocalTime.of(13, 0), java.time.DayOfWeek.values().toSet(), Duration.ofMinutes(10), true)
            assertTrue(data.alarms.set(alarm) is Outcome.Ok)
            assertEquals(alarm.id, data.alarms.next.value!!.alarm.id)
            assertEquals(listOf(alarm), data.ports.prefs.alarms())
            assertTrue(data.onboarding.setCompleted(true) is Outcome.Ok)
            assertEquals("true", data.database.kv().get(Keys.ONBOARDING))
            val stopped = data.workouts.start(Sport.YOGA).let {
                time.advance(Duration.ofMinutes(25))
                data.workouts.stop()
            }
            assertTrue(stopped is Outcome.Ok)
            assertEquals(Sport.YOGA, (stopped as Outcome.Ok).value.sport)
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun alarmsRemoveDeletesTheRow() = runBlocking {
        val opened = open()
        try {
            val data = opened.data
            val first = StrapAlarm("one", LocalTime.of(6, 30), setOf(java.time.DayOfWeek.MONDAY), Duration.ZERO, true)
            val second = StrapAlarm("two", LocalTime.of(7, 30), setOf(java.time.DayOfWeek.MONDAY), Duration.ZERO, true)
            assertTrue(data.alarms.set(first) is Outcome.Ok)
            assertTrue(data.alarms.set(second) is Outcome.Ok)
            assertTrue(data.alarms.remove("missing") is Outcome.Err)
            assertTrue(data.alarms.remove("one") is Outcome.Ok)
            assertEquals(listOf("two"), data.alarms.alarms.value.map { it.id })
            assertEquals(listOf("two"), data.ports.prefs.alarms().map { it.id })
            assertNull(data.database.alarms().get("one"))
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun eveningSampleMarksTwoDays() = runBlocking {        val opened = open()
        try {
            val date = LocalDate.of(2026, 6, 15)
            val epoch = date.atTime(18, 0).atZone(zone).toEpochSecond()
            opened.data.ports.samples.commit(listOf(sample(epoch, 55, emptyList())), emptyList())
            assertEquals(setOf(date.toString(), date.plusDays(1).toString()), opened.data.database.dirty().dates().toSet())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 90_000)
    fun exportBackupAndRestoreRoundTrip() = runBlocking {
        val name = "circlet-roundtrip-${System.nanoTime()}.db"
        val opened = open(fileName = name)
        try {
            val data = opened.data
            val epoch = time.now().epochSecond
            data.ports.samples.commit(listOf(sample(epoch, 77, listOf(1000))), listOf(RawRecord(epoch, byteArrayOf(4, 5))))
            data.recomputeNow()
            val folder = context.cacheDir.resolve("backups-${System.nanoTime()}")
            folder.mkdirs()
            val configured = data.portability.updateBackup(
                app.circlet.core.BackupSettings(true, Duration.ofMinutes(15), folder.absolutePath, null),
            )
            assertTrue(configured is Outcome.Ok)
            assertTrue(data.runDueBackup() is Outcome.Ok)
            repeat(7) {
                time.advance(Duration.ofMinutes(16))
                assertTrue(data.runDueBackup() is Outcome.Ok)
            }
            val kept = folder.listFiles().orEmpty().filter { it.name.startsWith("circlet-backup-") }
            assertEquals(7, kept.size)
            val before = data.portability.backup.value.lastBackup
            assertTrue(data.runDueBackup() is Outcome.Ok)
            assertEquals(before, data.portability.backup.value.lastBackup)

            val exported = data.portability.exportDatabase()
            assertTrue(exported is Outcome.Ok)
            data.ports.samples.commit(listOf(sample(epoch, 10, emptyList())), emptyList())
            assertEquals(10, data.database.samples().range(epoch, epoch).single().heartRate)
            val restored = data.portability.restore((exported as Outcome.Ok).value.uri)
            assertTrue(restored.toString(), restored is Outcome.Ok)
            assertEquals(77, data.database.samples().range(epoch, epoch).single().heartRate)
            assertTrue(byteArrayOf(4, 5).contentEquals(data.database.rawRecords().bytes(epoch)))

            val bad = java.io.File(context.cacheDir, "not-a-db-${System.nanoTime()}")
            bad.writeBytes(ByteArray(40) { 1 })
            val rejected = data.portability.restore(bad.toURI().toString())
            assertTrue(rejected is Outcome.Err)
            assertEquals(77, data.database.samples().range(epoch, epoch).single().heartRate)
        } finally {
            opened.close()
            context.deleteDatabase(name)
        }
    }

    @Test(timeout = 60_000)
    fun replayingABatchReplacesTheSameSecond() = runBlocking {
        val opened = open()
        try {
            val epoch = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(zone).toEpochSecond()
            opened.data.ports.samples.commit(listOf(sample(epoch, 60, listOf(800))), emptyList())
            opened.data.ports.samples.commit(listOf(sample(epoch, 72, listOf(900))), emptyList())
            val rows = opened.data.database.samples().range(epoch, epoch)
            assertEquals(1, rows.size)
            assertEquals(72, rows.single().heartRate)
            assertEquals(2L, opened.data.database.dirty().generation(LocalDate.of(2026, 6, 15).toString()))
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun emptyStoreObservesAnAbsentDay() = runBlocking {
        val opened = open()
        try {
            val day = opened.data.metrics.observeDay(LocalDate.of(2026, 6, 15)).first()
            assertEquals(AbsentReason.NOT_ENOUGH_DATA, day.sleep.absentReason)
            assertTrue(day.activities.isEmpty())
            assertTrue(day.heartRateByMinute.isEmpty())
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun zoneChangeDirtiesTheDaysSamplesCanMoveTo() = runBlocking {
        val opened = open()
        try {
            val date = LocalDate.of(2026, 6, 15)
            val far = LocalDate.of(2026, 1, 2)
            val epoch = far.atTime(2, 0).atZone(zone).toEpochSecond()
            opened.data.ports.samples.commit(listOf(sample(epoch, 60, emptyList())), emptyList())
            opened.data.database.days().upsert(
                app.circlet.data.db.DaySummaryEntity(
                    date.toString(),
                    1,
                    time.now().toEpochMilli(),
                    DayCodec.encode(emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)),
                ),
            )
            for (iso in opened.data.database.dirty().dates()) {
                val generation = opened.data.database.dirty().generation(iso) ?: continue
                opened.data.database.dirty().clear(iso, generation)
            }
            opened.data.noteZone(ZoneId.of("UTC"))
            assertTrue(opened.data.database.dirty().dates().isEmpty())
            assertEquals("UTC", opened.data.database.kv().get(Keys.ZONE))
            opened.data.noteZone(ZoneId.of("America/New_York"))
            val dirty = opened.data.database.dirty().dates().toSet()
            assertTrue(dirty.contains(date.toString()))
            assertTrue(dirty.contains(date.minusDays(1).toString()))
            assertTrue(dirty.contains(date.plusDays(1).toString()))
            assertTrue(dirty.contains(far.toString()))
            assertTrue(dirty.contains(far.minusDays(1).toString()))
            val generation = opened.data.database.dirty().generation(date.toString())
            opened.data.noteZone(ZoneId.of("America/New_York"))
            assertEquals(generation, opened.data.database.dirty().generation(date.toString()))
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun migrationOneToTwoKeepsSamplesAndAddsTheSkinTempColumn() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `samples` (`epochSecond` INTEGER PRIMARY KEY NOT NULL, " +
                            "`heartRate` INTEGER, `rr` BLOB, `gravity` BLOB, `green` INTEGER, " +
                            "`red` INTEGER, `infrared` INTEGER, `ambient` INTEGER)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = factory.create(config)
        val db = helper.writableDatabase
        try {
            db.execSQL("INSERT INTO samples (epochSecond, heartRate) VALUES (1000, 60)")
            CircletMigrations.MIGRATION_1_2.migrate(db)
            val cursor = db.query("SELECT epochSecond, heartRate, skinTempCount FROM samples")
            assertTrue(cursor.moveToFirst())
            assertEquals(1000L, cursor.getLong(0))
            assertEquals(60, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            cursor.close()
            assertTrue(CircletMigrations.missingSteps().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test(timeout = 60_000)
    fun committedSkinTempCountsRoundTrip() = runBlocking {
        val opened = open()
        try {
            val epoch = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(zone).toEpochSecond()
            opened.data.ports.samples.commit(listOf(sample(epoch, 60, emptyList()).copy(skinTempCount = 935)), emptyList())
            val rows = opened.data.database.samples().range(epoch, epoch)
            assertEquals(935, rows.single().skinTempCount)
            opened.data.ports.samples.commit(listOf(sample(epoch + 1, 60, emptyList())), emptyList())
            val missing = opened.data.database.samples().range(epoch + 1, epoch + 1)
            assertNull(missing.single().skinTempCount)
        } finally {
            opened.close()
        }
    }

    @Test(timeout = 60_000)
    fun storedSleepStageFollowsTheEpoch() = runBlocking {        val opened = open()
        try {
            val start = time.now().minus(Duration.ofMinutes(20))
            val session = SleepSession(
                id = "nap",
                kind = SleepKind.NAP,
                start = start,
                end = time.now().plus(Duration.ofMinutes(20)),
                epochs = listOf(SleepEpoch(start, SleepStage.LIGHT, Duration.ofMinutes(40))),
                cycles = emptyList(),
                latency = Duration.ZERO,
                efficiency = Metric.of(100, Confidence.LOW),
                wakeEvents = emptyList(),
                timeInBed = Duration.ofMinutes(40),
                timeAsleep = Duration.ofMinutes(40),
            )
            val date = time.now().atZone(zone).toLocalDate()
            val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(naps = listOf(session))
            opened.data.database.days().upsert(
                app.circlet.data.db.DaySummaryEntity(date.toString(), 1, time.now().toEpochMilli(), DayCodec.encode(day)),
            )
            assertEquals(SleepStage.LIGHT, opened.data.ports.sleepStage.current())
        } finally {
            opened.close()
        }
    }

    private fun noon(date: LocalDate) = sample(date.atTime(12, 0).atZone(zone).toEpochSecond(), 64, emptyList())

    @Test(timeout = 60_000)
    fun healthExportRetriesSurviveReopenAndRespectPartialPermissions() = runBlocking {
        val fileName = "health-retry-${System.nanoTime()}.db"
        val date = LocalDate.of(2026, 6, 14)
        val client = AuditHealthClient()
        var opened = open(fileName = fileName, healthExportClient = client)
        try {
            val base = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA)
            val day = base.copy(
                steps = app.circlet.core.Steps(Metric.of(50, Confidence.HIGH)),
                heart = base.heart.copy(resting = app.circlet.core.RestingHeartRate(Metric.of(60, Confidence.HIGH))),
            )
            val db = opened.data.database
            db.days().upsert(app.circlet.data.db.DaySummaryEntity(date.toString(), 1, 0, DayCodec.encode(day)))
            db.kv().put(app.circlet.data.db.KvEntity(Keys.HEALTH_ENABLED, "true"))
            db.kv().put(app.circlet.data.db.KvEntity("health_pending:$date", "7"))
            opened.data.retryHealthExports()
            assertNotNull(db.kv().get("health_pending:$date"))
            opened.close()
            opened = open(fileName = fileName, healthExportClient = client)
            client.failed = false
            opened.data.retryHealthExports()
            assertEquals(setOf(app.circlet.core.HealthKind.STEPS), client.rows.values.map { it.kind }.toSet())
            assertNotNull(opened.data.database.kv().get("health_pending:$date"))
            client.granted = app.circlet.core.HealthKind.entries.toSet()
            opened.data.retryHealthExports()
            assertNull(opened.data.database.kv().get("health_pending:$date"))
            assertTrue(client.rows.values.all { it.version == 7L })
            val count = client.rows.size
            opened.data.retryHealthExports()
            assertEquals(count, client.rows.size)
        } finally {
            opened.close()
            context.deleteDatabase(fileName)
        }
    }

    private fun sample(epoch: Long, heart: Int, rr: List<Int>) = Sample(
        epochSecond = epoch,
        heartRateBpm = heart,
        rrIntervalsMs = rr,
        gravity = GravityVector(0f, 0f, 1f),
        optical = OpticalChannels(10, 11, 12, 13),
        rawRecord = null,
    )

    private fun manual(date: LocalDate) = Activity(
        id = "manual-$date",
        source = ActivitySource.MANUAL,
        sport = Sport.YOGA,
        sportConfidence = null,
        start = date.atTime(12, 0).atZone(zone).toInstant(),
        end = date.atTime(12, 30).atZone(zone).toInstant(),
        strain = Metric.of(4.0, Confidence.LOW),
        averageHeartRateBpm = Metric.of(90, Confidence.LOW),
        maxHeartRateBpm = Metric.of(110, Confidence.LOW),
        calories = Metric.of(80, Confidence.LOW),
        zoneMinutes = ZoneMinutes.ZERO,
        heartRateRecovery = null,
    )

    private suspend fun open(
        debounceMs: Long = 50,
        startPipeline: Boolean = false,
        fileName: String? = null,
        healthExportClient: app.circlet.data.health.HealthRecordClient? = null,
        sampleQuietMs: Long = debounceMs,
    ): Opened {
        val engine = FakeDayEngine()
        val database = if (fileName == null) openMemoryDatabase(context) else openNamedDatabase(context, fileName)
        val data = DataModule.open(
            context = context,
            dayEngine = engine,
            insightEngine = FakeInsights(),
            time = time,
            database = database,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.IO,
            debounceMs = debounceMs,
            startPipeline = startPipeline,
            healthExportClient = healthExportClient,
            sampleQuietMs = sampleQuietMs,
        )
        data.awaitReady()
        return Opened(data, engine)
    }
}

private class AuditHealthClient : app.circlet.data.health.HealthRecordClient {
    var failed = true
    var granted = setOf(app.circlet.core.HealthKind.STEPS)
    val rows = mutableMapOf<String, app.circlet.data.health.HealthWrite>()
    override suspend fun status(enabled: Boolean) = app.circlet.core.HealthConnectStatus(true, enabled, granted)
    override suspend fun delete(kind: app.circlet.core.HealthKind, clientRecordIds: List<String>) { error("Must not delete before upsert") }
    override suspend fun insert(writes: List<app.circlet.data.health.HealthWrite>) {
        if (failed) error("provider unavailable")
        require(writes.all { it.kind in granted })
        writes.forEach { rows[it.clientRecordId] = it }
    }
}

private class Opened(val data: CircletData, val engine: FakeDayEngine) {
    fun close() = data.close()
}

private class FakeDayEngine : DayEngine {
    var version: Int = 1
    var fail: Boolean = false
    val calls = CopyOnWriteArrayList<LocalDate>()
    val manuals = CopyOnWriteArrayList<String>()

    override val algorithmVersion: Int get() = version

    override fun computeDay(input: DayInput): DaySummary {
        if (fail) error("engine failed")
        calls += input.date
        manuals.clear()
        manuals += input.manualActivities.map { it.id }
        val start = input.date.atTime(8, 0).atZone(input.zone).toInstant()
        val auto = Activity(
            id = "auto-${input.date}",
            source = ActivitySource.AUTO,
            sport = Sport.RUNNING,
            sportConfidence = Confidence.LOW,
            start = start,
            end = start.plusSeconds(1_800),
            strain = Metric.of(8.0, Confidence.MEDIUM),
            averageHeartRateBpm = Metric.of(150, Confidence.MEDIUM),
            maxHeartRateBpm = Metric.of(170, Confidence.MEDIUM),
            calories = Metric.of(200, Confidence.LOW),
            zoneMinutes = ZoneMinutes.of(0, 0, 10, 20, 0, 0),
            heartRateRecovery = null,
        )
        return emptyDay(input.date, AbsentReason.NOT_ENOUGH_DATA).copy(
            calories = Metric.of(version, Confidence.HIGH),
            activities = input.manualActivities + auto,
        )
    }
}

private class FakeInsights : InsightEngine {
    override fun trend(days: List<DaySummary>, metric: MetricKind): List<TrendPoint> = emptyList()

    override fun records(days: List<DaySummary>): List<PersonalRecord> = emptyList()

    override fun weeklyRecap(weekStart: LocalDate, days: List<DaySummary>): WeeklyRecap = WeeklyRecap(
        weekStart = weekStart,
        days = days,
        averageRecovery = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageStrain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageSleepPerformance = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        averageHrvMs = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
        sleepDebt = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
    )

    override fun correlations(
        days: List<DaySummary>,
        tags: List<JournalTag>,
        entries: List<JournalEntry>,
    ): List<TagCorrelation> = emptyList()

    override fun recommendedBedtime(history: List<DaySummary>, zone: ZoneId, now: Instant): Instant? = null
}
