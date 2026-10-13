package app.circlet.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.AppContainer
import app.circlet.core.DaySummary
import app.circlet.core.Outcome
import app.circlet.core.SleepEdit
import app.circlet.core.rejectedSleepWindow
import app.circlet.ui.LocalMessenger
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.GhostRow
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.Space
import app.circlet.ui.design.TileRow
import app.circlet.ui.design.body
import app.circlet.ui.design.hoursMinutes
import app.circlet.ui.design.label
import app.circlet.ui.design.minutesWords
import app.circlet.ui.design.words
import app.circlet.ui.workout.TimeStepper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Saves a correction for [date] and tells the user when it is refused. */
@Composable
private fun rememberSleepEditSaver(date: LocalDate): (SleepEdit) -> Unit {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    return { edit ->
        scope.launch {
            when (val result = AppContainer.get(context).metrics.saveSleepEdit(date, edit)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> messenger.show(result.error.words())
            }
        }
    }
}

@Composable
private fun rememberSleepEdit(date: LocalDate): SleepEdit? {
    val context = LocalContext.current
    val flow = remember(date) { AppContainer.get(context).metrics.observeSleepEdit(date) }
    val edit by flow.collectAsStateWithLifecycle(null)
    return edit
}

/** Edit, add, remove or restore the night, with the sheets they open. */
@Composable
fun SleepEditActions(day: DaySummary, date: LocalDate) {
    val edit = rememberSleepEdit(date)
    val save = rememberSleepEditSaver(date)
    val format = LocalTimeFormat.current
    val sleep = day.sleep.value
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    SecondaryButton(if (sleep != null) "EDIT SLEEP" else "ADD SLEEP") { editing = true }
    if (sleep != null) SecondaryButton("REMOVE SLEEP") { removing = true }
    if (edit.changesMain) SecondaryButton("USE DETECTED SLEEP") { save(edit.withDetectedMain()) }

    if (editing) {
        val now = remember { Instant.now() }
        val initial = remember { suggestedSleepWindow(date, format.zone, now, sleep) }
        var bed by remember { mutableStateOf(initial.first) }
        var wake by remember { mutableStateOf(initial.second) }
        val problem = rejectedSleepWindow(bed, wake, now)
        Sheet(title = if (sleep != null) "EDIT SLEEP" else "ADD SLEEP", onDismiss = { editing = false }) {
            TimeStepper("BED", bed, null, format) { bed = it }
            Spacer(Modifier.height(Space.md))
            TimeStepper("WAKE", wake, bed, format) { wake = it }
            Spacer(Modifier.height(Space.md))
            if (problem == null) {
                Text("IN BED  ${minutesWords(Duration.between(bed, wake).toMinutes())}", style = label(12.sp, Palette.Dim))
            } else {
                Text(problem, style = body(14.sp, Palette.Warn))
            }
            Spacer(Modifier.height(Space.md))
            PrimaryButton("SAVE", enabled = problem == null) {
                editing = false
                save(edit.withWindow(bed, wake))
            }
        }
    }

    if (removing) {
        Sheet(title = "REMOVE THIS SLEEP?", onDismiss = { removing = false }) {
            Text(
                "This night will count as no sleep. Recovery and the other figures worked out from it are scored again without it. You can bring the detected sleep back later.",
                style = body(14.sp, Palette.Dim),
            )
            Spacer(Modifier.height(Space.md))
            PrimaryButton("REMOVE SLEEP", danger = true) {
                removing = false
                save(edit.withMainRemoved())
            }
            Spacer(Modifier.height(Space.xs))
            SecondaryButton("KEEP SLEEP") { removing = false }
        }
    }
}

/** Detected naps, each with a REJECT button, and the rejected ones with KEEP. */
@Composable
fun NapsCard(day: DaySummary, date: LocalDate) {
    val format = LocalTimeFormat.current
    val edit = rememberSleepEdit(date)
    val save = rememberSleepEditSaver(date)
    val rejected = edit?.rejectedNaps.orEmpty().sorted()
    Card("NAPS") {
        if (day.naps.isEmpty() && rejected.isEmpty()) GhostRow(Glyph.Moon, "NAP", "Daytime sleep is kept apart from the night")
        day.naps.forEachIndexed { i, nap ->
            if (i > 0) Divider()
            NapRow(
                content = { TileRow(nap.timeAsleep.hoursMinutes(), Palette.Sleep, "NAP", format.range(nap.start, nap.end)) },
                action = "REJECT",
            ) { save(edit.withNap(nap.start, counted = false)) }
        }
        rejected.forEachIndexed { i, start ->
            if (i > 0 || day.naps.isNotEmpty()) Divider()
            NapRow(
                content = { TileRow(null, Palette.Sleep, "NAP", "${format.time(start)} · NOT COUNTED") },
                action = "KEEP",
            ) { save(edit.withNap(start, counted = true)) }
        }
        if (rejected.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Rejected naps are left out of your sleep totals and sleep debt.", style = body(12.sp, Palette.Faint))
        }
    }
}

@Composable
private fun NapRow(content: @Composable () -> Unit, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { content() }
        Box(Modifier.padding(start = Space.sm)) { SmallButton(action, onClick = onAction) }
    }
}
