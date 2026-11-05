package app.circlet.data.cloud

import app.circlet.data.db.CircletDatabase
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs the on-disk database up to Firebase, the way WHOOP's app queues strap data and uploads it in
 * compressed batches. The phone keeps its full history. Small tables are journaled row by row; the
 * per-second samples go up as one gzip bundle per hour, because a node per second does not fit the
 * free plan. Raw strap bytes stay on the phone: the samples decoded from them are what a restore needs.
 */
internal class CloudMirror(
    private val room: CircletDatabase,
    private val session: FirebaseCloudSession,
    private val createRemote: () -> CloudRemote = session::remote,
) {
    private val db get() = room.openHelper.writableDatabase
    private val tables by lazy { cloudTables(db).filterNot { it.name in BUNDLED }.associateBy { it.name } }
    private val samples by lazy { cloudTables(db).single { it.name == "samples" } }
    private val monitor = Any()
    @Volatile private var capturing = false
    private val recordings = CloudRecordings(session)
    private val loadedHours = mutableSetOf<Long>()
    private var lastHours = -1

    /** True on a signed-in phone that started empty and has not yet loaded its cloud history. */
    @Volatile var needsRestore: Boolean = false
        private set
    var onReload: suspend () -> Unit = {}

    /** Registers with the session; starts journaling only once an account is signed in. No network. */
    fun initialize() = synchronized(monitor) {
        session.mirror = this
        if (session.state.value.signedIn) activate()
    }

    /** Called at startup when signed in, and right after a first sign-in. Idempotent. */
    fun activate() = synchronized(monitor) {
        if (capturing) return@synchronized
        installJournal()
        val restored = session.preferences.getBoolean(KEY_BASELINE, false)
        if (!restored) {
            if (hasHistory()) queueBaseline() else needsRestore = true
        }
        app.circlet.core.RecordingFiles.storage = recordings
        capturing = true
        session.queued()
    }

    /** Wraps every repository IO block, so a change is journaled before its result (and a strap ACK) escapes. */
    fun <T> local(block: () -> T): T = synchronized(monitor) {
        try { block() } finally { if (capturing) capture() }
    }

    /** Loads a fresh phone's history from the cloud. Returns false when nothing was waiting to load. */
    fun retryRestore(): Boolean {
        if (!needsRestore) return false
        val remote = createRemote()
        val format = remote.format()
        if (format != null && format != 1) throw CloudFailure("Update Circlet before opening this cloud database.")
        if (format != null) {
            for (table in tables.values) remote.read(table.name, ::applyDownloaded)
            remote.read("recordings") { row -> if (!session.outbox.contains(row.table, row.key)) recordings.restore(row) }
        }
        synchronized(monitor) {
            check(session.preferences.edit().putBoolean(KEY_BASELINE, true).putBoolean(KEY_RESTORED, format != null).commit())
            needsRestore = false
        }
        return true
    }

    /**
     * Samples for [date]'s window, from the cloud when this phone was restored and lacks them.
     * A phone that collected its own history never downloads: a day with no samples was not worn.
     */
    fun ensureSamples(date: LocalDate, zone: ZoneId): Boolean {
        if (!session.preferences.getBoolean(KEY_RESTORED, false)) return true
        val window = app.circlet.data.pipeline.sampleWindow(date, zone)
        val hours = (window.first / 3600)..(window.last / 3600)
        val missing = synchronized(monitor) { hours.filter { it !in loadedHours && !hasLocalHour(it) } }
        if (missing.isEmpty()) return true
        return try {
            createRemote().readRange(SAMPLE_HOURS, hourKey(missing.first()), hourKey(missing.last()), ::applyHour)
            synchronized(monitor) { loadedHours += missing }
            true
        } catch (failed: Exception) { session.problem(failed); false }
    }

    /** Downloads every sample hour this phone does not hold, so an export is complete. */
    fun loadForExport() {
        if (needsRestore) throw CloudFailure("Wait for your cloud history to finish loading before exporting it.")
        if (!session.preferences.getBoolean(KEY_RESTORED, false)) return
        createRemote().read(SAMPLE_HOURS) { row -> if (!synchronized(monitor) { hasLocalHour(row.key.hourOf()) }) applyHour(row) }
    }

    /** Uploads up to [limit] changed hours. Returns how many were sent. */
    fun uploadHours(remote: CloudRemote, limit: Int = 8): Int {
        val batch = synchronized(monitor) {
            db.query("SELECT hour,version FROM cloud_hours ORDER BY hour LIMIT ?", arrayOf(limit)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
            }.map { (hour, version) -> Triple(hour, version, bundle(hour)) }
        }
        if (batch.isEmpty()) return 0
        remote.upload(batch.map { (hour, _, payload) -> CloudRow(SAMPLE_HOURS, hourKey(hour), payload) })
        synchronized(monitor) {
            // An hour that gained samples during the upload keeps its newer version and goes again.
            for ((hour, version) in batch) db.execSQL("DELETE FROM cloud_hours WHERE hour=? AND version=?", arrayOf(hour, version))
        }
        return batch.size
    }

    // No monitor: the recordings lock calls this, and taking the monitor there could deadlock with [local].
    fun pendingHours(): Int =
        if (!capturing) 0 else db.query("SELECT count(*) FROM cloud_hours").use { it.moveToFirst(); it.getInt(0) }

    private fun bundle(hour: Long): String? {
        val rows = JSONArray()
        db.query("SELECT * FROM samples WHERE epochSecond BETWEEN ? AND ? ORDER BY epochSecond", arrayOf(hour * 3600, hour * 3600 + 3599)).use { cursor ->
            while (cursor.moveToNext()) rows.put(cloudRowJson(cursor, samples.columns))
        }
        if (rows.length() == 0) return null
        return packCloudJson(JSONObject().put("hour", hour).put("rows", rows))
    }

    private fun applyHour(row: CloudRow) = synchronized(monitor) {
        val payload = row.payload ?: return@synchronized
        val json = unpackCloudJson(payload)
        val hour = json.getLong("hour")
        require(hourKey(hour) == row.key) { "Cloud hour does not match its data." }
        val rows = json.getJSONArray("rows")
        for (index in 0 until rows.length()) {
            val sample = rows.getJSONObject(index)
            val epoch = sample.getLong("epochSecond")
            require(epoch / 3600 == hour) { "A cloud sample lies outside its hour." }
            applyCloudRow(db, samples, CloudRow("samples", cloudKey(epoch.toString()), packCloudJson(sample)), replace = false)
        }
        // Downloaded samples are already in the cloud and must not be uploaded again.
        db.execSQL("DELETE FROM cloud_hours WHERE hour=?", arrayOf(hour))
        loadedHours += hour
    }

    private fun applyDownloaded(row: CloudRow) = synchronized(monitor) {
        if (session.outbox.contains(row.table, row.key)) return@synchronized
        applyCloudRow(db, tables.getValue(row.table), row)
        db.execSQL("DELETE FROM cloud_changes WHERE table_name=? AND row_key=?", arrayOf(row.table, row.key))
    }

    private fun hasHistory(): Boolean = db.query("SELECT EXISTS(SELECT 1 FROM samples) OR EXISTS(SELECT 1 FROM day_summaries)").use {
        it.moveToFirst() && it.getInt(0) == 1
    }

    private fun hasLocalHour(hour: Long): Boolean =
        db.query("SELECT EXISTS(SELECT 1 FROM samples WHERE epochSecond BETWEEN ? AND ?)", arrayOf(hour * 3600, hour * 3600 + 3599)).use {
            it.moveToFirst() && it.getInt(0) == 1
        }

    /** First sign-in on a phone that already has history: queue all of it once. */
    private fun queueBaseline() {
        for (table in tables.values) {
            db.query("SELECT *,${table.keySql()} AS cloud_key FROM \"${table.name}\"").use { cursor ->
                val rows = mutableListOf<CloudRow>()
                while (cursor.moveToNext()) {
                    rows += CloudRow(table.name, cursor.getString(cursor.getColumnIndexOrThrow("cloud_key")), encodeCloudRow(cursor, table.columns))
                    if (rows.size == 500) { session.outbox.put(rows, replaceExisting = false); rows.clear() }
                }
                if (rows.isNotEmpty()) session.outbox.put(rows, replaceExisting = false)
            }
        }
        db.execSQL("INSERT OR IGNORE INTO cloud_hours(hour,version) SELECT DISTINCT epochSecond/3600, 1 FROM samples")
        recordings.importExisting()
        check(session.preferences.edit().putBoolean(KEY_BASELINE, true).commit())
    }

    /** Persistent journal tables and triggers, so every connection's writes are captured. */
    private fun installJournal() {
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_changes (table_name TEXT NOT NULL,row_key TEXT NOT NULL,row_id INTEGER,deleted INTEGER NOT NULL,PRIMARY KEY(table_name,row_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_hours (hour INTEGER PRIMARY KEY NOT NULL,version INTEGER NOT NULL)")
        for (table in tables.values) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                val prefix = if (operation == "DELETE") "OLD." else "NEW."
                val deleted = if (operation == "DELETE") 1 else 0
                db.execSQL("CREATE TRIGGER IF NOT EXISTS cloud_${table.name}_$operation AFTER $operation ON \"${table.name}\" BEGIN " +
                    "INSERT OR REPLACE INTO cloud_changes VALUES ('${table.name}',${table.keySql(prefix)},${prefix}rowid,$deleted); END")
            }
        }
        for (operation in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS cloud_samples_$operation AFTER $operation ON samples BEGIN " +
                "INSERT OR REPLACE INTO cloud_hours(hour,version) VALUES (NEW.epochSecond/3600," +
                "COALESCE((SELECT version FROM cloud_hours WHERE hour=NEW.epochSecond/3600),0)+1); END")
        }
    }

    private fun capture() {
        val changes = mutableListOf<CloudRow>()
        var changed = false
        db.query("SELECT table_name,row_key,row_id,deleted FROM cloud_changes").use { cursor ->
            while (cursor.moveToNext()) {
                changed = true
                val table = tables.getValue(cursor.getString(0))
                val key = cursor.getString(1)
                val payload = if (cursor.getInt(3) != 0) null else {
                    db.query("SELECT * FROM \"${table.name}\" WHERE rowid = ?", arrayOf(cursor.getLong(2))).use { row ->
                        if (row.moveToFirst()) encodeCloudRow(row, table.columns) else null
                    }
                }
                changes += CloudRow(table.name, key, payload)
                if (changes.size == 500) { session.outbox.put(changes); changes.clear() }
            }
        }
        if (changes.isNotEmpty()) session.outbox.put(changes)
        if (changed) db.execSQL("DELETE FROM cloud_changes")
        // Sample hours are queued in the database itself; the session only needs a nudge when that queue moves.
        val hours = db.query("SELECT count(*) FROM cloud_hours").use { it.moveToFirst(); it.getInt(0) }
        if (changed || hours != lastHours) {
            lastHours = hours
            session.queued()
        }
    }

    private fun String.hourOf(): Long = String(chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8).toLong()

    internal companion object {
        const val SAMPLE_HOURS = "sample_hours"
        private const val KEY_BASELINE = "baseline_queued"
        private const val KEY_RESTORED = "restored_from_cloud"
        private val BUNDLED = setOf("samples", "raw_records")
        fun hourKey(hour: Long): String = cloudKey(hour.toString())
    }
}
