package app.circlet.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import app.circlet.ui.design.narrowDay
import java.time.LocalDate
import kotlin.math.roundToInt

/** The scrolling body every tab and pushed screen shares: one column of cards. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
        if (action != null) action() else Spacer(Modifier.width(Space.touch))
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
        val tint = when {
            !connected -> Palette.Faint
            (percent ?: 100) < 20 -> Palette.Danger
            else -> Palette.White
        }
        Column(
            Modifier
                .size(Space.touch)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Strap status", onClick = onStrap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BatteryGlyph(if (connected) percent else null, tint)
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

fun List<DaySummary>.dayLabels(): List<String> = map { it.date.narrowDay() }
