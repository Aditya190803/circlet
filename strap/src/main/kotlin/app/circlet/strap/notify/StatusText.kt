package app.circlet.strap.notify

import app.circlet.core.LinkState
import app.circlet.core.StrapState
import kotlin.math.roundToInt

/** The one line the foreground notification shows. */
internal fun statusLine(state: StrapState): String {
    val battery = state.batteryPercent
    return when {
        state.link == LinkState.CONNECTED && state.sync.draining -> "Syncing history"
        state.link == LinkState.CONNECTED && battery != null -> {
            val charging = if (state.charging == true) " · charging" else ""
            "Connected · ${battery.roundToInt()}% battery$charging"
        }
        state.link == LinkState.CONNECTED -> "Connected"
        state.link == LinkState.UNPAIRED -> "No strap paired"
        else -> "Looking for your strap"
    }
}
