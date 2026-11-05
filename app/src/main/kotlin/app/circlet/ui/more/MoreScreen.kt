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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.DoubleTapAction
import app.circlet.core.LinkState
import app.circlet.core.OnboardingChecks
import app.circlet.core.StrapState
import app.circlet.core.UserSettings
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.TextEntry
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.agoWords
import app.circlet.ui.design.stalled
import app.circlet.ui.design.syncProgressLabel
import app.circlet.ui.design.label
import app.circlet.ui.design.words
import app.circlet.ui.state.StrapAction
import app.circlet.ui.strap.Access
import app.circlet.ui.strap.AccessRows
import java.time.Instant
import kotlin.math.roundToInt

/** Everything the More tab can ask for. */
class MoreActions(
    val sync: () -> Unit = {},
    val buzz: () -> Unit = {},
    val find: () -> Unit = {},
    val rename: (String) -> Unit = {},
    val forget: () -> Unit = {},
    val setBroadcast: (Boolean) -> Unit = {},
    val setDoubleTap: (DoubleTapAction) -> Unit = {},
    val pair: () -> Unit = {},
    val profile: () -> Unit = {},
    val units: () -> Unit = {},
    val notifications: () -> Unit = {},
    val about: () -> Unit = {},
    val trends: () -> Unit = {},
    val journal: () -> Unit = {},
    val alarms: () -> Unit = {},
    val stress: () -> Unit = {},
    val workouts: () -> Unit = {},
    val data: () -> Unit = {},
    val importHistory: () -> Unit = {},
    val breathe: () -> Unit = {},
    val diagnostics: () -> Unit = {},
    val gallery: (() -> Unit)? = null,
)

@Composable
fun MoreScreen(
    strap: StrapState,
    settings: UserSettings,
    checks: OnboardingChecks,
    access: Access,
    busy: StrapAction?,
    actions: MoreActions,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader("More")
        ScreenColumn {
            if (strap.link == LinkState.UNPAIRED) {
                Card("Strap") {
                    Text(
                        "No strap is paired. Remove it from the official app and from Android Bluetooth settings first, because it only talks to one app at a time.",
                        style = body(14.sp),
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Pair a strap", onClick = actions.pair)
                }
            } else {
                StrapCard(strap, settings, busy, actions)
            }
            Card("Background sync") {
                val healthy = access.nearby && access.notifications && access.battery
                Text(
                    if (healthy) "Circlet stays connected with the app closed and checks in every 15 minutes."
                    else "Android may pause syncing while the app is closed. Allow the settings below to keep overnight data flowing.",
                    style = body(14.sp),
                )
                Spacer(Modifier.height(6.dp))
                AccessRows(access)
                Divider()
                StatRow(
                    "Strap association", if (checks.companionDeviceAssociated) "On" else "Off",
                    color = if (checks.companionDeviceAssociated) Palette.Teal else Palette.Dim,
                    note = "Wakes Circlet when the strap is near",
                )
            }
            Card {
                ListRow("Trends", Glyph.Trends, "Metrics over weeks and months, plus records", onClick = actions.trends)
                Divider()
                ListRow("Journal", Glyph.Journal, "Log how the day went and see what moves your scores", onClick = actions.journal)
                Divider()
                ListRow("Alarms", Glyph.Alarm, "Wake-up alarms the strap can buzz for", onClick = actions.alarms)
                Divider()
                ListRow("Stress", Glyph.Stress, "Stress through the selected day", onClick = actions.stress)
                Divider()
                ListRow("Workouts", Glyph.Run, "Activities for the selected day", onClick = actions.workouts)
                Divider()
                ListRow("Breathe", Glyph.Breathing, "Paced breathing with your heart rate", onClick = actions.breathe)
                Divider()
                ListRow("Cloud and data", Glyph.Export, "Firebase sync, Health Connect, export and restore", onClick = actions.data)
                Divider()
                ListRow("Import WHOOP history", Glyph.Import, "Bring in your earlier days and baseline", onClick = actions.importHistory)
                Divider()
                ListRow("Sync diagnostics", Glyph.Info, "How recent syncs ended, with a log you can save", onClick = actions.diagnostics)
                Divider()
                ListRow("Profile", Glyph.Person, "Age, height, weight and max heart rate", onClick = actions.profile)
                Divider()
                ListRow("Units", Glyph.Ruler, "Weight and clock", onClick = actions.units)
                Divider()
                ListRow("Notifications", Glyph.Alarm, "Call buzz, off-wrist and low-battery alerts, quiet hours", onClick = actions.notifications)
                Divider()
                ListRow("About and licences", Glyph.Info, "How scores are made, and the open-source notices", onClick = actions.about)
                actions.gallery?.let { open ->
                    Divider()
                    ListRow("Screen gallery", Glyph.Grid, "Debug builds only. Every component and screen state.", onClick = open)
                }
            }
        }
    }
}

@Composable
private fun StrapCard(strap: StrapState, settings: UserSettings, busy: StrapAction?, actions: MoreActions) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    val connected = strap.link == LinkState.CONNECTED
    val idle = connected && busy == null
    Card("Strap") {
        StatRow(
            "Name",
            strap.name ?: "Strap",
            note = when (strap.link) {
                LinkState.CONNECTED -> if (strap.onWrist == false) "Connected · off wrist" else "Connected"
                LinkState.CONNECTING, LinkState.RECONNECTING -> "Searching"
                LinkState.DISCONNECTED, LinkState.UNPAIRED -> "Offline"
            },
            onClick = if (idle) ({ renaming = true }) else null,
        )
        Divider()
        strap.generation?.let {
            StatRow("Model", it.model)
            Divider()
        }
        StatRow("Battery", strap.batteryPercent?.roundToInt()?.toString(), if (strap.charging == true) "% · charging" else "%")
        Divider()
        StatRow("Last sync", strap.lastSync?.let { agoWords(it, Instant.now()) } ?: "Not yet")
        strap.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.words(), style = body(13.sp, Palette.Warn))
        }
        Spacer(Modifier.height(12.dp))
        val syncing = strap.sync.draining || busy == StrapAction.SYNC
        val syncLabel = when {
            strap.sync.draining -> syncProgressLabel(strap.sync)
            syncing -> "Syncing"
            !connected -> "Reconnect and sync"
            strap.sync.stalled() -> "Retry sync"
            else -> "Sync now"
        }
        PrimaryButton(
            syncLabel,
            enabled = strap.link != LinkState.UNPAIRED && strap.link != LinkState.CONNECTING && busy == null,
            loading = syncing,
            onClick = actions.sync,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Buzz", enabled = idle, glyph = Glyph.Vibrate, modifier = Modifier.weight(1f), onClick = actions.buzz)
            SecondaryButton("Find strap", enabled = idle, glyph = Glyph.Search, modifier = Modifier.weight(1f), onClick = actions.find)
        }
        Spacer(Modifier.height(8.dp))
        ListRow(
            "Heart rate broadcast", Glyph.Broadcast, "Lets gym equipment and bike computers read your heart rate.",
            enabled = idle || busy == StrapAction.BROADCAST,
        ) {
            Toggle(settings.heartRateBroadcast, "Heart rate broadcast", enabled = idle, onChange = actions.setBroadcast)
        }
        Divider()
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Double tap on the strap", style = label())
            Spacer(Modifier.height(10.dp))
            val options = listOf(DoubleTapAction.STOP_ALARM, DoubleTapAction.MARK_MOMENT)
            Segmented(listOf("Stops the alarm", "Marks a moment"), options.indexOf(settings.doubleTap)) { actions.setDoubleTap(options[it]) }
        }
        QuietButton("Forget this strap", Palette.Danger, enabled = busy == null) { forgetting = true }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(strap.name.orEmpty()) }
        Sheet("Rename your strap", onDismiss = { renaming = false }) {
            Text("This is the name the strap advertises over Bluetooth.", style = body(14.sp))
            Spacer(Modifier.height(14.dp))
            TextEntry(name, "Strap name", placeholder = "Strap name") { name = it }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Save name", enabled = name.isNotBlank() && name.trim() != strap.name) {
                actions.rename(name.trim())
                renaming = false
            }
        }
    }
    if (forgetting) {
        Sheet("Forget ${strap.name ?: "this strap"}?", onDismiss = { forgetting = false }) {
            Text(
                "Circlet stops connecting to it and you will need to pair again. Your recorded history and baseline stay on this phone.",
                style = body(14.sp),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Forget strap", danger = true) {
                actions.forget()
                forgetting = false
            }
            QuietButton("Keep it") { forgetting = false }
        }
    }
}
