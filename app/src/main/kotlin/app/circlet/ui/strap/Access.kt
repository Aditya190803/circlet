package app.circlet.ui.strap

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.circlet.ui.design.Divider
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.body
import app.circlet.ui.design.title

/**
 * The three Android settings the strap link depends on, read from the system each time the
 * screen resumes, with the calls that ask for them.
 */
class Access(
    val nearby: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val requestNearby: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestBattery: () -> Unit,
)

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
private fun requestNoBatteryLimits(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@Composable
fun rememberAccess(): Access {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    // Reading `refresh` re-evaluates the checks after every system dialog.
    return remember(refresh) {
        Access(
            nearby = granted(context, Manifest.permission.BLUETOOTH_SCAN) && granted(context, Manifest.permission.BLUETOOTH_CONNECT),
            notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            requestNearby = { launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(settings) }
                }
            },
            requestBattery = { requestNoBatteryLimits(context) },
        )
    }
}

/** The three access rows, each with a tick when granted and an ALLOW button when not. */
@Composable
fun AccessRows(access: Access) {
    AccessRow(Glyph.Bluetooth, "Nearby devices", "Required. Finds and connects to the strap.", access.nearby, access.requestNearby)
    Divider()
    AccessRow(
        Glyph.Bell, "Notifications", "Shows the quiet status entry Android requires for a background connection.",
        access.notifications, access.requestNotifications,
    )
    Divider()
    AccessRow(Glyph.Power, "Unrestricted battery", "Stops Android from pausing sync while you sleep.", access.battery, access.requestBattery)
}

@Composable
fun AccessRow(glyph: Glyph, name: String, reason: String, done: Boolean, onAllow: () -> Unit) {
    Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(glyph, Palette.White, 24.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = title(17.sp))
            Text(reason, style = body(13.sp, Palette.Faint))
        }
        Spacer(Modifier.width(10.dp))
        if (done) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(Shapes.pill)
                    .background(Palette.Teal)
                    .semantics { contentDescription = "$name allowed" },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.Check, Palette.BgBottom, 18.dp) }
        } else {
            SmallButton("ALLOW", onClick = onAllow)
        }
    }
}
