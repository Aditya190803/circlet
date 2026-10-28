package app.circlet.strap.bg

import android.content.Context
import app.circlet.core.StrapState
import app.circlet.strap.StrapHost
import app.circlet.strap.notify.StatusNote

internal class AndroidHost(
    private val context: Context,
    private val note: StatusNote,
) : StrapHost {
    override fun onPaired(address: String, askToAssociate: Boolean) {
        PairedFlag.set(context, address)
        StrapWatchdog.schedule(context)
        StrapService.start(context)
        StrapCompanion.associateIfAttached(context, address)
    }

    override fun onForgotten() {
        PairedFlag.address(context)?.let { StrapCompanion.stopObserving(context, it) }
        PairedFlag.set(context, null)
        StrapWatchdog.cancel(context)
        StrapService.stop(context)
        note.update(app.circlet.core.StrapState())
    }

    override fun notify(state: StrapState) {
        note.update(state)
    }
}
