package app.circlet.strap.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.circlet.strap.notify.StatusNote

// Ported from OpenStrap edge/android EdgeTrackingService.kt — a sticky connectedDevice
// foreground service so the process survives the app leaving the screen.
/**
 * Keeps the process alive while a strap is paired.
 * The link itself lives in [app.circlet.strap.StrapModule], which is started from the application.
 */
class StrapService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = hooksNote ?: StatusNote(this)
        try {
            note.foreground(this)
            running = true
        } catch (failed: Exception) {
            Log.w(TAG, "startForeground failed: $failed")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StrapService"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        internal var hooksNote: StatusNote? = null

        fun start(context: Context) {
            val intent = Intent(context, StrapService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (failed: Exception) {
                // A background start without a companion or battery exemption is refused.
                // The presence callback and the next watchdog tick try again.
                Log.w(TAG, "start refused: $failed")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StrapService::class.java))
        }
    }
}
