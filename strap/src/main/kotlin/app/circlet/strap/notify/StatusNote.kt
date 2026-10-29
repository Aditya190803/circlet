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
import android.os.SystemClock
import android.util.Log
import app.circlet.core.ClockStyle
import app.circlet.core.DoubleTapAction
import app.circlet.core.LinkState
import app.circlet.core.MassUnit
import app.circlet.core.StrapState
import app.circlet.core.UserSettings
import java.time.LocalTime
import kotlin.math.roundToInt

// Ported from OpenStrap edge/android EdgeTrackingService.kt — one ongoing notification
// for the connectedDevice foreground service. Every state change goes through here.
/**
 * Builds and posts the strap notification.
 * [foreground] is the call the service makes inside the start deadline.
 * [update] refreshes the same notification afterwards.
 */
internal class StatusNote(
    private val context: Context,
    private val settingsSource: () -> UserSettings? = { null },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Volatile
    private var latest = StrapState()
    private var offWristEpisode = loadOffWristEpisode()
    private var lowBatteryArmed = preferences.getBoolean(KEY_LOW_BATTERY_ARMED, true)

    private val offWristAlert = Runnable { postOffWristAlertIfDue() }

    @Volatile
    private var renderQueued = false
    private var lastRenderAt = 0L
    private val render = Runnable {
        renderQueued = false
        lastRenderAt = SystemClock.uptimeMillis()
        show(latest)
    }

    fun current(): StrapState = latest

    /** A drain publishes many times a second; render the newest state at most once per [RENDER_GAP_MS]. */
    fun update(state: StrapState) {
        latest = state
        if (renderQueued) return
        renderQueued = true
        val wait = (lastRenderAt + RENDER_GAP_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed(render, wait)
    }

    private fun show(state: StrapState) {
        val previous = offWristEpisode
        offWristEpisode = OffWristAlertPolicy.update(previous, state, System.currentTimeMillis())
        persistOffWristEpisode()
        if (state.onWrist == true || state.link == LinkState.UNPAIRED) {
            handler.removeCallbacks(offWristAlert)
            manager().cancel(OFF_WRIST_ID)
        } else {
            scheduleOffWristAlert(state)
        }
        checkLowBattery(state)
        if (app.circlet.strap.bg.StrapService.running) {
            ensureChannel()
            manager().notify(ID, build(state))
        }
    }

    fun foreground(service: Service) {
        ensureChannel()
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (app.circlet.strap.bg.StrapService.locationWanted && locationGranted()) {
            try {
                service.startForeground(ID, build(latest), device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                return
            } catch (refused: SecurityException) {
                // Android refuses the location type while the app is in the background; keep the link alive.
                Log.w(TAG, "location foreground refused: $refused")
            }
        }
        service.startForeground(ID, build(latest), device)
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
        val settings = settings()
        if (!settings.offWristAlerts || isQuiet(settings)) return
        if (!manager().areNotificationsEnabled()) return
        ensureOffWristChannel()
        manager().notify(OFF_WRIST_ID, buildOffWristAlert())
    }

    private fun checkLowBattery(state: StrapState) {
        val settings = settings()
        val decision = LowBatteryAlertPolicy.evaluate(
            armed = lowBatteryArmed,
            state = state,
            threshold = settings.lowBatteryThreshold,
            enabled = settings.lowBatteryAlerts,
            quiet = isQuiet(settings),
        )
        if (decision.armed != lowBatteryArmed) {
            lowBatteryArmed = decision.armed
            preferences.edit().putBoolean(KEY_LOW_BATTERY_ARMED, decision.armed).apply()
        }
        val percent = state.batteryPercent
        if (!decision.fire || percent == null || !manager().areNotificationsEnabled()) return
        ensureLowBatteryChannel()
        manager().notify(LOW_BATTERY_ID, buildLowBatteryAlert(percent.roundToInt()))
    }

    private fun settings(): UserSettings = settingsSource() ?: UserSettings(
        MassUnit.KILOGRAM,
        ClockStyle.MATCH_SYSTEM,
        DoubleTapAction.STOP_ALARM,
        heartRateBroadcast = false,
    )

    private fun isQuiet(settings: UserSettings): Boolean {
        val now = LocalTime.now()
        return LowBatteryAlertPolicy.inQuietHours(
            now.hour * 60 + now.minute,
            settings.quietStartMinute,
            settings.quietEndMinute,
        )
    }

    private fun buildLowBatteryAlert(percent: Int): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                context,
                LOW_BATTERY_ID,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(context, LOW_BATTERY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Strap battery low")
            .setContentText("Your WHOOP band is at $percent%. Charge it soon.")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureLowBatteryChannel() {
        val channel = NotificationChannel(
            LOW_BATTERY_CHANNEL,
            "Strap battery low",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setShowBadge(false)
        channel.description = "Alerts when the band battery drops to your chosen level"
        manager().createNotificationChannel(channel)
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
        private const val TAG = "StatusNote"
        private const val RENDER_GAP_MS = 1_000L
        const val ID: Int = 7101
        const val CHANNEL: String = "circlet_strap"
        const val OFF_WRIST_ID: Int = 7102
        const val OFF_WRIST_CHANNEL: String = "circlet_off_wrist"
        const val LOW_BATTERY_ID: Int = 7103
        const val LOW_BATTERY_CHANNEL: String = "circlet_low_battery"
        private const val KEY_LOW_BATTERY_ARMED = "low_battery_armed"
        private const val PREFERENCES = "circlet_strap"
        private const val KEY_OFF_WRIST_SINCE = "off_wrist_since"
        private const val KEY_OFF_WRIST_ALERTED = "off_wrist_alerted"
    }
}
