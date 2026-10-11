package app.circlet.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.Activity
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.Space
import app.circlet.ui.design.TimeFormat
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.decimal
import app.circlet.ui.design.glyph
import app.circlet.ui.design.label
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.containerViewModel
import java.time.LocalDate

@Composable
fun WorkoutsScreen(date: LocalDate, onBack: () -> Unit, onOpenActivity: (String) -> Unit, onStartWorkout: () -> Unit) {
    val vm = containerViewModel { container, _ -> WorkoutsViewModel(container.metrics, container.workouts, container.time) }
    val state by vm.state.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val format = LocalTimeFormat.current
    val today = LocalDate.now(format.zone)
    LaunchedEffect(date) { vm.setDate(date) }
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("WORKOUTS", subtitle = dateTitle(date, today), onBack = onBack)
        ScreenColumn {
            when (val current = state) {
                Loadable.Loading -> Card {
                    Skeleton(Modifier.fillMaxWidth().height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    Skeleton(Modifier.fillMaxWidth(0.7f).height(14.dp))
                }
                is Loadable.Failed -> ErrorState(current.message, onRetry = null)
                is Loadable.Ready<WorkoutsPage> -> DayWorkouts(
                    page = current.value,
                    format = format,
                    onToggle = vm::toggle,
                    onMerge = vm::merge,
                    onOpen = onOpenActivity,
                )
            }
            PrimaryButton("START A WORKOUT") { onStartWorkout() }
        }
    }
}

@Composable
private fun ColumnScope.DayWorkouts(
    page: WorkoutsPage,
    format: TimeFormat,
    onToggle: (String) -> Unit,
    onMerge: () -> Unit,
    onOpen: (String) -> Unit,
) {
    if (page.activities.isEmpty()) {
        EmptyState(
            Glyph.Run,
            "NO WORKOUTS",
            "Workouts you start, and activity the strap detects while you move, are listed here once they are recorded.",
        )
        return
    }
    Card("ACTIVITIES") {
        page.activities.forEachIndexed { index, activity ->
            if (index > 0) Divider()
            ActivityRow(
                activity = activity,
                format = format,
                selected = activity.id in page.selected,
                onOpen = { onOpen(activity.id) },
                onToggle = { onToggle(activity.id) },
            )
        }
    }
    Text(
        "To merge, select two or more activities that follow each other on this day with no other workout in between.",
        style = body(13.sp, Palette.Faint),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    SecondaryButton("MERGE SELECTED", enabled = page.mergeable, onClick = onMerge)
}

@Composable
private fun ActivityRow(
    activity: Activity,
    format: TimeFormat,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val name = activity.sport.label()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(activity.sport.glyph(), tint = Palette.Dim, size = 22.dp)
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClickLabel = "Open $name", onClick = onOpen),
        ) {
            Text(name.uppercase(), style = label(13.sp, Palette.White))
            Text(
                "${timeRange(activity.start, activity.end, format)}, ${durationText(activity.start, activity.end)}",
                style = body(12.sp, Palette.Faint),
            )
        }
        Figure(
            value = activity.strain.value?.decimal(),
            caption = "STRAIN",
            absent = activity.strain.absentReason,
            alignEnd = true,
        )
        Spacer(Modifier.width(Space.xs))
        Toggle(checked = selected, description = "Select $name for merging", onChange = { onToggle() })
    }
}
