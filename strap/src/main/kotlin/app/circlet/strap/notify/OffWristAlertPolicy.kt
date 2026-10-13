package app.circlet.strap.notify

import app.circlet.core.LinkState
import app.circlet.core.StrapState

internal const val OFF_WRIST_ALERT_DELAY_MS = 5 * 60 * 1000L

internal data class OffWristEpisode(
    val startedAtMillis: Long,
    val alerted: Boolean = false,
)

internal object OffWristAlertPolicy {
    fun update(episode: OffWristEpisode?, state: StrapState, nowMillis: Long): OffWristEpisode? = when {
        state.link == LinkState.UNPAIRED || state.onWrist == true -> null
        state.onWrist == false -> episode ?: OffWristEpisode(nowMillis)
        else -> episode
    }

    fun remainingMillis(episode: OffWristEpisode, nowMillis: Long): Long =
        (episode.startedAtMillis + OFF_WRIST_ALERT_DELAY_MS - nowMillis).coerceAtLeast(0)

    fun isEligible(state: StrapState): Boolean =
        state.link == LinkState.CONNECTED && state.onWrist == false
}
