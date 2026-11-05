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
 */
internal data class CallNotice(
    val packageName: String,
    val category: String?,
    val callType: Int?,
    val fullScreen: Boolean,
    val chronometer: Boolean,
)

/** The source a notification's app counts as, or null when it is not a call app Circlet listens to. */
internal fun sourceOf(packageName: String, defaultDialer: String?): CallSource? = when {
    packageName in WHATSAPP -> CallSource.WHATSAPP
    packageName == defaultDialer || packageName in DIALERS -> CallSource.PHONE
    else -> null
}

/**
 * True while the call is ringing. A call style answers directly; without one, an incoming call
 * is the full-screen alert, and an answered call shows a running timer instead.
 */
internal fun ringing(notice: CallNotice): Boolean {
    if (notice.category != CATEGORY_CALL) return false
    notice.callType?.let { return it == CALL_TYPE_INCOMING }
    return notice.fullScreen && !notice.chronometer
}
