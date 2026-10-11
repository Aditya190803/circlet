package app.circlet.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.Metric

@Composable
fun Card(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.card)
            .background(Palette.Card)
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = label(13.sp, Palette.White), modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.CardLine),
    )
}

/** A block that breathes where content is still loading. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val alpha by breathing(0.4f)
    Box(
        modifier
            .alpha(alpha)
            .clip(Shapes.bar)
            .background(Palette.CardLine),
    )
}

/** The short dash that stands where a number would be. */
@Composable
fun AbsentDash(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 22.dp, height = 3.dp)
            .clip(Shapes.bar)
            .background(Palette.Ghost),
    )
}

/** A small outlined word beside a name, such as ESTIMATE. */
@Composable
fun Tag(text: String, color: Color = Palette.Faint) {
    Text(
        text,
        style = label(9.sp, color),
        modifier = Modifier
            .border(1.dp, Palette.Ghost, Shapes.tile)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * One named value.
 * A null [value] draws the dash, and [absent] says why underneath the name.
 */
@Composable
fun StatRow(
    name: String,
    value: String?,
    unit: String = "",
    color: Color = Palette.White,
    note: String? = null,
    absent: AbsentReason? = null,
    tag: String? = null,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = label())
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Tag(tag)
                }
            }
            val under = if (value == null && !loading) absent?.words() ?: note else note
            if (under != null) {
                val reason = value == null && absent != null && !loading
                Text(
                    if (reason) under else under.uppercase(),
                    style = if (reason) body(12.sp, Palette.Faint) else label(10.sp, Palette.Faint),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            loading -> Skeleton(Modifier.size(width = 48.dp, height = 16.dp))
            value == null -> AbsentDash()
            else -> {
                Text(value, style = number(22.sp, color))
            }
        }
        if (unit.isNotEmpty() && !loading) Text(" $unit", style = label(11.sp, Palette.Faint))
        if (onClick != null) Icon(Glyph.ChevronRight, Palette.Faint, 18.dp, Modifier.padding(start = 6.dp))
    }
}

/** [StatRow] fed straight from a core [Metric]: value and absent reason in one. */
@Composable
fun <T : Any> MetricRow(
    name: String,
    metric: Metric<T>,
    unit: String = "",
    color: Color = Palette.White,
    note: String? = null,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
    format: (T) -> String,
) {
    StatRow(
        name = name,
        value = metric.value?.let(format),
        unit = unit,
        color = color,
        note = note,
        absent = metric.absentReason,
        tag = tag,
        onClick = onClick,
    )
}

/**
 * A large number over its caption, used in pairs across a card.
 * A null [value] draws the dash, and [absent] says why under the caption.
 */
@Composable
fun Figure(
    value: String?,
    caption: String,
    color: Color = Palette.White,
    absent: AbsentReason? = null,
    alignEnd: Boolean = false,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        when {
            loading -> Skeleton(Modifier.padding(vertical = 13.dp).size(width = 72.dp, height = 16.dp))
            value == null -> AbsentDash(Modifier.padding(vertical = 19.dp).size(width = 30.dp, height = 4.dp))
            else -> Text(value, style = number(34.sp, color))
        }
        Text(caption, style = label(11.sp))
        if (value == null && absent != null && !loading) {
            Text(absent.words(), style = body(12.sp, Palette.Faint), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Outline placeholder for a row that will exist once the strap has something to say. */
@Composable
fun GhostRow(glyph: Glyph, title: String, text: String) {
    Row(
        Modifier
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .border(1.dp, Palette.Ghost, Shapes.tile),
            contentAlignment = Alignment.Center,
        ) { Icon(glyph, Palette.Faint, 18.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = label(13.sp, Palette.Dim))
            Text(text, style = body(13.sp, Palette.Faint))
        }
    }
}

/** A row led by a filled tile holding its headline number: a sleep, a nap, an activity. */
@Composable
fun TileRow(
    tile: String?,
    color: Color,
    title: String,
    subtitle: String,
    glyph: Glyph? = null,
    detail: String? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(Shapes.tile)
                .then(if (tile != null) Modifier.background(color) else Modifier.border(1.dp, Palette.Ghost, Shapes.tile)),
            contentAlignment = Alignment.Center,
        ) {
            if (tile != null) Text(tile, style = number(17.sp, Palette.BgBottom)) else AbsentDash()
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) Icon(glyph, Palette.White, 16.dp, Modifier.padding(end = 6.dp))
                Text(title, style = label(13.sp, Palette.White))
            }
            Text(subtitle, style = label(11.sp, Palette.Faint))
            if (detail != null) Text(detail, style = label(10.sp, Palette.Faint), modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Text(trailing, style = label(11.sp, Palette.Faint))
        if (onClick != null) Icon(Glyph.ChevronRight, Palette.Faint, 18.dp, Modifier.padding(start = 6.dp))
    }
}

/** A settings-style row: glyph, name, optional line of detail, then a value, a control or a chevron. */
@Composable
fun ListRow(
    title: String,
    glyph: Glyph? = null,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = Palette.Dim,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Icon(glyph, Palette.White, 22.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = title(17.sp))
            if (subtitle != null) Text(subtitle, style = body(13.sp, Palette.Faint))
        }
        if (value != null) Text(value, style = label(12.sp, valueColor), modifier = Modifier.padding(start = 10.dp))
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (onClick != null) {
            Icon(Glyph.ChevronRight, Palette.Faint, 18.dp, Modifier.padding(start = 6.dp))
        }
    }
}

/**
 * The single contextual card a screen shows when something needs saying.
 * [footer] holds a progress bar or steps under the text.
 */
@Composable
fun NoticeCard(
    glyph: Glyph,
    heading: String,
    text: String,
    tint: Color = Palette.White,
    action: String? = null,
    onAction: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    Card {
        Row {
            Icon(glyph, tint, 22.dp, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(heading, style = title(18.sp))
                Spacer(Modifier.height(4.dp))
                Text(text, style = body(14.sp))
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(14.dp))
            footer()
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            PrimaryButton(action, onClick = onAction)
        }
    }
}

/** A whole card that has nothing to show yet, and says what will fill it. */
@Composable
fun EmptyState(glyph: Glyph, heading: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .border(1.dp, Palette.Ghost, Shapes.pill),
            contentAlignment = Alignment.Center,
        ) { Icon(glyph, Palette.Faint, 24.dp) }
        Spacer(Modifier.height(12.dp))
        Text(heading, style = label(13.sp, Palette.Dim))
        Spacer(Modifier.height(4.dp))
        Text(text, style = body(13.sp, Palette.Faint), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Something failed that the user can retry. */
@Composable
fun ErrorState(text: String, onRetry: (() -> Unit)? = null) {
    NoticeCard(
        Glyph.Warning,
        "Something went wrong",
        text,
        tint = Palette.Warn,
        action = if (onRetry != null) "TRY AGAIN" else null,
        onAction = onRetry ?: {},
    )
}
