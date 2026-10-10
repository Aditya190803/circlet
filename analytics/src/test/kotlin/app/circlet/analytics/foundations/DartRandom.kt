package app.circlet.analytics.foundations

/**
 * Dart VM `Random` as of SDK 3.12: 64-bit multiply-with-carry after a Thomas Wang seed mix,
 * cranked four times in the constructor. Used only so synthetic series match the Dart vectors.
 * Shift counts at or above 64 yield 0 on that VM; every shift in the mixer is below 64.
 */
internal class DartRandom(seed: Long) {
    private var state: Long = setupSeed(seed)

    init {
        repeat(4) { nextState() }
    }

    fun nextDouble(): Double {
        val hi = nextPowerOfTwo(1 shl 26)
        val lo = nextPowerOfTwo(1 shl 27)
        return (hi * POW2_27 + lo) / POW2_53
    }

    /** Dart VM `Random.nextInt`: power-of-two mask, otherwise 32-bit rejection sampling. */
    fun nextInt(max: Int): Int {
        require(max > 0)
        if ((max and -max) == max) {
            nextState()
            return ((state and 0xFFFFFFFFL) and (max - 1).toLong()).toInt()
        }
        val pow2_32 = 1L shl 32
        while (true) {
            nextState()
            val rnd32 = state and 0xFFFFFFFFL
            val result = rnd32 % max
            if (rnd32 - result + max <= pow2_32) return result.toInt()
        }
    }

    private fun nextPowerOfTwo(max: Int): Int {
        nextState()
        return ((state and 0xFFFFFFFFL) and (max - 1).toLong()).toInt()
    }

    private fun nextState() {
        val a = 0xffffda61L
        val stateLo = state and 0xFFFFFFFFL
        val stateHi = state ushr 32
        state = a * stateLo + stateHi
    }

    companion object {
        private const val POW2_27 = 134217728.0
        private const val POW2_53 = 9007199254740992.0

        private fun setupSeed(n0: Long): Long {
            var n = n0
            n = n.inv() + (n shl 21)
            n = n xor (n ushr 24)
            n *= 265
            n = n xor (n ushr 14)
            n *= 21
            n = n xor (n ushr 28)
            n += n shl 31
            if (n == 0L) n = 0x5a17
            return n
        }
    }
}
