package app.circlet.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.DaySummary
import app.circlet.core.LinkState
import app.circlet.core.StrapState
import app.circlet.ui.design.BatteryGlyph
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.IconButton
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Space
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.label
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Counts taps on the bottom bar. A screen that sees the count move since it last looked
 * scrolls back to its top, so a tap on any tab lands at the top of that tab.
 */
val LocalTabTaps = staticCompositionLocalOf<StateFlow<Int>> { MutableStateFlow(0) }

/** The scrolling body every tab and pushed screen shares: one column of cards. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val taps by LocalTabTaps.current.collectAsState()
    var seen by rememberSaveable { mutableIntStateOf(taps) }
    LaunchedEffect(taps) {
        if (taps != seen) {
            seen = taps
            scroll.animateScrollTo(0)
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = Space.screen),
        verticalArrangement = Arrangement.spacedBy(Space.cardGap),
    ) {
        content()
        Spacer(Modifier.height(Space.lg))
    }
}

/**
 * Title bar for a detail or pushed screen. A null [onBack] leaves the back slot empty;
 * [action] fills the slot opposite it with one touch-sized control.
 */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(Glyph.ChevronLeft, "Back", onClick = onBack) else Spacer(Modifier.size(Space.touch))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = label(15.sp, Palette.White))
            if (subtitle != null) Text(subtitle, style = label(11.sp, Palette.Faint))
        }
        // The slot keeps its width even when the action draws nothing, so the title stays centred.
        Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) { action?.invoke() }
    }
}

/** Home's bar: profile, the date with its two arrows, and the strap's battery. */
@Composable
fun TopBar(
    date: LocalDate,
    today: LocalDate,
    strap: StrapState,
    onShift: (Long) -> Unit,
    onProfile: () -> Unit,
    onStrap: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Glyph.Person, "Profile", onClick = onProfile)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Glyph.ChevronLeft, "Previous day") { onShift(-1) }
            Text(dateTitle(date, today), style = label(13.sp, Palette.White), textAlign = TextAlign.Center, modifier = Modifier.width(112.dp))
            IconButton(Glyph.ChevronRight, "Next day", enabled = date < today) { onShift(1) }
        }
        Spacer(Modifier.weight(1f))
        val connected = strap.link == LinkState.CONNECTED
        val percent = strap.batteryPercent?.roundToInt()
        val charging = connected && strap.charging == true
        val tint = when {
            !connected -> Palette.Faint
            charging -> Palette.Teal
            (percent ?: 100) < 20 -> Palette.Danger
            else -> Palette.White
        }
        Column(
            Modifier
                .size(Space.touch)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Strap status", onClick = onStrap)
                .semantics(mergeDescendants = true) {
                    contentDescription = when {
                        !connected -> "Strap offline"
                        charging -> "Strap charging" + (percent?.let { ", $it percent" } ?: "")
                        else -> percent?.let { "Strap battery $it percent" } ?: "Strap connected"
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BatteryGlyph(if (connected) percent else null, tint, charging)
            Text(
                if (connected) percent?.let { "$it%" } ?: "" else "OFF",
                style = label(10.sp, tint),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** A mean worth showing: two or more real values. */
fun average(values: List<Double>): Double? = if (values.size >= 2) values.average() else null

/** "Sun" over "20", the way a week of bars is labelled. */
fun List<DaySummary>.dayLabels(): List<String> = map {
    it.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + "\n" + it.date.dayOfMonth
}
