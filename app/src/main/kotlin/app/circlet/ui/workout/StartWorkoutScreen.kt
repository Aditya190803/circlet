package app.circlet.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import app.circlet.core.Sport
import app.circlet.ui.LocalMessenger
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Space
import app.circlet.ui.design.TextEntry
import app.circlet.ui.design.glyph
import app.circlet.ui.design.label
import app.circlet.ui.state.containerViewModel

@Composable
fun StartWorkoutScreen(onBack: () -> Unit, onStarted: () -> Unit) {
    val vm = containerViewModel { container, _ -> StartWorkoutViewModel(container.workouts) }
    val query by vm.query.collectAsStateWithLifecycle()
    val sports by vm.sports.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }
    LaunchedEffect(vm) { vm.started.collect { onStarted() } }

    Column(Modifier.fillMaxSize()) {
        DetailHeader("START A WORKOUT", onBack = onBack)
        ScreenColumn {
            TextEntry(
                value = query,
                description = "Search sports",
                placeholder = "Search sports",
                onChange = vm::setQuery,
            )
            if (sports.isEmpty()) {
                EmptyState(
                    Glyph.Search,
                    "NO MATCHING SPORT",
                    "Try a shorter word. Choose OTHER if your sport is not listed.",
                )
            } else {
                Card("CHOOSE A SPORT") {
                    sports.forEachIndexed { index, sport ->
                        if (index > 0) Divider()
                        SportRow(sport = sport, onStart = { vm.start(sport) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SportRow(sport: Sport, onStart: () -> Unit) {
    val name = sport.label()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .clickable(role = Role.Button, onClickLabel = "Start $name", onClick = onStart),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(sport.glyph(), tint = Palette.Dim, size = 22.dp)
        Text(name.uppercase(), style = label(13.sp, Palette.White))
    }
}
