// Ported from OpenStrap protocol/lib/src/commands.dart — buildCommand and the Gen 4 builders.
package app.circlet.protocol

/**
 * Named haptic waveform ids. [SHORT_PULSE] is OpenStrap's `hapticShortPulse`.
 */
object Haptics {
    const val SHORT_PULSE: Int = 2
}

/**
 * Gen 4 command frames. Each builder returns a complete frame ready to write.
 * Sequence numbers are masked to a byte, matching OpenStrap.
 *
 * There is no builder for a command that can harm the strap: force-trim,
 * persistent optical or IMU save, firmware or certificate load, DFU, lock,
 * power-cycle, or reboot.
 */
object Commands {
    private const val COMMAND = 0x23
    private const val GET_HELLO = 0x23
    private const val GET_BATTERY = 0x1A
    private const val GET_DATA_RANGE = 0x22
    private const val SEND_HISTORICAL = 0x16
    private const val ABORT_HISTORICAL = 0x14
    private const val HISTORY_RESULT = 0x17
    private const val SET_CLOCK = 0x0A
    private const val TOGGLE_REALTIME_HR = 0x03
    private const val GENERIC_HR_PROFILE = 0x0E
    private const val RUN_HAPTICS = 0x4F
    private const val STOP_HAPTICS = 0x7A
    private const val SET_ALARM = 0x42
    private const val GET_ALARM = 0x43
    private const val RUN_ALARM = 0x44
    private const val DISABLE_ALARM = 0x45
    private const val GET_ADV_NAME = 0x4C
    private const val SET_ADV_NAME = 0x4D

    /**
     * Set the strap clock. Body is `[u32 epoch seconds][u32 0]`.
     * The second word is the 1/32768-second fraction; whole seconds send zero.
     */
    fun setClock(seq: Int, epochSeconds: Long): ByteArray {
        require(epochSeconds in 0..0xFFFF_FFFFL) { "epochSeconds must fit in a u32" }
        val payload = ByteArray(8)
        putU32(payload, 0, epochSeconds)
        return command(seq, SET_CLOCK, payload)
    }

    fun getHello(seq: Int): ByteArray = command(seq, GET_HELLO, byteArrayOf(0x00))

    /** Empty body, matching OpenStrap `cmdGetBattery`. Padding supplies the trailing zero. */
    fun getBattery(seq: Int): ByteArray = command(seq, GET_BATTERY, ByteArray(0))

    fun getDataRange(seq: Int): ByteArray = command(seq, GET_DATA_RANGE, byteArrayOf(0x00))

    fun sendHistoricalData(seq: Int): ByteArray = command(seq, SEND_HISTORICAL, byteArrayOf(0x00))

    fun abortHistorical(seq: Int): ByteArray = command(seq, ABORT_HISTORICAL, byteArrayOf(0x00))

    /**
     * Acknowledge one history batch. [token] is the 8 bytes at metadata inner[13:21].
     * The body is `[0x01] + token`. Do not acknowledge a complete marker.
     */
    fun historyAck(seq: Int, token: ByteArray): ByteArray {
        require(token.size == 8) { "history token must be 8 bytes, was ${token.size}" }
        val payload = ByteArray(9)
        payload[0] = 0x01
        token.copyInto(payload, 1)
        return command(seq, HISTORY_RESULT, payload)
    }

    fun toggleRealtimeHr(seq: Int, on: Boolean): ByteArray =
        command(seq, TOGGLE_REALTIME_HR, byteArrayOf(if (on) 0x01 else 0x00))

    /**
     * Standard heart-rate profile (0x180D) on or off.
     * OpenStrap names the opcode and does not publish a builder. The body is the
     * same bare on/off byte as [toggleRealtimeHr]. Confirm on a strap before relying on it:
     * the setting is stored on the device and survives a reboot.
     */
    fun toggleGenericHrProfile(seq: Int, on: Boolean): ByteArray =
        command(seq, GENERIC_HR_PROFILE, byteArrayOf(if (on) 0x01 else 0x00))

    /** Play one waveform. Body is `[pattern, 0, 0, 0, 0]`, matching OpenStrap `cmdBuzz`. */
    fun runHaptics(seq: Int, pattern: Int): ByteArray {
        require(pattern in 0..0xFF) { "haptic pattern must fit in a u8" }
        return command(seq, RUN_HAPTICS, byteArrayOf(pattern.toByte(), 0, 0, 0, 0))
    }

    /** Cancel an in-progress buzz. Body is the revision byte, matching OpenStrap `cmdStopHaptics`. */
    fun stopHaptics(seq: Int): ByteArray = command(seq, STOP_HAPTICS, byteArrayOf(0x01))

    /**
     * Arm the on-strap alarm in the rev-1 form that fired on firmware 41.17.4.
     * Body is `[0x01][u32 epoch][u16 sub-seconds][u16 haptic mode]`, with the
     * fraction and the haptic mode both zero for a whole-second wake time.
     */
    fun setAlarm(seq: Int, epochSeconds: Long): ByteArray {
        require(epochSeconds in 0..0xFFFF_FFFFL) { "epochSeconds must fit in a u32" }
        val payload = ByteArray(9)
        payload[0] = 0x01
        putU32(payload, 1, epochSeconds)
        return command(seq, SET_ALARM, payload)
    }

    fun getAlarm(seq: Int): ByteArray = command(seq, GET_ALARM, byteArrayOf(0x01))

    fun runAlarm(seq: Int): ByteArray = command(seq, RUN_ALARM, byteArrayOf(0x01))

    fun disableAlarm(seq: Int): ByteArray = command(seq, DISABLE_ALARM, byteArrayOf(0x01))

    fun getAdvertisingName(seq: Int): ByteArray = command(seq, GET_ADV_NAME, byteArrayOf(0x00))

    /**
     * Rename the advertised name. Body is `[0x01][length][ascii][u32 0]`.
     * Gen 4 accepts 1 to 20 ASCII characters.
     */
    fun setAdvertisingName(seq: Int, name: String): ByteArray {
        require(name.isNotEmpty() && name.length <= 20 && name.all { it.code in 0x20..0x7E }) {
            "advertising name must be 1..20 printable ASCII characters"
        }
        val payload = ByteArray(2 + name.length + 4)
        payload[0] = 0x01
        payload[1] = name.length.toByte()
        for (i in name.indices) payload[2 + i] = name[i].code.toByte()
        return command(seq, SET_ADV_NAME, payload)
    }

    /**
     * The five-frame session open, sequence numbers starting at [startSeq].
     * `sessionInit(0)` is OpenStrap's `initPackets`.
     */
    fun sessionInit(startSeq: Int): List<ByteArray> {
        val base = startSeq and 0xFF
        return listOf(
            getHello(base),
            getAdvertisingName((base + 1) and 0xFF),
            getDataRange((base + 2) and 0xFF),
            getAlarm((base + 3) and 0xFF),
            sendHistoricalData((base + 4) and 0xFF),
        )
    }

    private fun command(seq: Int, opcode: Int, payload: ByteArray): ByteArray {
        val inner = ByteArray(3 + payload.size)
        inner[0] = COMMAND.toByte()
        inner[1] = (seq and 0xFF).toByte()
        inner[2] = (opcode and 0xFF).toByte()
        payload.copyInto(inner, 3)
        return Frames.wrap(inner)
    }
}
