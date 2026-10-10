package app.circlet.strap.bg

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Runtime permission and battery-exemption checks the onboarding flow asks for. */
object StrapAccess {
    fun runtimePermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        return permissions
    }

    fun missing(context: Context): List<String> =
        runtimePermissions().filter { !granted(context, it) }

    fun scanGranted(context: Context): Boolean = granted(context, Manifest.permission.BLUETOOTH_SCAN)

    fun connectGranted(context: Context): Boolean = granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun notificationsEnabled(context: Context): Boolean =
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    fun batteryExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** System dialog that asks the user to leave this app out of battery optimisation. */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
