package app.circlet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Recomputes stored days when the zone changes. The work runs off the main thread. */
class ZoneChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "circlet-zone", isDaemon = true) {
            try {
                AppBootstrap.onZoneChanged(app)
            } finally {
                pending.finish()
            }
        }
    }
}
