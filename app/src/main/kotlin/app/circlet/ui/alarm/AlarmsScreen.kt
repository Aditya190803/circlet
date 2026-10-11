package app.circlet.ui.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.LinkState
import app.circlet.core.StrapAlarm
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Chip
import app.circlet.ui.design.Divider
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.Field
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.NoticeCard
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.Space
import app.circlet.ui.design.Tag
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.state.containerViewModel
import java.time.LocalTime

/** The alarm being edited in the sheet. [isNew] is true for an alarm not yet saved. */
private data class Editing(val alarm: StrapAlarm, val isNew: Boolean)

@Composable
fun AlarmsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { container, _ ->
        AlarmViewModel(container.alarms, container.strap, container.time)
    }
    val messenger = LocalMessenger.current
    val state by vm.state.collectAsStateWithLifecycle()
    val twentyFourHour = LocalTimeFormat.current.twentyFourHour
    var editing by remember { mutableStateOf<Editing?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("ALARMS", onBack = onBack)
        ScreenColumn {
            when (val s = state) {
                AlarmUiState.Loading -> {
                    Card(title = "NEXT ALARM") { Skeleton(Modifier.fillMaxWidth().height(14.dp)) }
                    Card(title = "ALARMS") { Skeleton(Modifier.fillMaxWidth().height(14.dp)) }
                }
                is AlarmUiState.Ready -> {
                    val next = s.next
                    Card(title = "NEXT ALARM") {
                        if (next != null) {
                            Text(formatAlarmTime(next.alarm.time, twentyFourHour), style = number(34.sp, Palette.Teal))
                            Text(describeDays(next.alarm.weekdays), style = body(13.sp, Palette.Dim))
                        } else {
                            EmptyState(
                                glyph = Glyph.Alarm,
                                heading = "NO ALARM SET",
                                text = "Alarms are stored on the strap, which buzzes at the set time. The next one you turn on shows here.",
                            )
                        }
                    }

                    if (s.link != LinkState.CONNECTED) {
                        NoticeCard(
                            glyph = Glyph.Strap,
                            heading = "STRAP NOT CONNECTED",
                            text = "Alarms are kept on the strap. Connect it to send a test buzz.",
                        )
                    }

                    Card(
                        title = "ALARMS",
                        trailing = { SmallButton(text = "ADD", onClick = { editing = Editing(newAlarm(), true) }) },
                    ) {
                        if (s.alarms.isEmpty()) {
                            EmptyState(
                                glyph = Glyph.Alarm,
                                heading = "NO ALARMS",
                                text = "Add one and it is stored on the strap, so it still rings if the phone is off.",
                            )
                        } else {
                            s.alarms.forEachIndexed { index, alarm ->
                                if (index > 0) Divider()
                                AlarmRow(
                                    alarm = alarm,
                                    twentyFourHour = twentyFourHour,
                                    onEdit = { editing = Editing(alarm, false) },
                                    onToggle = { vm.toggle(alarm, it) },
                                )
                            }
                        }
                    }

                    QuietButton(
                        text = "TEST BUZZ",
                        color = Palette.Teal,
                        enabled = s.link == LinkState.CONNECTED,
                        onClick = { vm.buzz() },
                    )
                }
            }
        }
    }

    editing?.let { e ->
        Sheet(title = if (e.isNew) "NEW ALARM" else "EDIT ALARM", onDismiss = { editing = null }) {
            AlarmEditor(
                existing = e.alarm,
                twentyFourHour = twentyFourHour,
                onSave = { alarm -> vm.save(alarm) { editing = null } },
            )
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: StrapAlarm,
    twentyFourHour: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val time = formatAlarmTime(alarm.time, twentyFourHour)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Edit alarm $time", onClick = onEdit),
        ) {
            Text(
                time,
                style = number(26.sp, if (alarm.enabled) Palette.White else Palette.Faint),
            )
            Text(describeDays(alarm.weekdays), style = label(11.sp, Palette.Faint))
            val caption = smartWakeCaption(alarm.smartWake)
            if (caption.isNotEmpty()) Tag(text = caption, color = Palette.Teal)
        }
        Toggle(
            checked = alarm.enabled,
            description = "Alarm at $time",
            onChange = onToggle,
        )
    }
}

/**
 * Hour and minute entry, weekday chips, smart wake, and save. An empty weekday
 * set means the alarm rings once. Deleting is not offered: the core
 * AlarmController has no remove call.
 */
@Composable
private fun AlarmEditor(
    existing: StrapAlarm,
    twentyFourHour: Boolean,
    onSave: (StrapAlarm) -> Unit,
) {
    var hourText by remember(existing.id) {
        mutableStateOf(displayHour(existing.time.hour, twentyFourHour).toString())
    }
    var minuteText by remember(existing.id) {
        mutableStateOf("%02d".format(existing.time.minute))
    }
    var pm by remember(existing.id) { mutableStateOf(existing.time.hour >= 12) }
    var days by remember(existing.id) { mutableStateOf(existing.weekdays) }
    var option by remember(existing.id) { mutableStateOf(smartWakeOption(existing.smartWake)) }

    val hourValue = hourText.toIntOrNull()
    val minuteValue = minuteText.toIntOrNull()
    val hourRange = if (twentyFourHour) 0..23 else 1..12
    val hourOk = hourValue != null && hourValue in hourRange
    val minuteOk = minuteValue != null && minuteValue in 0..59
    val parsed: LocalTime? =
        if (hourValue != null && minuteValue != null && hourOk && minuteOk) {
            LocalTime.of(if (twentyFourHour) hourValue else withPeriod(hourValue % 12, pm), minuteValue)
        } else {
            null
        }

    Field(
        name = "HOUR",
        value = hourText,
        hint = if (twentyFourHour) "0 to 23" else "1 to 12",
        isError = hourText.isNotEmpty() && !hourOk,
        maxLength = 2,
        onChange = { if (it.length <= 2 && it.all(Char::isDigit)) hourText = it },
    )
    Field(
        name = "MINUTE",
        value = minuteText,
        hint = "0 to 59",
        isError = minuteText.isNotEmpty() && !minuteOk,
        maxLength = 2,
        onChange = { if (it.length <= 2 && it.all(Char::isDigit)) minuteText = it },
    )
    if (!twentyFourHour) {
        Segmented(
            options = listOf("AM", "PM"),
            selected = if (pm) 1 else 0,
            onSelect = { pm = it == 1 },
        )
    }

    Text("REPEAT", style = label(11.sp, Palette.Faint), modifier = Modifier.padding(horizontal = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        WEEK_ORDER.forEach { day ->
            Chip(
                text = shortDay(day),
                selected = day in days,
                onClick = { days = if (day in days) days - day else days + day },
            )
        }
    }
    Text(
        if (days.isEmpty()) {
            "No days chosen. The alarm rings once, at the next time it comes round."
        } else {
            "Repeats ${describeDays(days).lowercase()}."
        },
        style = body(13.sp, Palette.Faint),
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Text("SMART WAKE", style = label(11.sp, Palette.Faint), modifier = Modifier.padding(horizontal = 4.dp))
    Segmented(
        options = listOf("OFF", "15 MIN", "30 MIN"),
        selected = option,
        onSelect = { option = it },
    )
    Text(smartWakeLine(option), style = body(13.sp, Palette.Faint), modifier = Modifier.padding(horizontal = 4.dp))

    PrimaryButton(
        text = "SAVE ALARM",
        enabled = parsed != null,
        onClick = {
            parsed?.let { time ->
                onSave(
                    StrapAlarm(
                        id = existing.id,
                        time = time,
                        weekdays = days,
                        smartWake = SMART_WAKE_OPTIONS[option],
                        enabled = existing.enabled,
                    ),
                )
            }
        },
    )
}
