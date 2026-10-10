package app.circlet.strap.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import app.circlet.core.StrapState

// Ported from OpenStrap edge/android EdgeTrackingService.kt — one ongoing notification
// for the connectedDevice foreground service. Every state change goes through here.
/**
 * Builds and posts the strap notification.
 * [foreground] is the call the service makes inside the start deadline.
 * [update] refreshes the same notification afterwards.
 */
internal class StatusNote(private val context: Context) {
    private var latest = StrapState()

    fun current(): StrapState = latest

    fun update(state: StrapState) {
        latest = state
        if (!app.circlet.strap.bg.StrapService.running) return
        ensureChannel()
        manager().notify(ID, build(state))
    }

    fun foreground(service: Service) {
        ensureChannel()
        service.startForeground(ID, build(latest), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

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

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    companion object {
        const val ID: Int = 7101
        const val CHANNEL: String = "circlet_strap"
    }
}
