package app.circlet.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Outcome
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.Space
import app.circlet.ui.design.ZoneBars
import app.circlet.ui.design.body
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.decimal
import app.circlet.ui.design.grouped
import app.circlet.ui.design.label
import app.circlet.ui.design.TimeFormat
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.TextEntry
import app.circlet.ui.design.words
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.containerViewModel
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun ActivityDetailScreen(activityId: String, date: LocalDate, onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> ActivityDetailViewModel(container.metrics, container.workouts) }
    val state by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val format = LocalTimeFormat.current
    val today = LocalDate.now(format.zone)
    var confirming by remember { mutableStateOf(false) }
    var changingSport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = (state as? Loadable.Ready<Activity>)?.value
    LaunchedEffect(activityId, date) { vm.open(activityId, date) }
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }
    LaunchedEffect(vm) { vm.deleted.collect { onBack() } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("WORKOUT", subtitle = dateTitle(date, today), onBack = onBack)
        ScreenColumn {
            when (val current = state) {
                Loadable.Loading -> Card("ACTIVITY") {
                    Skeleton(Modifier.fillMaxWidth().height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
                }
                is Loadable.Failed -> ErrorState(current.message, onRetry = null)
                is Loadable.Ready<Activity> -> ActivityBody(activity = current.value, format = format)
            }
            if (ready != null) {
                SecondaryButton("CHANGE SPORT") { changingSport = true }
                SecondaryButton("DELETE ACTIVITY") { confirming = true }
            }
        }
    }

    if (changingSport && ready != null) {
        var query by remember { mutableStateOf("") }
        Sheet(title = "CHANGE SPORT", onDismiss = { changingSport = false }) {
            TextEntry(query, "Sport", placeholder = "Search sports") { query = it }
            Spacer(Modifier.height(Space.xs))
            filterSports(query).forEach { pick ->
                QuietButton(pick.label()) {
                    changingSport = false
                    scope.launch {
                        when (val result = AppContainer.get(context).workouts.edit(ready.copy(sport = pick))) {
                            is Outcome.Ok -> Unit
                            is Outcome.Err -> messenger.show(result.error.words())
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        Sheet(title = "DELETE THIS ACTIVITY?", onDismiss = { confirming = false }) {
            Text(
                "The activity and its recorded heart rate and strain will be removed. This cannot be undone.",
                style = body(14.sp, Palette.Dim),
            )
            Spacer(Modifier.height(Space.md))
            PrimaryButton("DELETE", danger = true) {
                confirming = false
                vm.delete()
            }
            Spacer(Modifier.height(Space.xs))
            SecondaryButton("KEEP ACTIVITY") { confirming = false }
        }
    }
}

@Composable
private fun ActivityBody(activity: Activity, format: TimeFormat) {
    Card("SUMMARY") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Figure(value = activity.strain.value?.decimal(), caption = "STRAIN", absent = activity.strain.absentReason)
            Figure(value = activity.calories.value?.grouped(), caption = "KCAL", absent = activity.calories.absentReason)
        }
        Divider()
        Text(
            "${timeRange(activity.start, activity.end, format)}, ${durationText(activity.start, activity.end)}",
            style = body(14.sp, Palette.Dim),
        )
        Text(
            "${source(activity)}, ${activity.sport.label()}",
            style = label(12.sp, Palette.Faint),
        )
    }
    Card("HEART RATE") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Figure(value = activity.averageHeartRateBpm.value?.toString(), caption = "AVERAGE BPM", absent = activity.averageHeartRateBpm.absentReason)
            Figure(value = activity.maxHeartRateBpm.value?.toString(), caption = "MAX BPM", absent = activity.maxHeartRateBpm.absentReason)
        }
        // A null recovery means the minute after the activity was not sampled.
        val drop = activity.heartRateRecovery?.dropBpm
        Divider()
        Figure(
            value = drop?.value?.toString(),
            caption = "RECOVERY DROP BPM",
            absent = if (drop == null) AbsentReason.NOT_ENOUGH_DATA else drop.absentReason,
        )
    }
    Card("TIME IN ZONES") {
        ZoneBars(activity.zoneMinutes)
    }
}

private fun source(activity: Activity): String =
    if (activity.source == ActivitySource.AUTO) "DETECTED" else "LOGGED"
