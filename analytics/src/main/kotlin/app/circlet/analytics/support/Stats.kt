package app.circlet.analytics.support

// Dart's Random(seed) and Fisher-Yates shuffle. The permutation tests are
// seeded, so the same history has to yield the same p. The statistics these
// files used to copy live in foundations; the wrappers below are that copy.

internal fun mean(xs: List<Double>): Double? = app.circlet.analytics.foundations.mean(xs)

internal fun stddev(xs: List<Double>): Double? = app.circlet.analytics.foundations.stddev(xs)

internal fun percentileSorted(sorted: List<Double>, p: Double): Double? =
    app.circlet.analytics.foundations.percentileSorted(sorted, p)

internal fun percentile(values: List<Double>, p: Double): Double? =
    app.circlet.analytics.foundations.percentile(values, p)

internal fun median(xs: List<Double>): Double? = app.circlet.analytics.foundations.median(xs)

internal fun mad(xs: List<Double>, scaled: Boolean = true): Double? =
    app.circlet.analytics.foundations.mad(xs, scaled)

internal fun sampleCadenceSeconds(tsSec: List<Double>): Double? =
    app.circlet.analytics.foundations.sampleCadenceSeconds(tsSec)

internal fun round6(x: Double): Double? = app.circlet.analytics.foundations.round6(x)

internal fun calendarDays(dates: List<String>): List<Int> =
    app.circlet.analytics.foundations.calendarDays(dates)

internal fun averageRanks(xs: List<Double>): List<Double> =
    app.circlet.analytics.foundations.averageRanks(xs)

internal fun benjaminiHochberg(ps: List<Double?>): List<Double?> =
    app.circlet.analytics.foundations.benjaminiHochberg(ps)

internal class DartRandom(seed: Int) {
    private var state = seed.toLong() and 0xFFFFFFFFFFFFL

    /** Next [bits] of the 48-bit LCG, unsigned. [bits] is 1..32. */
    private fun next(bits: Int): Long {
        state = (0x5DEECE66DL * state + 0xBL) and 0xFFFFFFFFFFFFL
        return state ushr (48 - bits)
    }

    /**
     * Dart's rejection sample: redraw while `rnd32 - result + max` exceeds 2^32.
     * The bound is a Long. `1 shl 31` is a negative Int and the loop never ends.
     */
    fun nextInt(max: Int): Int {
        require(max > 0)
        if (max and -max == max) return (next(31) and (max - 1).toLong()).toInt()
        while (true) {
            val rnd32 = next(32)
            val result = (rnd32 % max).toInt()
            if (rnd32 - result + max <= 0x1_0000_0000L) return result
        }
    }
}

internal fun <T> MutableList<T>.dartShuffle(random: DartRandom) {
    var length = size
    while (length > 1) {
        val pos = random.nextInt(length)
        length--
        val tmp = this[length]
        this[length] = this[pos]
        this[pos] = tmp
    }
}

internal fun finite(x: Double?): Boolean = x != null && x.isFinite()
