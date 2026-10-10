package app.circlet.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParityTest {
    @Test
    fun gen4CasesMatchTheCapturedOracle() {
        val text = javaClass.getResource("/decode_parity_cases.json")!!.readText()
        val cases = Json.read(text) as List<*>
        assertEquals(2934, cases.size)
        val gen4 = setOf("r24", "record", "rr", "accel")
        var ran = 0
        var passed = 0
        val skipped = linkedMapOf<String, Int>()
        val failures = ArrayList<String>()
        val byKind = linkedMapOf<String, Int>()
        for ((index, item) in cases.withIndex()) {
            val case = item as Map<*, *>
            val kind = case["kind"] as String
            if (kind !in gen4) {
                skipped[kind] = (skipped[kind] ?: 0) + 1
                continue
            }
            ran++
            byKind[kind] = (byKind[kind] ?: 0) + 1
            val bytes = fromHex(case["hex"] as String)
            val expected = case["out"]
            val actual = decode(kind, bytes)
            val diff = diff(actual, expected, "case[$index]($kind)", kind == "r24")
            if (diff == null) passed++ else if (failures.size < 12) failures.add(diff)
        }
        val summary = "ran=$ran passed=$passed skipped=$skipped byKind=$byKind"
        println(summary)
        assertEquals(summary + "\n" + failures.joinToString("\n"), ran, passed)
        assertTrue(failures.isEmpty())
    }

    private fun decode(kind: String, bytes: ByteArray): Any? = when (kind) {
        "r24" -> r24Map(decodeR24(bytes))
        "record" -> recordMap(decodeRecord(bytes))
        "rr" -> rrMap(realtimeRr(bytes))
        "accel" -> accelMap(frameAccel(bytes))
        else -> error(kind)
    }

    private fun r24Map(record: R24?): Map<String, Any?>? {
        record ?: return null
        return mapOf(
            "ts_epoch" to record.epochSecond,
            "ts_subsec" to record.subSeconds.toLong(),
            "counter" to record.counter,
            "hr" to record.heartRate.toLong(),
            "rr_count" to record.rrIntervalsMs.size.toLong(),
            "rr_intervals_ms" to record.rrIntervalsMs.map { it.toLong() },
            "ppg_green" to record.greenPpg.toLong(),
            "accel_g" to record.gravity,
            "spo2_red_raw" to record.red.toLong(),
            "spo2_ir_raw" to record.infrared.toLong(),
            "ambient_raw" to record.ambient.toLong(),
            "raw_tail" to hex(record.rawTail),
        )
    }

    private fun recordMap(record: DecodedRecord?): Map<String, Any?>? {
        record ?: return null
        return mapOf(
            "ts" to record.ts,
            "hr" to record.hr.toLong(),
            "activity" to record.activity,
            "steps_inc" to record.stepsInc?.toLong(),
            "wrist_on" to record.wristOn,
            "rec_type" to record.recType.toLong(),
        )
    }

    private fun rrMap(rr: RealtimeRr?): Map<String, Any?>? {
        rr ?: return null
        return mapOf("ts" to rr.ts, "rr_ms" to rr.intervals.map { it.toLong() })
    }

    private fun accelMap(frame: ImuFrame?): Map<String, Any?>? {
        frame ?: return null
        return mapOf("ts" to frame.ts, "idx" to frame.idx.toLong(), "mags" to frame.mags)
    }
}

private val IGNORED_R24 = setOf("ppg_red_ir", "skin_contact", "skin_temp_raw")

private fun diff(actual: Any?, expected: Any?, path: String, r24: Boolean): String? {
    if (expected == null) return if (actual == null) null else "$path: expected null, got $actual"
    if (actual == null) return "$path: expected $expected, got null"
    if (expected is Boolean) return if (actual == expected) null else "$path: $actual != $expected"
    if (expected is String) return if (actual == expected) null else "$path: $actual != $expected"
    if (expected is Long && actual is Long) {
        return if (expected == actual) null else "$path: $actual != $expected"
    }
    if (expected is Double || actual is Double) {
        val e = (expected as Number).toDouble()
        val a = (actual as Number).toDouble()
        return if (abs(a - e) <= 1e-4) null else "$path: $a != $e"
    }
    if (expected is List<*>) {
        if (actual !is List<*>) return "$path: expected list"
        if (actual.size != expected.size) return "$path: length ${actual.size} != ${expected.size}"
        for (i in expected.indices) {
            val child = diff(actual[i], expected[i], "$path[$i]", false) ?: continue
            return child
        }
        return null
    }
    if (expected is Map<*, *>) {
        if (actual !is Map<*, *>) return "$path: expected map"
        for ((key, value) in expected) {
            if (r24 && key in IGNORED_R24) continue
            val child = diff(actual[key], value, "$path.$key", false) ?: continue
            return child
        }
        for (key in actual.keys) {
            if (!expected.containsKey(key)) return "$path.$key: unexpected"
        }
        return null
    }
    return "$path: ${actual::class} vs ${expected::class}"
}

private object Json {
    fun read(text: String): Any? = Parser(text).parse()

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Any? {
            skip()
            return value()
        }

        private fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                val key = str()
                skip()
                i++
                out[key] = value()
                skip()
                if (s[i] == '}') { i++; return out }
                i++
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skip()
                if (s[i] == ']') { i++; return out }
                i++
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    sb.append(
                        when (val e = s[i]) {
                            'u' -> {
                                val hex = s.substring(i + 1, i + 5)
                                i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> e
                        },
                    )
                } else {
                    sb.append(s[i])
                }
                i++
            }
            i++
            return sb.toString()
        }

        private fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            val whole = !s.substring(start, i).contains('.')
            var dotted = false
            if (i < s.length && s[i] == '.') {
                dotted = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                dotted = true
                i++
                if (s[i] == '+' || s[i] == '-') i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val token = s.substring(start, i)
            return if (!dotted && whole) token.toLong() else token.toDouble()
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
