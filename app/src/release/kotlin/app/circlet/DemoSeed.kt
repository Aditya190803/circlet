package app.circlet

import android.content.Context
import app.circlet.core.StrapPorts

/** Release builds never seed demo data. The debug source set holds the real seeding. */
object DemoSeed {
    @Suppress("UNUSED_PARAMETER")
    suspend fun seedIfFresh(context: Context, ports: StrapPorts) = Unit
}
