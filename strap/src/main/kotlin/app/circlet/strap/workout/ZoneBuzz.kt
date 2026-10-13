package app.circlet.strap.workout

/** How long a new zone must hold before it counts as a change. */
internal const val ZONE_HOLD_MILLIS = 10_000L

/** Minimum gap between two zone buzzes. */
internal const val ZONE_BUZZ_GAP_MILLIS = 30_000L

internal fun interface ZoneBuzzer {
    fun buzz()
}

/**
 * [settled] is the zone the user was last told about, or silently started in.
 * [candidate] is a different zone seen since [candidateSinceMillis].
 */
internal data class ZoneBuzzState(
    val settled: Int? = null,
    val candidate: Int? = null,
    val candidateSinceMillis: Long = 0,
    val lastBuzzMillis: Long? = null,
)

internal data class ZoneBuzzStep(val state: ZoneBuzzState, val buzz: Boolean)

/**
 * One heart-rate reading in, the next state and whether to buzz out.
 * A buzz needs the new zone held for [ZONE_HOLD_MILLIS] and [ZONE_BUZZ_GAP_MILLIS] since the last buzz.
 * While paused or off nothing buzzes and a pending change is dropped; when off the settled zone
 * still follows the reading, so turning the setting on never buzzes for an old change.
 */
internal fun stepZoneBuzz(
    state: ZoneBuzzState,
    zone: Int?,
    nowMillis: Long,
    enabled: Boolean,
    paused: Boolean,
): ZoneBuzzStep {
    if (paused || zone == null) return ZoneBuzzStep(state.copy(candidate = null), false)
    if (!enabled || state.settled == null) return ZoneBuzzStep(state.copy(settled = zone, candidate = null), false)
    if (zone == state.settled) return ZoneBuzzStep(state.copy(candidate = null), false)
    val since = if (state.candidate == zone) state.candidateSinceMillis else nowMillis
    val held = nowMillis - since >= ZONE_HOLD_MILLIS
    val spaced = state.lastBuzzMillis?.let { nowMillis - it >= ZONE_BUZZ_GAP_MILLIS } ?: true
    if (held && spaced) {
        return ZoneBuzzStep(ZoneBuzzState(settled = zone, lastBuzzMillis = nowMillis), true)
    }
    return ZoneBuzzStep(state.copy(candidate = zone, candidateSinceMillis = since), false)
}
