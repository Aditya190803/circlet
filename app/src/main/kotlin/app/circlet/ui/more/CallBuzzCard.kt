package app.circlet.ui.more

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.circlet.core.CallSource
import app.circlet.core.UserSettings
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.Palette
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body

/** The strap module's notification listener. Named here so the UI builds against :core alone. */
private const val CALL_LISTENER = "app.circlet.strap.bg.CallListener"

/** Which apps' ringing calls buzz the strap, and the notification access that makes it work. */
@Composable
fun CallBuzzCard(settings: UserSettings, onChange: (UserSettings) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val granted = remember(refresh) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }

    fun set(source: CallSource, on: Boolean) {
        onChange(settings.copy(callBuzz = if (on) settings.callBuzz + source else settings.callBuzz - source))
        if (on && !granted) openAccess(context)
    }

    Card("Calls") {
        Text(
            "The strap pulses while a call rings, for up to 45 seconds. Tap the strap a few times to silence it",
            style = body(13.sp, Palette.Faint),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (!granted) {
            ListRow("Notification access", subtitle = "Circlet only looks at incoming-call notifications and keeps nothing") {
                SmallButton("Allow") { openAccess(context) }
            }
            Divider()
        }
        ListRow("Phone calls", subtitle = "Calls to your number") {
            Toggle(CallSource.PHONE in settings.callBuzz, "Buzz the strap for phone calls") { set(CallSource.PHONE, it) }
        }
        Divider()
        ListRow("WhatsApp calls", subtitle = "Voice and video calls") {
            Toggle(CallSource.WHATSAPP in settings.callBuzz, "Buzz the strap for WhatsApp calls") { set(CallSource.WHATSAPP, it) }
        }
    }
}

private fun openAccess(context: Context) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context.packageName, CALL_LISTENER).flattenToString(),
        )
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(detail) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
