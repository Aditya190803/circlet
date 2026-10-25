package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Where a value sits against its baseline. [up] is null when the two read the same;
 * [better] is null when the metric has no good direction.
 */
data class Change(val up: Boolean?, val better: Boolean?)

/**
 * Compares [value] with [baseline]. Differences smaller than [step], the smallest unit the
 * value is shown in, read as no change. Null when either side is missing.
 */
fun change(value: Double?, baseline: Double?, higherIsBetter: Boolean?, step: Double): Change? {
    if (value == null || baseline == null) return null
    val diff = value - baseline
    if (abs(diff) < step) return Change(null, null)
    val up = diff > 0
    return Change(up, higherIsBetter?.let { it == up })
}

/** The small triangle beside a value: up or down, coloured by whether that is good news. */
@Composable
fun ChangeMark(change: Change?, modifier: Modifier = Modifier) {
    val color = when (change?.better) {
        true -> Palette.Better
        false -> Palette.Worse
        null -> Palette.Faint
    }
    Canvas(modifier.size(10.dp)) {
        val up = change?.up
        if (up == null) {
            if (change != null) drawCircle(color, size.minDimension / 3.2f)
            return@Canvas
        }
        val w = size.width
        val h = size.height * 0.62f
        val top = (size.height - h) / 2
        val path = Path().apply {
            if (up) {
                moveTo(w / 2, top); lineTo(w, top + h); lineTo(0f, top + h)
            } else {
                moveTo(0f, top); lineTo(w, top); lineTo(w / 2, top + h)
            }
            close()
        }
        drawPath(path, color)
    }
}

/**
 * One metric against its own baseline: glyph and name, then the value with the baseline under
 * it and a [ChangeMark] beside. A null [value] draws the dash.
 */
@Composable
fun BaselineRow(
    glyph: Glyph,
    name: String,
    value: String?,
    baseline: String?,
    change: Change?,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, Palette.Dim, 24.dp)
        Spacer(Modifier.width(14.dp))
        Text(name, style = label(13.sp, Palette.White), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (value == null) {
                AbsentDash(Modifier.padding(vertical = 12.dp))
            } else {
                Text(value, style = number(26.sp))
            }
            if (baseline != null) Text(baseline, style = number(14.sp, Palette.Faint))
        }
        Spacer(Modifier.width(8.dp))
        ChangeMark(change, Modifier.padding(bottom = if (baseline != null) 18.dp else 0.dp))
    }
}

/** A [BaselineRow] standing on its own as a tile, the way Home lists its statistics. */
@Composable
fun BaselineTile(
    glyph: Glyph,
    name: String,
    value: String?,
    baseline: String?,
    change: Change?,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.card)
            .background(Palette.CardFill)
            .padding(horizontal = 20.dp),
    ) { BaselineRow(glyph, name, value, baseline, change, onClick) }
}

/** The footnote under a card of [BaselineRow]s naming what the small numbers are. */
@Composable
fun BaselineLegend(days: Int, today: Boolean) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(Shapes.control)
            .background(Palette.BgBottom.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChangeMark(Change(up = true, better = true))
        ChangeMark(Change(up = false, better = false))
        Spacer(Modifier.width(4.dp))
        Text("${if (today) "Today" else "This day"} vs. last $days days", style = body(14.sp, Palette.Dim))
    }
}

/** A sentence-case heading between groups of cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = title(26.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
        trailing?.invoke()
    }
}
