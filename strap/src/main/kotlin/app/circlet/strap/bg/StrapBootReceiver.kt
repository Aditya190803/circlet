package app.circlet.strap.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Ported from OpenStrap edge/android BootReceiver.kt — restart the service after boot
// and after the app package is replaced, when a strap is paired.
class StrapBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            QUICKBOOT,
            -> if (PairedFlag.isPaired(context)) StrapService.start(context)
        }
    }

    private companion object {
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
