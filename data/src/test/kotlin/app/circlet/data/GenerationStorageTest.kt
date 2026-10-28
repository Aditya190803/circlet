package app.circlet.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.circlet.data.backup.restoreSnapshot
import app.circlet.data.backup.snapshotBytes
import app.circlet.data.backup.validateSnapshot
import app.circlet.data.cloud.CloudRow
import app.circlet.data.cloud.applyCloudRow
import app.circlet.data.cloud.cloudKey
import app.circlet.data.cloud.cloudTables
import app.circlet.data.cloud.packCloudJson
import app.circlet.data.db.openMemoryDatabase
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenerationStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun legacyCloudRowsGainGen4DefaultsButUnknownColumnsAreRejected() {
        val database = openMemoryDatabase(context)
        try {
            val db = database.openHelper.writableDatabase
            val tables = cloudTables(db).associateBy { it.name }
            val sample = JSONObject().put("epochSecond", 1000).put("heartRate", 60).put("skinTempCount", 935)
            for (column in listOf("rr", "gravity", "green", "red", "infrared", "ambient")) sample.put(column, JSONObject.NULL)
            applyCloudRow(db, tables.getValue("samples"), CloudRow("samples", cloudKey("1000"), packCloudJson(sample)))
            val stored = database.samples().range(1000, 1000).single()
            assertEquals("GEN4", stored.generation)
            assertEquals(935, stored.skinTempCount)
            assertNull(stored.skinTemperatureCentiC)
            assertNull(stored.bandState)
            val raw = JSONObject().put("epochSecond", 1000).put("bytes", JSONObject().put("blob", "Lxg="))
            applyCloudRow(db, tables.getValue("raw_records"), CloudRow("raw_records", cloudKey("1000"), packCloudJson(raw)))
            db.query("SELECT archiveKey FROM raw_records").use {
                assertTrue(it.moveToFirst())
                assertEquals("1000", it.getString(0))
            }
            sample.put("unexpected", 1)
            assertTrue(runCatching {
                applyCloudRow(db, tables.getValue("samples"), CloudRow("samples", cloudKey("1000"), packCloudJson(sample)))
            }.exceptionOrNull() is IllegalArgumentException)
        } finally { database.close() }
    }

    @Test fun versionThreeSnapshotRestoresSamplesAndRawRecords() {
        val database = openMemoryDatabase(context)
        val legacy = File.createTempFile("circlet-gen3", ".db", context.cacheDir)
        try {
            legacy.writeBytes(snapshotBytes(database, context))
            SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE raw_records")
                db.execSQL("CREATE TABLE raw_records (epochSecond INTEGER PRIMARY KEY NOT NULL, bytes BLOB NOT NULL)")
                db.execSQL("INSERT INTO raw_records VALUES (1000, X'2F18')")
                db.execSQL("DROP TABLE samples")
                db.execSQL("CREATE TABLE samples (epochSecond INTEGER PRIMARY KEY NOT NULL, heartRate INTEGER, rr BLOB, gravity BLOB, green INTEGER, red INTEGER, infrared INTEGER, ambient INTEGER, skinTempCount INTEGER)")
                db.execSQL("INSERT INTO samples VALUES (1000, 60, NULL, NULL, NULL, NULL, NULL, NULL, 935)")
                db.execSQL("PRAGMA user_version = 3")
            }
            val bytes = legacy.readBytes()
            assertNull(validateSnapshot(bytes))
            restoreSnapshot(database, bytes)
            val sample = database.samples().range(1000, 1000).single()
            assertEquals("GEN4", sample.generation)
            assertEquals(935, sample.skinTempCount)
            assertNull(sample.skinTemperatureCentiC)
            database.openHelper.readableDatabase.query("SELECT archiveKey, bytes FROM raw_records").use {
                assertTrue(it.moveToFirst())
                assertEquals("1000", it.getString(0))
                assertArrayEquals(byteArrayOf(0x2F, 0x18), it.getBlob(1))
            }
        } finally {
            legacy.delete()
            database.close()
        }
    }
}
