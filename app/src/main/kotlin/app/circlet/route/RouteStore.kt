package app.circlet.route

import android.content.Context
import app.circlet.core.RoutePoint
import java.io.File
import org.json.JSONObject

/** One JSON line per fix in `filesDir/routes/<id>.jsonl`. */
class RouteStore(context: Context) {
    private val dir = File(context.filesDir, "routes")

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl")

    fun exists(id: String): Boolean = app.circlet.core.RecordingFiles.read(file(id)) != null

    fun append(id: String, point: RoutePoint) = app.circlet.core.RecordingFiles.locked {
        dir.mkdirs()
        val line = JSONObject()
            .put("t", point.epochMs)
            .put("lat", point.lat)
            .put("lon", point.lon)
            .put("acc", point.accuracyM)
        app.circlet.core.RecordingFiles.append(file(id), line.toString() + "\n")
    }

    fun read(id: String): List<RoutePoint> = app.circlet.core.RecordingFiles.locked {
        val source = file(id)
        val bytes = app.circlet.core.RecordingFiles.read(source) ?: return@locked emptyList()
        String(bytes, Charsets.UTF_8).lineSequence().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                RoutePoint(json.getLong("t"), json.getDouble("lat"), json.getDouble("lon"), json.getDouble("acc"))
            }.getOrNull()
        }.toList()
    }

    fun rename(from: String, to: String) = app.circlet.core.RecordingFiles.locked {
        val source = file(from)
        val bytes = app.circlet.core.RecordingFiles.read(source)
        if (bytes != null) {
            app.circlet.core.RecordingFiles.write(file(to), bytes)
            app.circlet.core.RecordingFiles.delete(source)
        }
    }

    fun delete(id: String) = app.circlet.core.RecordingFiles.locked {
        app.circlet.core.RecordingFiles.delete(file(id))
    }
}
