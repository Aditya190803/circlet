package app.circlet.strap.bg

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.circlet.strap.notify.StatusNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
            if (intent == null || intent.getBooleanExtra(RECONNECT, false)) wakes.update { it + 1 }
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
        private const val RECONNECT = "reconnect"
        internal val wakes = MutableStateFlow(0L)

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        internal var hooksNote: StatusNote? = null

        /** True while a route is being recorded; the service then also declares the location type. */
        @Volatile
        var locationWanted: Boolean = false
            private set

        /** Turns the location type on or off and re-enters the foreground with the new type set. */
        fun setLocationWanted(context: Context, wanted: Boolean) {
            locationWanted = wanted
            if (wanted || running) start(context)
        }

        fun start(context: Context, reconnect: Boolean = false) {
            // A connectedDevice foreground service needs this permission; without it startForeground
            // throws, and a started service that never enters the foreground crashes the app.
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "start skipped: Bluetooth connect permission missing")
                return
            }
            val intent = Intent(context, StrapService::class.java).putExtra(RECONNECT, reconnect)
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

        fun ensureRunning(context: Context) {
            if (!running && PairedFlag.isPaired(context)) start(context, reconnect = true)
        }
    }
}
