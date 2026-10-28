package app.circlet.data.cloud

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

internal data class CloudRow(val table: String, val key: String, val payload: String?)
internal fun cloudKey(text: String): String {
    val digits = "0123456789abcdef"
    val bytes = text.toByteArray(Charsets.UTF_8)
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }
}
internal data class CloudTable(val name: String, val columns: List<String>, val primaryKey: String) {
    fun keySql(prefix: String = ""): String = "lower(hex(CAST($prefix\"$primaryKey\" AS BLOB)))"
}

internal fun cloudTables(db: SupportSQLiteDatabase): List<CloudTable> =
    app.circlet.data.db.USER_TABLES.map { table ->
        val columns = mutableListOf<String>()
        var key: String? = null
        db.query("PRAGMA table_info(\"$table\")").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                columns += name
                if (cursor.getInt(cursor.getColumnIndexOrThrow("pk")) == 1) key = name
            }
        }
        CloudTable(table, columns, checkNotNull(key) { "Cloud table has no primary key" })
    }

internal fun encodeCloudRow(cursor: Cursor, columns: List<String>): String = packCloudJson(cloudRowJson(cursor, columns))

internal fun cloudRowJson(cursor: Cursor, columns: List<String>): JSONObject {
    val json = JSONObject()
    for (column in columns) {
        val index = cursor.getColumnIndexOrThrow(column)
        val value: Any = when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.getEncoder().encodeToString(cursor.getBlob(index)))
            else -> cursor.getString(index)
        }
        json.put(column, value)
    }
    return json
}

internal fun packCloudJson(json: JSONObject): String {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    return Base64.getEncoder().encodeToString(output.toByteArray())
}

internal fun unpackCloudJson(payload: String): JSONObject {
    require(payload.length <= 2_000_000) { "Cloud record is too large." }
    val bytes = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload))).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 4_000_000) { "Cloud record expands beyond its limit." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    return JSONObject(String(bytes, Charsets.UTF_8))
}

/** [replace] false keeps a row this phone already holds; a restore never overwrites local history. */
internal fun applyCloudRow(db: SupportSQLiteDatabase, table: CloudTable, row: CloudRow, replace: Boolean = true) {
    if (row.payload == null) {
        db.execSQL("DELETE FROM \"${table.name}\" WHERE ${table.keySql()} = ?", arrayOf(row.key))
        return
    }
    val json = unpackCloudJson(row.payload)
    val columns = json.keys().asSequence().toSet()
    if (table.name == "samples" && columns == table.columns.toSet() - setOf("generation", "skinTemperatureCentiC", "bandState")) {
        json.put("generation", "GEN4").put("skinTemperatureCentiC", JSONObject.NULL).put("bandState", JSONObject.NULL)
    } else if (table.name == "raw_records" && columns == table.columns.toSet() - "archiveKey") {
        json.put("archiveKey", json.getLong("epochSecond").toString())
    }
    require(json.keys().asSequence().toSet() == table.columns.toSet()) { "Cloud data has a different schema. Update Circlet." }
    val values = table.columns.map { column ->
        when (val value = json.get(column)) {
            JSONObject.NULL -> null
            is JSONObject -> Base64.getDecoder().decode(value.getString("blob"))
            else -> value
        }
    }
    val key = cloudKey(json.get(table.primaryKey).toString())
    require(key == row.key) { "Cloud row key does not match its data." }
    db.execSQL(
        "INSERT OR ${if (replace) "REPLACE" else "IGNORE"} INTO \"${table.name}\" (${table.columns.joinToString { "\"$it\"" }}) VALUES (${values.joinToString { "?" }})",
        values.toTypedArray(),
    )
}
