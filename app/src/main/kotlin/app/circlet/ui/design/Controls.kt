package app.circlet.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The one filled button on a screen. [danger] is for the confirming step of a destructive action. */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val breath by breathing(0.5f)
    val active = enabled && !loading
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Shapes.control)
            .background(
                when {
                    !enabled -> Palette.CardLine
                    danger -> Palette.Danger
                    else -> Palette.White
                },
            )
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = label(13.sp, if (enabled) Palette.BgBottom else Palette.Faint),
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (loading) breath else 1f),
        )
    }
}

/** A second action that sits beside or under the primary one. */
@Composable
fun SecondaryButton(
    text: String,
    enabled: Boolean = true,
    glyph: Glyph? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Shapes.control)
            .background(Palette.CardLine)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Palette.White else Palette.Faint
        if (glyph != null) Icon(glyph, tint, 18.dp, Modifier.padding(end = 8.dp))
        Text(text, style = label(13.sp, tint), textAlign = TextAlign.Center)
    }
}

/** Text-only action. Pass [Palette.Danger] for a destructive one. */
@Composable
fun QuietButton(
    text: String,
    color: Color = Palette.Dim,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .clip(Shapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = label(12.sp, if (enabled) color else Palette.Faint), textAlign = TextAlign.Center) }
}

/** The compact filled button at the end of a row, such as ALLOW. */
@Composable
fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = Space.touch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = label(12.sp, if (enabled) Palette.BgBottom else Palette.Faint),
            modifier = Modifier
                .clip(Shapes.chip)
                .background(if (enabled) Palette.White else Palette.CardLine)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        )
    }
}

/** A row of mutually exclusive choices that fills the width it is given. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Space.touch)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(Shapes.control)
            .background(Palette.CardLine)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selected
            val fill by animateColorAsState(if (on) Palette.White else Color.Transparent, label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Shapes.chip)
                    .background(fill)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) { Text(option, style = label(12.sp, if (on) Palette.BgBottom else Palette.Dim), maxLines = 1) }
        }
    }
}

/** A tag the user can switch on and off, or a filter. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = Space.touch)
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(Shapes.pill)
                .then(if (selected) Modifier.background(Palette.White) else Modifier.border(1.dp, Palette.Ghost, Shapes.pill))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (selected) Palette.BgBottom else Palette.Dim
            if (glyph != null) Icon(glyph, tint, 16.dp, Modifier.padding(end = 6.dp))
            Text(text, style = label(12.sp, tint))
        }
    }
}

@Composable
fun Toggle(checked: Boolean, description: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Palette.Teal else Palette.CardLine, label = "track")
    val thumb by animateDpAsState(if (checked) 23.dp else 3.dp, label = "thumb")
    Box(
        Modifier
            .size(width = 56.dp, height = Space.touch)
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 50.dp, height = 30.dp)
                .clip(Shapes.pill)
                .background(track),
        ) {
            Box(
                Modifier
                    .offset(x = thumb, y = 3.dp)
                    .size(24.dp)
                    .clip(Shapes.pill)
                    .background(if (checked) Palette.BgBottom else Palette.Dim),
            )
        }
    }
}

/** A named value the user types, with the entry box at the end of the row. */
@Composable
fun Field(
    name: String,
    value: String,
    hint: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    maxLength: Int = 5,
    onChange: (String) -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 60.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = label())
            if (hint != null) Text(hint, style = body(12.sp, if (isError) Palette.Warn else Palette.Faint), modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = { text -> if (text.length <= maxLength) onChange(text.filter { it.isDigit() || it == '.' }) },
            enabled = enabled,
            singleLine = true,
            textStyle = number(20.sp).copy(textAlign = TextAlign.End),
            cursorBrush = SolidColor(Palette.Teal),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .width(96.dp)
                .heightIn(min = Space.touch)
                .clip(Shapes.chip)
                .background(Palette.CardLine)
                .then(if (isError) Modifier.border(1.dp, Palette.Warn, Shapes.chip) else Modifier)
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .semantics { contentDescription = name },
        )
    }
}

/** A full-width line of free text, such as a name or a note. */
@Composable
fun TextEntry(
    value: String,
    description: String,
    placeholder: String = "",
    maxLength: Int = 24,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onChange(it) },
        enabled = enabled,
        singleLine = true,
        textStyle = title(18.sp),
        cursorBrush = SolidColor(Palette.Teal),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(Shapes.control)
                    .background(Palette.CardLine)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = title(18.sp, Palette.Faint))
                inner()
            }
        },
    )
}

/** A panel that rises from the bottom for one short task or a confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Card,
        contentColor = Palette.White,
        scrimColor = Palette.Scrim,
        shape = Shapes.sheet,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(Shapes.pill)
                    .background(Palette.Ghost),
            )
        },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
            Text(title, style = title(22.sp))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** A dot that breathes while something is live. */
@Composable
fun LiveDot(color: Color, active: Boolean) {
    val alpha by breathing()
    Box(
        Modifier
            .size(7.dp)
            .alpha(if (active) alpha else 1f)
            .clip(Shapes.pill)
            .background(color),
    )
}

/** Thin bar that sweeps while work of unknown length is running. */
@Composable
fun Sweep(color: Color, modifier: Modifier = Modifier) {
    val move = rememberInfiniteTransition(label = "sweep")
    val x by move.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "x")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(Shapes.bar)
            .background(Palette.Track),
    ) {
        drawRect(color, Offset(size.width * x, 0f), Size(size.width * 0.35f, size.height))
    }
}

/** Segments for a count that is known: steps of a flow, nights of calibration. */
@Composable
fun ProgressSteps(done: Int, total: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(Shapes.bar)
                    .background(if (i < done) color else Palette.Track),
            )
        }
    }
}

/** A short line that appears above the navigation bar and goes away by itself. */
@Composable
fun MessageBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = Space.screen, vertical = Space.sm)
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .clip(Shapes.control)
            .background(Palette.CardRaised)
            .border(1.dp, Palette.CardLine, Shapes.control)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) { Text(text, style = body(14.sp, Palette.White)) }
}
