package app.circlet.strap.bg

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import app.circlet.strap.StrapModule
import app.circlet.strap.call.CallNotice
import app.circlet.strap.call.ringing
import app.circlet.strap.call.sourceOf

/**
 * Reads incoming-call notifications so the strap can buzz while a call rings.
 * Only call notifications from the phone and WhatsApp are looked at; nothing is stored or forwarded.
 * Calls that Do Not Disturb would hold back do not buzz either.
 */
class CallListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val calls = StrapModule.calls ?: return
        val source = sourceOf(sbn.packageName, defaultDialer())
        if (source == null) return
        val ranking = Ranking()
        val allowed = rankingMap?.getRanking(sbn.key, ranking) != true || ranking.matchesInterruptionFilter()
        calls.posted(sbn.key, source, allowed && ringing(notice(sbn)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        StrapModule.calls?.removed(sbn.key)
    }

    private fun defaultDialer(): String? = getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    private fun notice(sbn: StatusBarNotification): CallNotice {
        val notification = sbn.notification
        val extras = notification.extras
        return CallNotice(
            packageName = sbn.packageName,
            category = notification.category,
            callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0).takeIf { it != 0 },
            fullScreen = notification.fullScreenIntent != null,
            chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
        )
    }
}
