package app.circlet.ui.more

/** Where a paced breath is. [fill] is 0 for empty lungs and 1 for full, [index] counts breaths from 0. */
data class BreathePhase(val inhaling: Boolean, val fill: Float, val index: Int)

/** Inhale takes the first half of each breath and exhale the second. */
fun phaseAt(elapsedMs: Long, breathsPerMin: Double): BreathePhase {
    require(breathsPerMin > 0) { "breaths per minute must be positive" }
    val cycleMs = 60_000.0 / breathsPerMin
    val elapsed = elapsedMs.coerceAtLeast(0).toDouble()
    val index = (elapsed / cycleMs).toInt()
    val within = (elapsed - index * cycleMs) / (cycleMs / 2)
    return if (within < 1.0) {
        BreathePhase(inhaling = true, fill = within.toFloat(), index = index)
    } else {
        BreathePhase(inhaling = false, fill = (2.0 - within).toFloat().coerceAtLeast(0f), index = index)
    }
}
