package app.circlet.data.repo

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

internal data class DevicePermissionChecks(
    val scan: Boolean,
    val connect: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val companion: Boolean,
)

internal fun readDeviceChecks(context: Context): DevicePermissionChecks {
    fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val notifications = if (Build.VERSION.SDK_INT >= 33) {
        granted(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
    val battery = try {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (error: RuntimeException) {
        false
    }
    val companion = try {
        context.getSystemService(CompanionDeviceManager::class.java)?.associations?.isNotEmpty() == true
    } catch (error: RuntimeException) {
        false
    }
    return DevicePermissionChecks(
        scan = granted(android.Manifest.permission.BLUETOOTH_SCAN),
        connect = granted(android.Manifest.permission.BLUETOOTH_CONNECT),
        notifications = notifications,
        battery = battery,
        companion = companion,
    )
}
