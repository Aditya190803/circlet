package app.circlet.data.cloud

import app.circlet.core.RecordingStorage
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import org.json.JSONObject

/**
 * Step and route files stay on this phone. Each change queues only the 16 KB chunks it touched,
 * so an appended GPS fix does not re-upload the whole route.
 */
internal class CloudRecordings(private val session: FirebaseCloudSession) : RecordingStorage {
    override val root: String = session.context.filesDir.absolutePath

    @Synchronized override fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    @Synchronized override fun paths(): List<String> = listOf("steps", "routes").flatMap { directory ->
        File(root, directory).listFiles().orEmpty().filter { it.isFile }.map { "$directory/${it.name}" }.filter(PATH::matches)
    }

    @Synchronized override fun write(path: String, bytes: ByteArray) {
        val target = file(path)
        val before = chunkCount(target.takeIf { it.isFile }?.length() ?: 0)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        val parts = chunks(bytes)
        val rows = parts.mapIndexed { index, part -> row(path, index, part) } +
            (parts.size until before).map { CloudRow(TABLE, key(path, it), null) }
        queue(rows)
    }

    @Synchronized override fun append(path: String, bytes: ByteArray) {
        val target = file(path)
        val start = ((target.takeIf { it.isFile }?.length() ?: 0) / CHUNK_SIZE).toInt()
        target.parentFile?.mkdirs()
        target.appendBytes(bytes)
        queue(chunksFrom(path, start))
    }

    @Synchronized override fun delete(path: String): Boolean {
        val target = file(path)
        if (!target.isFile) return false
        val count = chunkCount(target.length())
        if (!target.delete()) return false
        queue((0 until count).map { CloudRow(TABLE, key(path, it), null) })
        return true
    }

    /** A restore writes a chunk only into a file this phone does not already hold more of. */
    @Synchronized fun restore(row: CloudRow) {
        val payload = row.payload ?: return
        val json = unpackCloudJson(payload)
        val path = json.getString("path")
        val index = json.getInt("chunk")
        require(index in 0..1_000_000 && row.key == key(path, index)) { "Cloud recording key does not match its data." }
        val bytes = Base64.getDecoder().decode(json.getString("bytes"))
        require(bytes.size <= CHUNK_SIZE)
        val target = file(path)
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { output ->
            val offset = index.toLong() * CHUNK_SIZE
            if (output.length() >= offset + bytes.size) return
            output.seek(offset)
            output.write(bytes)
        }
    }

    /** First sign-in on a phone with recordings: queue every chunk once. */
    @Synchronized fun importExisting() {
        for (path in paths()) queue(chunksFrom(path, 0))
    }

    private fun queue(rows: List<CloudRow>) {
        if (rows.isEmpty()) return
        session.outbox.put(rows)
        session.queued()
    }

    private fun chunksFrom(path: String, start: Int): List<CloudRow> {
        val bytes = file(path).readBytes()
        return chunks(bytes).drop(start).mapIndexed { offset, part -> row(path, start + offset, part) }
    }

    private fun file(path: String): File {
        require(PATH.matches(path)) { "Invalid recording path" }
        return File(root, path)
    }

    private fun row(path: String, index: Int, bytes: ByteArray) = CloudRow(TABLE, key(path, index),
        packCloudJson(JSONObject().put("path", path).put("chunk", index).put("bytes", Base64.getEncoder().encodeToString(bytes))))

    private fun key(path: String, index: Int): String = cloudKey(path) + "00" + "%08x".format(index)

    private fun chunkCount(length: Long): Int = ((length + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    private fun chunks(bytes: ByteArray): List<ByteArray> =
        (bytes.indices step CHUNK_SIZE).map { bytes.copyOfRange(it, minOf(it + CHUNK_SIZE, bytes.size)) }

    private companion object {
        const val TABLE = "recordings"
        const val CHUNK_SIZE = 16_384
        val PATH = Regex("(?:steps/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.csv|routes/[A-Za-z0-9._-]+\\.jsonl)")
    }
}
