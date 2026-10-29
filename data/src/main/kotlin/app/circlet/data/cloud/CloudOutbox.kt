package app.circlet.data.cloud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal data class PendingCloudRow(val row: CloudRow, val version: String)

/** Only unsent mutations live on disk. A successful upload removes exactly the sent version. */
internal class CloudOutbox(context: Context) : SQLiteOpenHelper(context, "cloud-pending.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (collection TEXT NOT NULL, row_key TEXT NOT NULL, payload TEXT, version TEXT NOT NULL, PRIMARY KEY(collection,row_key))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported upload queue version")

    @Synchronized fun put(rows: List<CloudRow>, replaceExisting: Boolean = true) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val values = ContentValues().apply {
                    put("collection", row.table)
                    put("row_key", row.key)
                    put("payload", row.payload)
                    put("version", UUID.randomUUID().toString())
                }
                val result = db.insertWithOnConflict("pending", null, values,
                    if (replaceExisting) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
                if (replaceExisting) check(result != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun page(limit: Int = 500): List<PendingCloudRow> =
        readableDatabase.rawQuery("SELECT collection,row_key,payload,version FROM pending ORDER BY collection,row_key LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PendingCloudRow(CloudRow(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2)), cursor.getString(3)))
            }
        }

    @Synchronized fun acknowledge(sent: List<PendingCloudRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in sent) db.delete("pending", "collection=? AND row_key=? AND version=?", arrayOf(item.row.table, item.row.key, item.version))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun count(): Int = readableDatabase.rawQuery("SELECT count(*) FROM pending", null).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized fun contains(table: String, key: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM pending WHERE collection=? AND row_key=?", arrayOf(table, key),
    ).use { it.moveToFirst() }

    fun replay(accept: (CloudRow) -> Unit) {
        var collection = ""
        var key = ""
        while (true) {
            val page = synchronized(this) {
                readableDatabase.rawQuery("SELECT collection,row_key,payload FROM pending WHERE collection>? OR (collection=? AND row_key>?) ORDER BY collection,row_key LIMIT 500",
                    arrayOf(collection, collection, key)).use { cursor -> buildList {
                    while (cursor.moveToNext()) add(CloudRow(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2)))
                } }
            }
            if (page.isEmpty()) return
            page.forEach(accept)
            collection = page.last().table
            key = page.last().key
        }
    }
}
