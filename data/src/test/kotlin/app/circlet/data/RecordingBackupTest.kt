package app.circlet.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.circlet.data.backup.restoreSnapshot
import app.circlet.data.backup.snapshotBytes
import app.circlet.data.backup.validateSnapshot
import app.circlet.data.db.openMemoryDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingBackupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun snapshotRestoresStepsAndRoutesAndRemovesRecordingsFromAfterTheBackup() {
        val db = openMemoryDatabase(context)
        val step = File(context.filesDir, "steps/2026-10-12.csv")
        val route = File(context.filesDir, "routes/audit-route.jsonl")
        val later = File(context.filesDir, "routes/audit-later.jsonl")
        try {
            step.parentFile!!.mkdirs()
            route.parentFile!!.mkdirs()
            step.writeText("1000,20\n2000,30\n")
            route.writeText("{\"t\":1000,\"lat\":1,\"lon\":2,\"acc\":3}\n")
            val bytes = snapshotBytes(db, context)
            assertNull(validateSnapshot(bytes))
            step.writeText("changed")
            route.delete()
            later.writeText("not in backup")
            restoreSnapshot(db, bytes, context)
            assertEquals("1000,20\n2000,30\n", step.readText())
            assertTrue(route.readText().contains("\"t\":1000"))
            assertFalse(later.exists())
        } finally {
            db.close()
            step.delete()
            route.delete()
            later.delete()
        }
    }

    @Test fun versionOneBackupRestoresSamplesWithoutTheNewNullableColumn() {
        val db = openMemoryDatabase(context)
        val old = File.createTempFile("circlet-v1", ".db", context.cacheDir)
        try {
            db.openHelper.writableDatabase.execSQL("INSERT INTO samples(epochSecond,heartRate) VALUES (1000,60)")
            old.writeBytes(snapshotBytes(db, context))
            SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
                sqlite.execSQL("ALTER TABLE samples RENAME TO new_samples")
                sqlite.execSQL("CREATE TABLE samples AS SELECT epochSecond,heartRate,rr,gravity,green,red,infrared,ambient FROM new_samples")
                sqlite.execSQL("DROP TABLE new_samples")
                sqlite.execSQL("DROP TABLE sleep_edits")
                sqlite.execSQL("PRAGMA user_version=1")
            }
            val bytes = old.readBytes()
            assertNull(validateSnapshot(bytes))
            db.openHelper.writableDatabase.execSQL("DELETE FROM samples")
            restoreSnapshot(db, bytes, context)
            db.openHelper.readableDatabase.query("SELECT heartRate, skinTempCount FROM samples WHERE epochSecond=1000").use {
                assertTrue(it.moveToFirst())
                assertEquals(60, it.getInt(0))
                assertTrue(it.isNull(1))
            }
        } finally {
            db.close()
            old.delete()
        }
    }

    @Test fun invalidArchivePathDoesNotReplaceExistingDatabaseRows() {
        val db = openMemoryDatabase(context)
        val bad = File.createTempFile("circlet-invalid", ".db", context.cacheDir)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("INSERT INTO samples(epochSecond,heartRate) VALUES (1000,60)")
            bad.writeBytes(snapshotBytes(db, context))
            SQLiteDatabase.openDatabase(bad.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO recording_files(path,bytes) VALUES ('../outside',X'00')")
            }
            assertNotNull(runCatching { restoreSnapshot(db, bad.readBytes(), context) }.exceptionOrNull())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM samples").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            db.close()
            bad.delete()
        }
    }
}
