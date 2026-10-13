package app.circlet.route

import android.content.Context
import app.circlet.core.RoutePoint
import java.io.File
import org.json.JSONObject

/** One JSON line per fix in `filesDir/routes/<id>.jsonl`. */
class RouteStore(context: Context) {
    private val dir = File(context.filesDir, "routes")

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl")

    fun exists(id: String): Boolean = file(id).exists()

    fun append(id: String, point: RoutePoint) {
        dir.mkdirs()
        val line = JSONObject()
            .put("t", point.epochMs)
            .put("lat", point.lat)
            .put("lon", point.lon)
            .put("acc", point.accuracyM)
        file(id).appendText(line.toString() + "\n")
    }

    fun read(id: String): List<RoutePoint> {
        val source = file(id)
        if (!source.exists()) return emptyList()
        return source.readLines().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                RoutePoint(json.getLong("t"), json.getDouble("lat"), json.getDouble("lon"), json.getDouble("acc"))
            }.getOrNull()
        }
    }

    fun rename(from: String, to: String) {
        val source = file(from)
        if (source.exists()) source.renameTo(file(to))
    }

    fun delete(id: String) {
        file(id).delete()
    }
}
