// Ported from OpenStrap protocol/lib/src/control.dart — parseHello, parseCommandResponse,
// parseEvent, parseMetadata, and the battery / data-range / alarm / name branches.
package app.circlet.protocol

/**
 * Identity read from a hello body. [charging] and [wristOn] stay null: no byte
 * in a captured hello is a charging or wrist flag. Those come from events.
 * [firmware] is the firmware commit hex, located by content because its offset drifts.
 */
data class Hello(
    val batteryPercent: Double?,
    val charging: Boolean?,
    val wristOn: Boolean?,
    val serial: String?,
    val firmware: String?,
)

/** Oldest and newest stored seconds, plus the ring-buffer counters when they are plausible. */
data class DataRange(
    val oldestEpochSeconds: Long?,
    val newestEpochSeconds: Long?,
    val backlog: Backlog?,
)

data class Backlog(
    val writtenPage: Long,
    val usedRecords: Long,
    val capacityPages: Long,
    val trimPage: Long,
    val wrapCount: Long,
    val freeRecords: Long,
)

/**
 * Armed alarm time. [active] is present on the rich form only: exactly 1 means armed.
 * The rev-1 form has no active flag, so [active] is null there.
 */
data class AlarmTime(
    val epochSeconds: Long,
    val active: Boolean?,
)

private const val MIN_UNIX = 1_700_000_000L
private const val MAX_UNIX = 4_102_444_800L

private fun plausibleUnix(v: Long): Boolean = v in MIN_UNIX..MAX_UNIX

/**
 * Battery percentage from a GET_BATTERY_LEVEL body.
 *
 * [body] is the response data after the result byte (inner[5:]). The value is a
 * little-endian u16 in tenths of a percent. Outside 0..100 is not a battery reading.
 */
fun batteryPercent(body: ByteArray): Double? {
    if (body.size < 2) return null
    val pct = roundAway(u16(body, 0) / 10.0, 1)
    return if (pct in 0.0..100.0) pct else null
}

/**
 * Hello parsed by content. [body] is the response data after the result byte (inner[5:]).
 */
fun hello(body: ByteArray): Hello {
    // OpenStrap refuses a payload shorter than 10 bytes. This body starts two bytes later.
    if (body.size < 8) return Hello(null, null, null, null, null)
    var battery: Double? = null
    for (off in 1 until 8) {
        if (off + 2 > body.size) break
        val v = u16(body, off)
        if (v in 10..1000) {
            val pct = roundAway(v / 10.0, 1)
            if (pct in 0.0..100.0) {
                battery = pct
                break
            }
        }
    }
    val serial = cstr(body, 14).takeIf { validSerial(it) }
    var firmware: String? = null
    for (run in asciiRuns(body, 14, 16)) {
        if (run.length >= 16 && run.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            firmware = run
            break
        }
    }
    return Hello(battery, charging = null, wristOn = null, serial, firmware)
}

/**
 * Data-range reply. [body] is the response data after the result byte.
 * Returns null when the revision is not 1 or neither the clock range nor the page counters hold.
 */
fun dataRange(body: ByteArray): DataRange? {
    if (body.isEmpty() || u8(body, 0) != 1) return null
    var oldest: Long? = null
    var newest: Long? = null
    if (body.size >= 61) {
        val o = u32(body, 33)
        val n = u32(body, 57)
        if (plausibleUnix(o) && plausibleUnix(n) && o <= n) {
            oldest = o
            newest = n
        }
    }
    var backlog: Backlog? = null
    if (body.size >= 33) {
        val written = u32(body, 9)
        val capacity = u32(body, 21)
        if (capacity > 0 && written <= capacity) {
            backlog = Backlog(
                writtenPage = written,
                usedRecords = u32(body, 25),
                capacityPages = capacity,
                trimPage = u32(body, 13),
                wrapCount = u32(body, 17),
                freeRecords = u32(body, 29),
            )
        }
    }
    if (oldest == null && backlog == null) return null
    return DataRange(oldest, newest, backlog)
}

/** Alarm time from a GET_ALARM_TIME body (bytes after the result). Unknown forms return null. */
fun alarmTime(body: ByteArray): AlarmTime? {
    if (body.isEmpty()) return null
    return when (u8(body, 0)) {
        0x01 -> if (body.size >= 5) AlarmTime(u32(body, 1), active = null) else null
        0x04 -> if (body.size >= 6) AlarmTime(u32(body, 2), active = u8(body, 1) == 1) else null
        else -> null
    }
}

/**
 * Advertising name from a successful GET_ADVERTISING_NAME body (bytes after the result).
 * Call this only when the response result is success. A failed reply's bytes are not a name.
 */
fun advertisingName(body: ByteArray): String? {
    if (body.size > 3) {
        val len = u8(body, 2)
        if (len in 1..20) {
            val name = printableRun(body, 3, 3 + len)
            if (name.isNotEmpty()) return name
        }
    }
    var start = 0
    while (start < body.size && u8(body, start) < 0x20) start++
    val fallback = printableRun(body, start, body.size)
    return fallback.ifEmpty { null }
}

private fun cstr(b: ByteArray, start: Int): String {
    if (start < 0 || start >= b.size) return ""
    val sb = StringBuilder()
    for (i in start until b.size) {
        val c = u8(b, i)
        if (c == 0) break
        if (c < 0x20 || c >= 0x7F) return ""
        sb.append(c.toChar())
    }
    return sb.toString()
}

private fun validSerial(s: String): Boolean {
    if (s.length !in 6..13) return false
    return s.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }
}

private fun asciiRuns(data: ByteArray, start: Int, minLen: Int): List<String> {
    val runs = ArrayList<String>()
    val cur = StringBuilder()
    for (i in start until data.size) {
        val c = u8(data, i)
        if (c in 0x20..0x7E) {
            cur.append(c.toChar())
        } else {
            if (cur.length >= minLen) runs.add(cur.toString())
            cur.clear()
        }
    }
    if (cur.length >= minLen) runs.add(cur.toString())
    return runs
}

private fun printableRun(p: ByteArray, from: Int, until: Int): String {
    val sb = StringBuilder()
    var i = from
    val end = minOf(until, p.size)
    while (i < end) {
        val c = u8(p, i)
        if (c == 0) break
        if (c in 0x20..0x7E) sb.append(c.toChar())
        i++
    }
    return sb.toString().trim()
}
