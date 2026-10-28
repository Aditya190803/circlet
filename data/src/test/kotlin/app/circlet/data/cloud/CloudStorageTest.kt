package app.circlet.data.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.circlet.core.Outcome
import app.circlet.core.RecordingFiles
import app.circlet.data.db.CircletDatabase
import app.circlet.data.db.KvEntity
import app.circlet.data.db.SampleEntity
import app.circlet.data.db.openMemoryDatabase
import app.circlet.data.db.openNamedDatabase
import app.circlet.data.repo.applyRetention
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var session: FirebaseCloudSession
    private lateinit var db: CircletDatabase
    private lateinit var mirror: CloudMirror
    private val remote = MemoryRemote()
    private val epoch = 1_700_000_000L
    private val hour = epoch / 3600

    @Before fun prepare() {
        context.deleteDatabase("cloud-pending.db")
        context.deleteDatabase(FILE_DB)
        File(context.filesDir, "steps").deleteRecursively()
        context.getSharedPreferences("personal-firebase", 0).edit().clear()
            .putString("uid", "alice").putBoolean("session_saved", true).commit()
        session = FirebaseCloudSession(context, scheduleUpload = {})
        db = openMemoryDatabase(context)
        mirror = CloudMirror(db, session) { remote }
    }

    @After fun close() {
        RecordingFiles.storage = null
        session.outbox.close()
        db.close()
    }

    @Test fun aSmallTableChangeIsQueuedBeforeTheRepositoryCallReturns() {
        mirror.initialize()
        mirror.local { db.kv().put(KvEntity("mass", "POUNDS")) }
        val queued = session.outbox.page().single { it.row.table == "kv" }
        assertEquals("POUNDS", unpackCloudJson(queued.row.payload!!).getString("value"))
    }

    @Test fun aSampleQueuesItsHourAndUploadsAsOneCompressedBundle() {
        mirror.initialize()
        mirror.local { db.samples().insert(listOf(sample(epoch, 63), sample(epoch + 1, 64))) }
        assertTrue(session.outbox.page().none { it.row.table == "samples" })
        assertEquals(1, mirror.pendingHours())
        assertEquals(1, mirror.uploadHours(remote))
        val bundle = remote.rows.single { it.table == CloudMirror.SAMPLE_HOURS }
        assertEquals(CloudMirror.hourKey(hour), bundle.key)
        val rows = unpackCloudJson(bundle.payload!!).getJSONArray("rows")
        assertEquals(listOf(63, 64), (0 until rows.length()).map { rows.getJSONObject(it).getInt("heartRate") })
        assertEquals(0, mirror.pendingHours())
    }

    @Test fun anHourThatGainsSamplesDuringItsUploadIsSentAgain() {
        mirror.initialize()
        mirror.local { db.samples().insert(listOf(sample(epoch, 63))) }
        val racing = object : CloudRemote by remote {
            override fun upload(rows: List<CloudRow>) {
                remote.upload(rows)
                mirror.local { db.samples().insert(listOf(sample(epoch + 2, 70))) }
            }
        }
        mirror.uploadHours(racing)
        assertEquals(1, mirror.pendingHours())
        mirror.uploadHours(remote)
        assertEquals(0, mirror.pendingHours())
        assertEquals(2, unpackCloudJson(remote.rows.last().payload!!).getJSONArray("rows").length())
    }

    @Test fun theLocalDatabaseIsKeptAfterEverythingIsUploaded() = kotlinx.coroutines.test.runTest {
        db.close()
        db = openNamedDatabase(context, FILE_DB)
        db.kv().put(KvEntity("mass", "POUNDS"))
        db.samples().insert(listOf(sample(epoch, 63)))
        mirror = CloudMirror(db, session) { remote }
        mirror.initialize()
        assertFalse(mirror.needsRestore)
        while (session.pendingCount() > 0) assertTrue(session.uploadPending(remote) is Outcome.Ok)
        assertTrue(context.getDatabasePath(FILE_DB).exists())
        assertEquals("POUNDS", db.kv().get("mass"))
        assertEquals(63, db.samples().range(epoch, epoch).single().heartRate)
        assertTrue(remote.rows.any { it.table == "kv" })
        assertTrue(remote.rows.any { it.table == CloudMirror.SAMPLE_HOURS })
    }

    @Test fun aFreshPhoneRestoresSummariesAndLoadsSampleHoursOnlyWhenNeeded() {
        remote.exists = true
        remote.rows += CloudRow("kv", cloudKey("mass"), packCloudJson(JSONObject().put("key", "mass").put("value", "POUNDS")))
        remote.rows += hourBundle(sample(epoch, 63))
        mirror.initialize()
        assertTrue(mirror.needsRestore)
        assertTrue(mirror.retryRestore())
        assertEquals("POUNDS", db.kv().get("mass"))
        assertTrue(db.samples().range(epoch, epoch).isEmpty())
        val day = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate()
        assertTrue(mirror.ensureSamples(day, ZoneOffset.UTC))
        assertEquals(63, db.samples().range(epoch, epoch).single().heartRate)
        assertEquals(0, session.pendingCount())
    }

    @Test fun aPhoneThatCollectedItsOwnHistoryNeverDownloadsSamples() {
        mirror.local { db.samples().insert(listOf(sample(epoch, 63))) }
        mirror = CloudMirror(db, session) { error("A phone with its own history must not download samples") }
        mirror.initialize()
        assertTrue(mirror.ensureSamples(LocalDate.of(2020, 1, 1), ZoneOffset.UTC))
    }

    @Test fun aRestoreNeverOverwritesALocalEditStillWaitingToUpload() {
        mirror.initialize()
        mirror.local { db.kv().put(KvEntity("mass", "POUNDS")) }
        db.close()
        db = openMemoryDatabase(context)
        context.getSharedPreferences("personal-firebase", 0).edit().remove("baseline_queued").commit()
        remote.exists = true
        remote.rows += CloudRow("kv", cloudKey("mass"), packCloudJson(JSONObject().put("key", "mass").put("value", "KILOGRAM")))
        mirror = CloudMirror(db, session) { remote }
        mirror.initialize()
        mirror.retryRestore()
        assertFalse(db.kv().get("mass") == "KILOGRAM")
        assertEquals(1, session.outbox.count())
    }

    @Test fun anOfflineRestoreKeepsWaitingAndReportsNothingLost() {
        remote.fail = true
        mirror.initialize()
        assertTrue(runCatching { mirror.retryRestore() }.isFailure)
        assertTrue(mirror.needsRestore)
        remote.fail = false
        assertTrue(mirror.retryRestore())
        assertFalse(mirror.needsRestore)
    }

    @Test fun anUploadCanResumeAfterRestartWithoutOpeningTheDatabase() = kotlinx.coroutines.test.runTest {
        mirror.initialize()
        mirror.local { db.kv().put(KvEntity("mass", "POUNDS")) }
        val restarted = FirebaseCloudSession(context, scheduleUpload = {})
        try {
            assertNull(restarted.mirror)
            assertTrue(restarted.uploadPending(remote) is Outcome.Ok)
            assertEquals(0, restarted.outbox.count())
            assertTrue(remote.rows.any { it.table == "kv" })
        } finally { restarted.outbox.close() }
    }

    @Test fun aFailedUploadKeepsItsDurableQueueForTheNextWorker() = kotlinx.coroutines.test.runTest {
        mirror.initialize()
        mirror.local { db.kv().put(KvEntity("mass", "POUNDS")) }
        mirror.local { db.samples().insert(listOf(sample(epoch, 63))) }
        val failing = object : CloudRemote by remote {
            override fun upload(rows: List<CloudRow>) { throw CloudFailure("Offline") }
        }
        assertTrue(session.uploadPending(failing) is Outcome.Err)
        assertEquals(1, session.outbox.count())
        assertEquals(1, mirror.pendingHours())
        assertTrue(session.uploadPending(remote) is Outcome.Ok)
        assertEquals(0, session.pendingCount())
    }

    @Test fun backgroundUploadsYieldAfterABoundedNumberOfPages() = kotlinx.coroutines.test.runTest {
        session.outbox.put((1..1_100).map { CloudRow("kv", cloudKey("row$it"), "payload") })
        assertTrue(session.uploadPending(remote, maxPages = 1) is Outcome.Ok)
        assertEquals(500, remote.rows.size)
        assertEquals(600, session.outbox.count())
    }

    @Test fun acknowledgingAnOlderUploadDoesNotEraseANewerEdit() {
        val outbox = session.outbox
        outbox.put(listOf(CloudRow("profile", "31", "first")))
        val inFlight = outbox.page()
        outbox.put(listOf(CloudRow("profile", "31", "second")))
        outbox.acknowledge(inFlight)
        assertEquals("second", outbox.page().single().row.payload)
        outbox.acknowledge(outbox.page())
        assertEquals(0, outbox.count())
    }

    @Test fun deletesAreQueuedAsDeletes() {
        mirror.initialize()
        mirror.local { db.kv().put(KvEntity("mass", "POUNDS")) }
        session.outbox.acknowledge(session.outbox.page())
        mirror.local { db.kv().delete("mass") }
        assertNull(session.outbox.page().single().row.payload)
    }

    @Test fun recordingsStayOnDiskAndQueueOnlyTheirChangedChunks() {
        mirror.initialize()
        val file = File(context.filesDir, "steps/2026-10-18.csv")
        RecordingFiles.append(file, "1000,10\n")
        session.outbox.acknowledge(session.outbox.page())
        RecordingFiles.append(file, "2000,12\n")
        assertEquals("1000,10\n2000,12\n", file.readText())
        val chunk = session.outbox.page().single { it.row.table == "recordings" }
        assertEquals("1000,10\n2000,12\n", String(java.util.Base64.getDecoder().decode(unpackCloudJson(chunk.row.payload!!).getString("bytes"))))
        assertTrue(RecordingFiles.delete(file))
        assertFalse(file.exists())
        assertNull(session.outbox.page().single { it.row.table == "recordings" }.row.payload)
    }

    @Test fun aFreshPhoneRestoresRecordingFiles() {
        remote.exists = true
        val bytes = java.util.Base64.getEncoder().encodeToString("1000,10\n".toByteArray())
        remote.rows += CloudRow("recordings", cloudKey("steps/2026-10-18.csv") + "00" + "%08x".format(0),
            packCloudJson(JSONObject().put("path", "steps/2026-10-18.csv").put("chunk", 0).put("bytes", bytes)))
        mirror.initialize()
        mirror.retryRestore()
        assertEquals("1000,10\n", File(context.filesDir, "steps/2026-10-18.csv").readText())
    }

    @Test fun retentionKeepsSamplesWhoseHourHasNotUploaded() {
        mirror.initialize()
        val now = Instant.ofEpochSecond(epoch).plusSeconds(10 * 86_400L)
        mirror.local {
            db.kv().put(KvEntity("sample_retention_days", "1"))
            db.samples().insert(listOf(sample(epoch, 63)))
        }
        applyRetention(db, now)
        assertEquals(1, db.samples().range(epoch, epoch).size)
        mirror.uploadHours(remote)
        applyRetention(db, now)
        assertTrue(db.samples().range(epoch, epoch).isEmpty())
    }

    private fun sample(at: Long, bpm: Int) = SampleEntity(at, bpm, null, null, null, null, null, null, 120)

    private fun hourBundle(vararg samples: SampleEntity): CloudRow {
        val rows = org.json.JSONArray()
        for (s in samples) rows.put(JSONObject().put("epochSecond", s.epochSecond).put("heartRate", s.heartRate)
            .put("rr", JSONObject.NULL).put("gravity", JSONObject.NULL).put("green", JSONObject.NULL).put("red", JSONObject.NULL)
            .put("infrared", JSONObject.NULL).put("ambient", JSONObject.NULL).put("skinTempCount", s.skinTempCount))
        return CloudRow(CloudMirror.SAMPLE_HOURS, CloudMirror.hourKey(hour), packCloudJson(JSONObject().put("hour", hour).put("rows", rows)))
    }

    private class MemoryRemote : CloudRemote {
        var exists = false
        var fail = false
        val rows = mutableListOf<CloudRow>()
        override fun format(): Int? { if (fail) throw CloudFailure("Offline test"); return if (exists) 1 else null }
        override fun read(table: String, accept: (CloudRow) -> Unit) {
            if (fail) throw CloudFailure("Offline test")
            rows.filter { it.table == table }.forEach(accept)
        }
        override fun upload(rows: List<CloudRow>) { this.rows += rows; exists = true }
    }

    private companion object {
        const val FILE_DB = "circlet-cloud-test.db"
    }
}
