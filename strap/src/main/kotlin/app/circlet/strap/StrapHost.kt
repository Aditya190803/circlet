package app.circlet.strap

import app.circlet.core.StrapState

/** What the Android process does when the link pairs, drops, or changes state. */
internal interface StrapHost {
    fun onPaired(address: String, askToAssociate: Boolean)
    fun onForgotten()
    fun notify(state: StrapState)
}
