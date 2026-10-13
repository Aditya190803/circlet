package app.circlet.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.UserSettings
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.label

private val THRESHOLDS = listOf(10, 15, 20, 30)
private const val DEFAULT_QUIET_START = 22 * 60
private const val DEFAULT_QUIET_END = 7 * 60
private const val MINUTES_PER_DAY = 24 * 60

@Composable
fun NotificationsScreen(settings: UserSettings, onChange: (UserSettings) -> Unit, onBack: () -> Unit) {
    val start = settings.quietStartMinute
    val end = settings.quietEndMinute
    val quietOn = start != null && end != null
    Column(Modifier.fillMaxSize()) {
        DetailHeader("NOTIFICATIONS", onBack = onBack)
        ScreenColumn {
            Card("ALERTS") {
                ListRow("Off wrist", subtitle = "When the connected strap stays off your wrist for 5 minutes") {
                    Toggle(settings.offWristAlerts, "Off wrist alert") { onChange(settings.copy(offWristAlerts = it)) }
                }
                Divider()
                ListRow("Low battery", subtitle = "Once when the strap battery drops to the level below") {
                    Toggle(settings.lowBatteryAlerts, "Low battery alert") { onChange(settings.copy(lowBatteryAlerts = it)) }
                }
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("ALERT AT", style = label())
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        THRESHOLDS.map { "$it%" },
                        THRESHOLDS.indexOf(settings.lowBatteryThreshold),
                        enabled = settings.lowBatteryAlerts,
                    ) { onChange(settings.copy(lowBatteryThreshold = THRESHOLDS[it])) }
                }
            }
            Card("QUIET HOURS") {
                ListRow("Quiet hours", subtitle = "No alerts in this window") {
                    Toggle(quietOn, "Quiet hours") {
                        onChange(
                            if (it) settings.copy(quietStartMinute = DEFAULT_QUIET_START, quietEndMinute = DEFAULT_QUIET_END)
                            else settings.copy(quietStartMinute = null, quietEndMinute = null),
                        )
                    }
                }
                if (start != null && end != null) {
                    Divider()
                    HourStepper("FROM", start) { onChange(settings.copy(quietStartMinute = it)) }
                    Divider()
                    HourStepper("UNTIL", end) { onChange(settings.copy(quietEndMinute = it)) }
                    Spacer(Modifier.height(6.dp))
                    Text("A window that ends before it starts runs overnight.", style = body(13.sp, Palette.Faint))
                }
            }
        }
    }
}

@Composable
private fun HourStepper(title: String, minute: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = label())
            Text(clockLabel(minute, LocalTimeFormat.current.twentyFourHour), style = body(16.sp))
        }
        SecondaryButton("−1 H", modifier = Modifier.weight(1f)) { onChange(Math.floorMod(minute - 60, MINUTES_PER_DAY)) }
        SecondaryButton("+1 H", modifier = Modifier.weight(1f)) { onChange((minute + 60) % MINUTES_PER_DAY) }
    }
}

internal fun clockLabel(minuteOfDay: Int, twentyFourHour: Boolean): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    if (twentyFourHour) return "%02d:%02d".format(hour, minute)
    val twelve = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(twelve, minute, if (hour < 12) "AM" else "PM")
}
