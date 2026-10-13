package app.circlet.ui.workout

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.circlet.core.routeKind
import app.circlet.route.RouteRecorder
import app.circlet.ui.design.Toggle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.LiveWorkout
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.LiveDot
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.Space
import app.circlet.ui.design.ZoneBars
import app.circlet.ui.design.body
import app.circlet.ui.design.decimal
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.state.containerViewModel

@Composable
fun LiveWorkoutScreen(onBack: () -> Unit, onFinished: (String) -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel { container, _ ->
        LiveWorkoutViewModel(container.workouts, container.time, RouteRecorder.get(context))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (vm.hasLocationPermission()) vm.setRecordRoute(true)
    }
    val messenger = LocalMessenger.current
    var confirmingFinish by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }
    LaunchedEffect(vm) { vm.finished.collect { onFinished(it) } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("LIVE WORKOUT", onBack = onBack)
        ScreenColumn {
            when (val current = state) {
                LiveUi.Loading -> Card("RECORDING") {
                    Skeleton(Modifier.fillMaxWidth().height(40.dp))
                    Spacer(Modifier.height(Space.sm))
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
                }
                LiveUi.Idle -> EmptyState(
                    Glyph.Run,
                    "NO WORKOUT RUNNING",
                    "Start a workout from the workouts page. Its time, heart rate, strain and calories appear here while you record it.",
                )
                is LiveUi.Recording -> {
                    val elapsed = elapsedText(current.elapsed)
                    Recording(live = current.live, elapsedText = elapsed)
                    if (current.live.sport.routeKind() != null) {
                        RouteCard(route = route, onToggle = { on ->
                            if (!on || vm.hasLocationPermission()) vm.setRecordRoute(on) else askLocation.launch(LOCATION_PERMISSIONS)
                        })
                    }
                    SecondaryButton(if (current.live.paused) "RESUME" else "PAUSE") {
                        if (current.live.paused) vm.resume() else vm.pause()
                    }
                    PrimaryButton("STOP WORKOUT") { confirmingFinish = true }
                    if (confirmingFinish) {
                        Sheet("FINISH THIS WORKOUT?", onDismiss = { confirmingFinish = false }) {
                            Text("$elapsed recorded. It is saved to today's activities.", style = body(14.sp, Palette.Dim))
                            Spacer(Modifier.height(Space.lg))
                            PrimaryButton("FINISH") {
                                confirmingFinish = false
                                vm.stop()
                            }
                            Spacer(Modifier.height(Space.sm))
                            SecondaryButton("KEEP GOING") { confirmingFinish = false }
                        }
                    }
                }
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
private fun RouteCard(route: LiveRoute, onToggle: (Boolean) -> Unit) {
    Card("ROUTE") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RECORD ROUTE", style = label(12.sp, Palette.Dim), modifier = Modifier.weight(1f))
            Toggle(route.recording, "Record route", onChange = onToggle)
        }
        if (route.recording) {
            Spacer(Modifier.height(Space.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure(value = if (route.distanceM > 0.0) distanceKm(route.distanceM) else null, caption = "DISTANCE KM")
                Figure(value = route.pace?.value, caption = "PACE ${route.pace?.unit ?: ""}".trim())
            }
        }
    }
}

@Composable
private fun Recording(live: LiveWorkout, elapsedText: String) {
    Card(live.sport.label().uppercase()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            LiveDot(color = if (live.paused) Palette.Warn else Palette.Teal, active = !live.paused)
            Text(elapsedText, style = number(48.sp, Palette.White))
        }
        if (live.paused) {
            Text("PAUSED", style = label(12.sp, Palette.Warn))
        } else {
            Text("RECORDING", style = label(12.sp, Palette.Faint))
        }
    }
    Card("NOW") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // A zero strain or calorie count is not yet meaningful, so it is held back until above zero.
            Figure(value = if (live.strain > 0.0) live.strain.decimal() else null, caption = "STRAIN")
            Figure(value = if (live.caloriesKcal > 0) live.caloriesKcal.toString() else null, caption = "KCAL")
            Figure(value = live.heartRateBpm?.toString(), caption = "BPM")
        }
    }
    Card("AVERAGE AND MAX") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Figure(value = live.averageHeartRateBpm?.toString(), caption = "AVERAGE BPM")
            Figure(value = live.maxHeartRateBpm?.toString(), caption = "MAX BPM")
        }
    }
    Card("TIME IN ZONES") {
        ZoneBars(live.zoneMinutes)
    }
}
