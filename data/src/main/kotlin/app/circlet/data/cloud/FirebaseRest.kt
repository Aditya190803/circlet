package app.circlet.data.cloud

import app.circlet.core.CloudConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

internal class CloudFailure(message: String) : IOException(message)
internal data class CloudHttpResponse(val status: Int, val body: String)
internal fun interface CloudHttp {
    fun request(url: String, method: String, body: String?): CloudHttpResponse
}

internal object FirebaseHttp : CloudHttp {
    override fun request(url: String, method: String, body: String?): CloudHttpResponse = request(url, method, body, emptyMap())

    fun request(url: String, method: String, body: String?, headers: Map<String, String>): CloudHttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (URI(url).host == "securetoken.googleapis.com")
                    "application/x-www-form-urlencoded" else "application/json; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = input?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 8_000_000) throw CloudFailure("Cloud response is too large. Try again after updating Circlet.")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return CloudHttpResponse(status, response)
        } finally { connection.disconnect() }
    }
}

internal interface CloudRemote {
    fun format(): Int?
    fun read(table: String, accept: (CloudRow) -> Unit)
    fun readRange(table: String, fromKey: String, toKey: String, accept: (CloudRow) -> Unit) =
        read(table) { if (it.key >= fromKey && it.key <= toKey) accept(it) }
    fun upload(rows: List<CloudRow>)
}

/** Firebase's authenticated REST API; no admin credentials or open database rules. */
internal class FirebaseRest(
    private val config: CloudConfig,
    private val uid: String,
    private val token: (Boolean) -> String,
    private val http: CloudHttp = FirebaseHttp,
) : CloudRemote {
    init { require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }

    override fun format(): Int? {
        val body = request("meta/format", "GET")
        return if (body == "null") null else body.toIntOrNull()
            ?: throw CloudFailure("Cloud format is not supported. Update Circlet.")
    }

    override fun read(table: String, accept: (CloudRow) -> Unit) {
        readPages(table, null, null, accept)
    }

    override fun readRange(table: String, fromKey: String, toKey: String, accept: (CloudRow) -> Unit) {
        readPages(table, fromKey, toKey, accept)
    }

    private fun readPages(table: String, fromKey: String?, toKey: String?, accept: (CloudRow) -> Unit) {
        require(table.matches(Regex("[a-z_]+")))
        var after: String? = null
        while (true) {
            val query = "orderBy=${urlEncode("\"\$key\"")}&limitToFirst=500" +
                ((after ?: fromKey)?.let { "&startAt=${urlEncode(JSONObject.quote(it))}" } ?: "") +
                (toKey?.let { "&endAt=${urlEncode(JSONObject.quote(it))}" } ?: "")
            val body = request("v1/$table", "GET", query = query)
            if (body == "null") return
            val json = JSONObject(body)
            val keys = json.keys().asSequence().sorted().toList()
            val fresh = keys.filter { after == null || it > after!! }
            for (key in fresh) {
                require(key.matches(Regex("[0-9a-f]+"))) { "Invalid cloud row key" }
                val row = json.getJSONObject(key)
                accept(CloudRow(table, key, if (row.getBoolean("deleted")) null else row.getString("data")))
            }
            if (keys.size < 500 || fresh.isEmpty()) return
            after = fresh.last()
        }
    }

    override fun upload(rows: List<CloudRow>) {
        val updates = JSONObject().put("meta/format", 1).put("meta/schema", app.circlet.data.db.CircletDatabase.VERSION)
        for (row in rows) {
            require(row.table.matches(Regex("[a-z_]+")) && row.key.matches(Regex("[0-9a-f]+")))
            val value = JSONObject().put("deleted", row.payload == null)
                .put("updatedAt", JSONObject().put(".sv", "timestamp"))
            if (row.payload != null) value.put("data", row.payload)
            updates.put("v1/${row.table}/${row.key}", value)
        }
        request("", "PATCH", updates.toString(), "print=silent")
    }

    private fun request(path: String, method: String, body: String? = null, query: String = ""): String {
        repeat(2) { attempt ->
            val suffix = if (path.isEmpty()) "" else "/$path"
            val url = "${config.databaseUrl}/users/$uid$suffix.json?auth=${urlEncode(token(attempt > 0))}" +
                if (query.isEmpty()) "" else "&$query"
            val response = http.request(url, method, body)
            if (response.status in 200..299) return response.body.trim()
            if (response.status == 401 && attempt == 0) return@repeat
            throw CloudFailure(when (response.status) {
                401, 403 -> "Cloud access was refused. Check your account and Firebase database rules."
                402, 429 -> "Firebase's free quota is unavailable. Your unsent changes are still queued."
                else -> "Firebase could not save or load data. Your unsent changes are still queued."
            })
        }
        throw CloudFailure("Sign in to Firebase again.")
    }
}

internal fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
