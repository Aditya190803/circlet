package app.circlet.ui.workout

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.circlet.ui.design.HeroDial
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.glyph
import app.circlet.ui.design.hoursMinutes
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
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun ActivityDetailScreen(activityId: String, date: LocalDate, onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> ActivityDetailViewModel(container.metrics, container.workouts) }
    val state by vm.state.collectAsStateWithLifecycle()
    val dayHeartRate by vm.dayHeartRate.collectAsStateWithLifecycle()
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
                is Loadable.Ready<Activity> -> ActivityBody(
                    activity = current.value,
                    heartRate = workoutMinutes(dayHeartRate, current.value, date, format),
                    format = format,
                )
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
private fun ActivityBody(activity: Activity, heartRate: List<Int?>, format: TimeFormat) {
    HeroDial(
        caption = "${activity.sport.label().uppercase()} STRAIN",
        glyph = activity.sport.glyph(),
        color = Palette.Strain,
        value = activity.strain.value?.decimal(),
        unit = "",
        fraction = activity.strain.value?.div(21.0)?.toFloat(),
        message = "${format.range(activity.start, activity.end)}  ·  ${source(activity)}",
    )
    Card {
        Row(Modifier.fillMaxWidth()) {
            Figure(
                value = Duration.between(activity.start, activity.end).hoursMinutes(),
                caption = "DURATION", modifier = Modifier.weight(1f),
            )
            Figure(
                value = activity.calories.value?.grouped(), caption = "KCAL",
                absent = activity.calories.absentReason, modifier = Modifier.weight(1f),
            )
            Figure(
                value = activity.averageHeartRateBpm.value?.toString(), caption = "AVG BPM",
                absent = activity.averageHeartRateBpm.absentReason, alignEnd = true, modifier = Modifier.weight(1f),
            )
        }
    }
    Card("HEART RATE") {
        if (heartRate.count { it != null } >= 2) {
            WorkoutHeartChart(heartRate)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(format.time(activity.start), style = label(10.sp, Palette.Faint))
                Text(format.time(activity.end), style = label(10.sp, Palette.Faint))
            }
            Spacer(Modifier.height(8.dp))
        }
        StatRow("AVERAGE", activity.averageHeartRateBpm.value?.toString(), "BPM", absent = activity.averageHeartRateBpm.absentReason)
        Divider()
        StatRow("MAX", activity.maxHeartRateBpm.value?.toString(), "BPM", absent = activity.maxHeartRateBpm.absentReason)
        // A null recovery means the minute after the activity was not sampled.
        activity.heartRateRecovery?.dropBpm?.value?.let { drop ->
            Divider()
            StatRow("DROP IN THE MINUTE AFTER", drop.toString(), "BPM")
        }
    }
    Card("TIME IN ZONES") {
        ZoneBars(activity.zoneMinutes)
    }
}

/** The slice of a day's per-minute heart rate that falls inside [activity]. */
private fun workoutMinutes(day: List<Int?>, activity: Activity, date: LocalDate, format: TimeFormat): List<Int?> {
    val dayStart = date.atStartOfDay(format.zone).toInstant()
    val from = (Duration.between(dayStart, activity.start).toMinutes().toInt()).coerceIn(0, day.size)
    val to = (Duration.between(dayStart, activity.end).toMinutes().toInt() + 1).coerceIn(from, day.size)
    return day.subList(from, to)
}

/** Heart rate across the workout as a filled line. Gaps where the strap had no reading are left open. */
@Composable
private fun WorkoutHeartChart(minutes: List<Int?>) {
    val valid = minutes.filterNotNull()
    val low = valid.min() - 5f
    val high = valid.max() + 5f
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val step = size.width / (minutes.size - 1).coerceAtLeast(1)
        fun y(bpm: Int) = size.height * (1f - (bpm - low) / (high - low))
        val line = Path()
        var open = false
        minutes.forEachIndexed { i, bpm ->
            if (bpm == null) {
                open = false
            } else {
                if (open) line.lineTo(i * step, y(bpm)) else line.moveTo(i * step, y(bpm))
                open = true
            }
        }
        drawPath(line, Palette.Strain, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun source(activity: Activity): String =
    if (activity.source == ActivitySource.AUTO) "DETECTED" else "LOGGED"
