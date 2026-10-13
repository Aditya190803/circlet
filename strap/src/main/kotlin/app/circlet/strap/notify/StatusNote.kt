package app.circlet.strap.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import app.circlet.core.LinkState
import app.circlet.core.StrapState

// Ported from OpenStrap edge/android EdgeTrackingService.kt — one ongoing notification
// for the connectedDevice foreground service. Every state change goes through here.
/**
 * Builds and posts the strap notification.
 * [foreground] is the call the service makes inside the start deadline.
 * [update] refreshes the same notification afterwards.
 */
internal class StatusNote(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile
    private var latest = StrapState()
    private var offWristEpisode = loadOffWristEpisode()

    private val offWristAlert = Runnable { postOffWristAlertIfDue() }

    fun current(): StrapState = latest

    fun update(state: StrapState) {
        latest = state
        handler.post {
            val previous = offWristEpisode
            offWristEpisode = OffWristAlertPolicy.update(previous, state, System.currentTimeMillis())
            persistOffWristEpisode()
            if (state.onWrist == true || state.link == LinkState.UNPAIRED) {
                handler.removeCallbacks(offWristAlert)
                manager().cancel(OFF_WRIST_ID)
            } else {
                scheduleOffWristAlert(state)
            }
            if (app.circlet.strap.bg.StrapService.running) {
                ensureChannel()
                manager().notify(ID, build(state))
            }
        }
    }

    fun foreground(service: Service) {
        ensureChannel()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (app.circlet.strap.bg.StrapService.locationWanted && locationGranted()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        service.startForeground(ID, build(latest), types)
    }

    private fun locationGranted() =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL, "Strap connection", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        channel.description = "Keeps the strap connected while the app is in the background"
        manager().createNotificationChannel(channel)
    }

    fun build(state: StrapState): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Circlet")
            .setContentText(statusLine(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pending)
            .build()
    }

    private fun scheduleOffWristAlert(state: StrapState) {
        handler.removeCallbacks(offWristAlert)
        val episode = offWristEpisode ?: return
        if (episode.alerted || !OffWristAlertPolicy.isEligible(state)) return
        handler.postDelayed(
            offWristAlert,
            OffWristAlertPolicy.remainingMillis(episode, System.currentTimeMillis()),
        )
    }

    private fun postOffWristAlertIfDue() {
        val episode = offWristEpisode ?: return
        val state = latest
        if (!OffWristAlertPolicy.isEligible(state)) return
        if (OffWristAlertPolicy.remainingMillis(episode, System.currentTimeMillis()) > 0) {
            scheduleOffWristAlert(state)
            return
        }

        offWristEpisode = episode.copy(alerted = true)
        persistOffWristEpisode()
        if (!manager().areNotificationsEnabled()) return
        ensureOffWristChannel()
        manager().notify(OFF_WRIST_ID, buildOffWristAlert())
    }

    private fun buildOffWristAlert(): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                OFF_WRIST_ID,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(context, OFF_WRIST_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Band off wrist")
            .setContentText("Your WHOOP band has been off your wrist for 5 minutes.")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureOffWristChannel() {
        val channel = NotificationChannel(
            OFF_WRIST_CHANNEL,
            "Band off wrist",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setShowBadge(false)
        channel.description = "Alerts after the connected band has been off your wrist for five minutes"
        manager().createNotificationChannel(channel)
    }

    private fun loadOffWristEpisode(): OffWristEpisode? {
        if (!preferences.contains(KEY_OFF_WRIST_SINCE)) return null
        return OffWristEpisode(
            startedAtMillis = preferences.getLong(KEY_OFF_WRIST_SINCE, 0L),
            alerted = preferences.getBoolean(KEY_OFF_WRIST_ALERTED, false),
        )
    }

    private fun persistOffWristEpisode() {
        val editor = preferences.edit()
        val episode = offWristEpisode
        if (episode == null) {
            editor.remove(KEY_OFF_WRIST_SINCE).remove(KEY_OFF_WRIST_ALERTED)
        } else {
            editor.putLong(KEY_OFF_WRIST_SINCE, episode.startedAtMillis)
                .putBoolean(KEY_OFF_WRIST_ALERTED, episode.alerted)
        }
        editor.apply()
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val ID: Int = 7101
        const val CHANNEL: String = "circlet_strap"
        const val OFF_WRIST_ID: Int = 7102
        const val OFF_WRIST_CHANNEL: String = "circlet_off_wrist"
        private const val PREFERENCES = "circlet_strap"
        private const val KEY_OFF_WRIST_SINCE = "off_wrist_since"
        private const val KEY_OFF_WRIST_ALERTED = "off_wrist_alerted"
    }
}
