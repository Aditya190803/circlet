package app.circlet.strap.call

import app.circlet.core.CallSource

private const val CATEGORY_CALL = "call"

/** `Notification.CallStyle.CALL_TYPE_INCOMING`. */
internal const val CALL_TYPE_INCOMING = 1

private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

/** Dialers that post the incoming-call notification on common phones, besides the default dialer. */
private val DIALERS = setOf(
    "com.android.dialer",
    "com.android.incallui",
    "com.android.server.telecom",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.samsung.android.incallui",
)

/**
 * What a posted notification says about a call.
 * [callType] is the call-style type when the app used one, else null.
 * [fullScreen] is true when the notification carries a full-screen intent; [chronometer] when it shows a running timer.
 * [phoneRinging] is true when the notification comes from the dialer while the system ringer is sounding.
 */
internal data class CallNotice(
    val packageName: String,
    val category: String?,
    val callType: Int?,
    val fullScreen: Boolean,
    val chronometer: Boolean,
    val phoneRinging: Boolean = false,
)

/** The source a notification's app counts as, or null when it is not a call app Circlet listens to. */
internal fun sourceOf(packageName: String, defaultDialer: String?): CallSource? = when {
    packageName in WHATSAPP -> CallSource.WHATSAPP
    packageName == defaultDialer || packageName in DIALERS -> CallSource.PHONE
    else -> null
}

/**
 * True while the call is ringing. An incoming call style answers directly; without one, an
 * incoming call is the full-screen alert, and an answered call shows a running timer instead.
 * Some dialers (Samsung's among them) post neither shape, so a dialer call notice also rings
 * while the system ringer sounds.
 */
internal fun ringing(notice: CallNotice): Boolean {
    if (notice.chronometer) return false
    val call = notice.category == CATEGORY_CALL
    if (call && notice.callType == CALL_TYPE_INCOMING) return true
    if (call && notice.callType == null && notice.fullScreen) return true
    return notice.phoneRinging && (call || notice.fullScreen)
}
