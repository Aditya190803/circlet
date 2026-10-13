package app.circlet.ui.more

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Space
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.state.containerViewModel

/** Paced breathing with a circle that grows on the inhale and shrinks on the exhale. */
@Composable
fun BreatheScreen(onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> BreatheViewModel(container.strap, container.time) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(ui.running) {
        view.keepScreenOn = ui.running
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("BREATHE", onBack = onBack)
        ScreenColumn {
            if (ui.running) {
                Session(ui)
                PrimaryButton("STOP", danger = true, onClick = vm::stop)
            } else {
                if (ui.finished) Summary(ui)
                Card("PACE") {
                    Segmented(
                        options = BREATHE_PACES.map { "%.1f".format(it).removeSuffix(".0") },
                        selected = ui.paceIndex,
                        onSelect = vm::setPace,
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text("Breaths per minute", style = body(12.sp, Palette.Faint))
                }
                Card("LENGTH") {
                    Segmented(
                        options = BREATHE_MINUTES.map { "$it MIN" },
                        selected = ui.lengthIndex,
                        onSelect = vm::setLength,
                    )
                }
                Card("STRAP") {
                    Toggle(ui.buzz, "Buzz the strap when the breath changes", onChange = vm::setBuzz)
                    Spacer(Modifier.height(Space.sm))
                    Text("Buzz on each change between in and out.", style = body(12.sp, Palette.Faint))
                }
                PrimaryButton("START", onClick = vm::start)
            }
        }
    }
}

@Composable
private fun Session(ui: BreatheUi) {
    val phase = phaseAt(ui.elapsedMs, ui.breathsPerMin)
    Card {
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val full = size.minDimension / 2
                drawCircle(Palette.CardLine, radius = full, style = Stroke(width = 2.dp.toPx()))
                drawCircle(Palette.Teal.copy(alpha = 0.55f), radius = full * (0.3f + 0.7f * phase.fill))
            }
            Text(
                if (phase.inhaling) "BREATHE IN" else "BREATHE OUT",
                style = label(18.sp, Palette.White),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(formatRemaining(ui.remainingMs), style = number(40.sp), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("REMAINING", style = label(12.sp), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.sm))
        Text(
            ui.bpm?.let { "$it BPM" } ?: "-- BPM",
            style = number(24.sp, Palette.Teal),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Summary(ui: BreatheUi) {
    Card("DONE") {
        Text(
            if (ui.startBpm != null && ui.bpm != null) {
                "Heart rate went from ${ui.startBpm} to ${ui.bpm} BPM."
            } else {
                "The strap sent no heart rate during this session."
            },
            style = body(14.sp),
        )
    }
}

internal fun formatRemaining(ms: Long): String {
    val seconds = (ms + 999) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
