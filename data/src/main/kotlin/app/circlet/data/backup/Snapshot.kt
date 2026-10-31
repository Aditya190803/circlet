package app.circlet.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import app.circlet.data.db.CircletDatabase
import app.circlet.data.db.Keys
import app.circlet.data.db.SCHEMA_IDENTITY
import app.circlet.data.db.USER_TABLES
import app.circlet.core.RecordingFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

internal class DocumentStore(private val context: Context) {
    /** Streams [source] into [location], so a whole database never has to fit in the heap. */
    fun write(location: String, name: String, source: File): String {
        val parsed = Uri.parse(location)
        return if (parsed.scheme == "content") writeContent(parsed, name, source) else writeFile(location, name, source)
    }

    fun read(uri: String): ByteArray = open(uri).use { it.readBytes() }

    /** Streams [uri] into [target]. */
    fun copyTo(uri: String, target: File) {
        open(uri).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun open(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "content") {
            return context.contentResolver.openInputStream(parsed) ?: throw IOException("unreadable")
        }
        return asFile(uri).inputStream()
    }

    /** Pairs of uri and display name. */
    fun list(folder: String): List<Pair<String, String>> {
        val parsed = Uri.parse(folder)
        if (parsed.scheme == "content") return listTree(parsed)
        val directory = asFile(folder)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().map { it.toURI().toString() to it.name }
    }

    fun delete(uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "content") {
            DocumentsContract.deleteDocument(context.contentResolver, parsed)
        } else {
            asFile(uri).delete()
        }
    }

    fun rememberPermission(uri: String) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content") return
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(parsed, flags)
        } catch (error: SecurityException) {
            // The picker grants the permission before this is called. A file URI has nothing to take.
        }
    }

    private fun writeFile(location: String, name: String, source: File): String {
        val directory = asFile(location)
        directory.mkdirs()
        val out = File(directory, name)
        source.copyTo(out, overwrite = true)
        return out.toURI().toString()
    }

    private fun writeContent(uri: Uri, name: String, source: File): String {
        val resolver = context.contentResolver
        val target = if (DocumentsContract.isTreeUri(uri)) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                ?: throw IOException("could not create $name")
        } else {
            uri
        }
        resolver.openOutputStream(target, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: throw IOException("could not write")
        return target.toString()
    }

    private fun listTree(tree: Uri): List<Pair<String, String>> {
        if (!DocumentsContract.isTreeUri(tree)) return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val result = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                result += uri.toString() to name
            }
        }
        return result
    }

    private fun asFile(uri: String): File = if (uri.startsWith("file:")) File(java.net.URI(uri)) else File(uri)
}

internal fun rotate(store: DocumentStore, folder: String, prefix: String, keep: Int) {
    val matches = store.list(folder).filter { (_, name) -> name.startsWith(prefix) }.sortedBy { it.second }
    val extra = matches.size - keep
    if (extra <= 0) return
    matches.take(extra).forEach { (uri, _) -> store.delete(uri) }
}

/**
 * A consistent copy of the open connection.
 * Callers hold the write lock. Rows are read through that connection, so a WAL
 * frame that has not been checkpointed is still included. VACUUM INTO would do
 * this in one step, but it needs SQLite 3.27 and the unit-test driver is older.
 */
internal fun snapshotBytes(database: CircletDatabase, context: Context): ByteArray {
    val file = snapshotFile(database, context)
    return try {
        file.readBytes()
    } finally {
        file.delete()
    }
}

/** The snapshot as a temporary file in the cache directory. The caller deletes it. */
internal fun snapshotFile(database: CircletDatabase, context: Context): File {
    val sqlite = database.openHelper.writableDatabase
    val temp = File.createTempFile("circlet-snap", ".db", context.cacheDir)
    temp.delete()
    val path = temp.absolutePath.replace('\\', '/').replace("'", "''")
    try {
        sqlite.execSQL("ATTACH DATABASE '$path' AS snap")
        try {
            sqlite.execSQL("PRAGMA snap.user_version = ${CircletDatabase.VERSION}")
            for (table in USER_TABLES) {
                sqlite.execSQL("CREATE TABLE snap.$table AS SELECT * FROM $table")
            }
            RecordingFiles.locked { archiveRecordings(sqlite, context) }
        } finally {
            sqlite.execSQL("DETACH DATABASE snap")
        }
        return temp
    } catch (failed: Exception) {
        temp.delete()
        throw failed
    }
}

internal fun validateSnapshot(bytes: ByteArray): String? = withTempFile(bytes) { validateSnapshot(it) }

internal fun validateSnapshot(file: File): String? {
    val head = ByteArray(100)
    val read = file.inputStream().use { it.readNBytes(head, 0, head.size) }
    if (read < head.size) return "file is too small"
    if (String(head, 0, 15, Charsets.UTF_8) != "SQLite format 3") return "not a sqlite database"
    val version = ByteBuffer.wrap(head, 60, 4).int
    if (version !in 1..CircletDatabase.VERSION) return "unsupported schema version $version"
    return inspect(file)
}

internal fun restoreSnapshot(database: CircletDatabase, bytes: ByteArray, context: Context? = null) =
    withTempFile(bytes) { restoreSnapshot(database, it, context) }

private fun <T> withTempFile(bytes: ByteArray, block: (File) -> T): T {
    val temp = File.createTempFile("circlet-snapshot", ".db")
    return try {
        temp.writeBytes(bytes)
        block(temp)
    } finally {
        temp.delete()
    }
}

/**
 * Replaces every user table from a validated snapshot file without closing the database,
 * so open flows keep their connection and see the new rows.
 */
internal fun restoreSnapshot(database: CircletDatabase, file: File, context: Context? = null) {
    val path = file.absolutePath.replace('\\', '/').replace("'", "''")
    val sqlite = database.openHelper.writableDatabase
    sqlite.execSQL("ATTACH DATABASE '$path' AS incoming")
    try {
        val incoming = mutableSetOf<String>()
        sqlite.query("SELECT name FROM incoming.sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) incoming += cursor.getString(0)
        }
        RecordingFiles.locked {
            val recordings = if (context != null && "recording_files" in incoming) readRecordings(sqlite) else null
            val previous = if (recordings != null) recordingFiles(context!!) else null
            var changedFiles = false
            try {
                sqlite.beginTransaction()
                try {
                    for (table in USER_TABLES) {
                        sqlite.execSQL("DELETE FROM $table")
                        if (table in incoming) {
                            val columns = tableColumns(sqlite, "incoming", table)
                            val common = tableColumns(sqlite, "main", table).filter { it in columns }
                                .joinToString(",") { "\"$it\"" }
                            check(common.isNotEmpty()) { "Incompatible backup table $table" }
                            if (table == "raw_records" && "archiveKey" !in columns) {
                                sqlite.execSQL("INSERT INTO raw_records ($common, archiveKey) SELECT $common, CAST(epochSecond AS TEXT) FROM incoming.raw_records")
                            } else {
                                sqlite.execSQL("INSERT INTO $table ($common) SELECT $common FROM incoming.$table")
                            }
                        }
                    }
                    if (recordings != null) {
                        changedFiles = true
                        replaceRecordings(context!!, recordings)
                    }
                    sqlite.setTransactionSuccessful()
                } finally {
                    sqlite.endTransaction()
                }
            } catch (failed: Exception) {
                if (changedFiles && previous != null) {
                    try {
                        replaceRecordings(context!!, previous)
                    } catch (rollback: Exception) {
                        failed.addSuppressed(rollback)
                    }
                }
                throw failed
            }
        }
    } finally {
        sqlite.execSQL("DETACH DATABASE incoming")
    }
}

private fun tableColumns(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, schema: String, table: String): List<String> =
    sqlite.query("PRAGMA $schema.table_info('$table')").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }

private fun inspect(file: File): String? {
    val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    db.use { sqlite ->
        val names = mutableSetOf<String>()
        sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        val missing = listOf("samples", "raw_records", "day_summaries", "kv").filter { it !in names }
        if (missing.isNotEmpty()) return "missing tables ${missing.joinToString()}"
        sqlite.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(Keys.SCHEMA)).use { cursor ->
            if (!cursor.moveToFirst()) return "missing schema identity"
            if (cursor.getString(0) != SCHEMA_IDENTITY) return "not a circlet database"
        }
    }
    return null
}
