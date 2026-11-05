package app.circlet.data.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

internal data class ArchivedRecording(val path: String, val bytes: ByteArray)

private val recordingPath = Regex("(?:steps/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.csv|routes/[A-Za-z0-9._-]+\\.jsonl)")

internal fun recordingFiles(context: Context): List<ArchivedRecording> =
    listOf("steps", "routes").flatMap { folder ->
        app.circlet.core.RecordingFiles.list(File(context.filesDir, folder)).mapNotNull { file ->
            val path = "$folder/${file.name}"
            if (recordingPath.matches(path)) app.circlet.core.RecordingFiles.read(file)?.let { ArchivedRecording(path, it) } else null
        }
    }

internal fun archiveRecordings(sqlite: SupportSQLiteDatabase, context: Context) {
    sqlite.execSQL("CREATE TABLE snap.recording_files (path TEXT PRIMARY KEY NOT NULL, bytes BLOB NOT NULL)")
    for (file in recordingFiles(context)) {
        sqlite.execSQL("INSERT INTO snap.recording_files(path, bytes) VALUES (?, ?)", arrayOf(file.path, file.bytes))
    }
}

internal fun readRecordings(sqlite: SupportSQLiteDatabase): List<ArchivedRecording> =
    sqlite.query("SELECT path, bytes FROM incoming.recording_files").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val path = cursor.getString(0)
                require(recordingPath.matches(path)) { "Invalid recording path in backup" }
                add(ArchivedRecording(path, cursor.getBlob(1)))
            }
        }
    }

internal fun replaceRecordings(context: Context, recordings: List<ArchivedRecording>) {
    require(recordings.all { recordingPath.matches(it.path) }) { "Invalid recording path" }
    val keep = recordings.map { it.path }.toSet()
    for (existing in recordingFiles(context)) {
        if (existing.path !in keep) check(app.circlet.core.RecordingFiles.delete(File(context.filesDir, existing.path))) { "Could not remove recording" }
    }
    for (recording in recordings) {
        val target = File(context.filesDir, recording.path)
        if (app.circlet.core.RecordingFiles.storage != null) {
            app.circlet.core.RecordingFiles.write(target, recording.bytes)
            continue
        }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Could not create recording folder" }
        val staged = File.createTempFile("recording-", ".tmp", target.parentFile)
        try {
            staged.writeBytes(recording.bytes)
            java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staged.delete()
        }
    }
}
