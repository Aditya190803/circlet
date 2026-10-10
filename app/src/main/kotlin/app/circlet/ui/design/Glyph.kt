package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val RING = "M12,20.5 A8.5,8.5 0 1 0 12,3.5 A8.5,8.5 0 0 0 12,20.5 Z"

/** Stroke icons drawn on a 24-unit grid so every glyph shares one weight. */
enum class Glyph(val path: String) {
    ChevronLeft("M15,5 L8,12 L15,19"),
    ChevronRight("M9,5 L16,12 L9,19"),
    ChevronDown("M5,9 L12,16 L19,9"),
    Close("M6,6 L18,18 M18,6 L6,18"),
    Check("M5,12.5 L10,17.5 L19,7"),
    Plus("M12,5 V19 M5,12 H19"),
    More("M6.5,12 A1,1 0 1 0 4.5,12 A1,1 0 1 0 6.5,12 Z M13,12 A1,1 0 1 0 11,12 A1,1 0 1 0 13,12 Z M19.5,12 A1,1 0 1 0 17.5,12 A1,1 0 1 0 19.5,12 Z"),
    Edit("M4.5,19.5 L5.5,15.5 L16,5 A2.1,2.1 0 0 1 19,8 L8.5,18.5 Z M14.5,6.5 L17.5,9.5"),
    Trash("M4.5,7 H19.5 M9.5,7 V4.5 H14.5 V7 M6.5,7 L7.5,20 H16.5 L17.5,7 M10.5,11 V16.5 M13.5,11 V16.5"),
    Search("M10.5,17 A6.5,6.5 0 1 0 10.5,4 A6.5,6.5 0 0 0 10.5,17 Z M15.2,15.2 L20,20"),
    Info("$RING M12,11 V16.5 M12,7.6 V7.7"),
    Warning("M12,4 L21,19.5 H3 Z M12,10 V14.5 M12,17 V17.1"),

    Home("M4,11 L12,4 L20,11 M6.5,9.5 V20 H17.5 V9.5"),
    Moon("M19.5,14.2 A8,8 0 1 1 9.8,4.5 A6.4,6.4 0 0 0 19.5,14.2 Z"),
    Pulse("M3,12 H8 L10.5,6 L13.5,18 L16,12 H21"),
    Bolt("M13,3 L5,13.5 H11 L10,21 L19,10 H13 Z"),
    Heart("M12,20 C5,15 3.5,11.5 3.5,8.5 A4.25,4.25 0 0 1 12,7 A4.25,4.25 0 0 1 20.5,8.5 C20.5,11.5 19,15 12,20 Z"),
    Flame("M12,21 C8,21 5.5,18.2 5.5,14.8 C5.5,11 9,9.5 9.5,4 C13,6 18.5,10 18.5,14.8 C18.5,18.2 16,21 12,21 Z"),
    Target("$RING M12,15.5 A3.5,3.5 0 1 0 12,8.5 A3.5,3.5 0 0 0 12,15.5 Z"),
    Steps(
        "M8.5,3.5 C10.5,3.5 11.5,5.5 11.5,8 C11.5,10 10.8,11 10.5,12.5 H6.5 C6.2,11 5.5,10 5.5,8 C5.5,5.5 6.5,3.5 8.5,3.5 Z " +
            "M6.7,15 H10.3 V15.5 A1.8,1.8 0 0 1 6.7,15.5 Z " +
            "M15.5,7.5 C17.5,7.5 18.5,9.5 18.5,12 C18.5,14 17.8,15 17.5,16.5 H13.5 C13.2,15 12.5,14 12.5,12 C12.5,9.5 13.5,7.5 15.5,7.5 Z " +
            "M13.7,19 H17.3 V19.5 A1.8,1.8 0 0 1 13.7,19.5 Z",
    ),
    Stress("M3,9 C5.5,5.5 8,5.5 10.5,9 S15.5,12.5 21,9 M3,15 C5.5,11.5 8,11.5 10.5,15 S15.5,18.5 21,15"),
    Breathing("M3,9 H13.5 A2.5,2.5 0 1 0 11,6.5 M3,13 H18 A2.5,2.5 0 1 1 15.5,15.5 M3,17 H10.5 A2,2 0 1 1 8.5,19"),

    Alarm("M12,21 A7.5,7.5 0 1 0 12,6 A7.5,7.5 0 0 0 12,21 Z M12,9.5 V13.5 L14.5,15 M4,6.5 L7,4 M20,6.5 L17,4"),
    Clock("$RING M12,7.5 V12 L15,14"),
    Journal("M6,3.5 H17 A1.5,1.5 0 0 1 18.5,5 V19 A1.5,1.5 0 0 1 17,20.5 H6 Z M9.5,8 H15 M9.5,11.5 H15"),
    Trends("M4,17 L9,11.5 L13,14.5 L20,6.5 M15.5,6.5 H20 V11"),
    Calendar("M5,5.5 H19 A1.5,1.5 0 0 1 20.5,7 V19 A1.5,1.5 0 0 1 19,20.5 H5 A1.5,1.5 0 0 1 3.5,19 V7 A1.5,1.5 0 0 1 5,5.5 Z M3.5,10 H20.5 M8,3 V7 M16,3 V7"),
    Export("M12,15 V4 M8,8 L12,4 L16,8 M5,13.5 V18.5 A1.5,1.5 0 0 0 6.5,20 H17.5 A1.5,1.5 0 0 0 19,18.5 V13.5"),
    Settings("M4,7 H11 M15,7 H20 M13,5 V9 M4,12 H7 M11,12 H20 M9,10 V14 M4,17 H14 M18,17 H20 M16,15 V19"),
    Ruler("M3.5,8 H20.5 V16 H3.5 Z M7.5,8 V11 M11,8 V12.5 M14.5,8 V11 M18,8 V12.5"),
    Document("M7,3.5 H14 L18.5,8 V19 A1.5,1.5 0 0 1 17,20.5 H7 A1.5,1.5 0 0 1 5.5,19 V5 A1.5,1.5 0 0 1 7,3.5 Z M14,3.5 V8 H18.5 M9,12.5 H15 M9,16 H15"),
    Grid("M4.5,4.5 H10.5 V10.5 H4.5 Z M13.5,4.5 H19.5 V10.5 H13.5 Z M4.5,13.5 H10.5 V19.5 H4.5 Z M13.5,13.5 H19.5 V19.5 H13.5 Z"),
    Lock("M7,11 H17 A1.5,1.5 0 0 1 18.5,12.5 V19 A1.5,1.5 0 0 1 17,20.5 H7 A1.5,1.5 0 0 1 5.5,19 V12.5 A1.5,1.5 0 0 1 7,11 Z M8.5,11 V8 A3.5,3.5 0 0 1 15.5,8 V11"),
    Shield("M12,3 L19,6 V11.5 C19,16 16,19.5 12,21 C8,19.5 5,16 5,11.5 V6 Z"),
    Person("M12,12 A4,4 0 1 0 12,4 A4,4 0 0 0 12,12 Z M4.5,20.5 C5.5,16.5 8.5,15 12,15 C15.5,15 18.5,16.5 19.5,20.5"),

    Strap("M9,2.5 H15 V6 M9,21.5 H15 V18 M8,6 H16 A2,2 0 0 1 18,8 V16 A2,2 0 0 1 16,18 H8 A2,2 0 0 1 6,16 V8 A2,2 0 0 1 8,6 Z"),
    Bluetooth("M7,7.5 L17,16.5 L12,21 V3 L17,7.5 L7,16.5"),
    Bell("M6,17 V11 A6,6 0 0 1 18,11 V17 L19.5,19 H4.5 Z M10,21.5 H14"),
    Power("M4,8 H17 A1.5,1.5 0 0 1 18.5,9.5 V14.5 A1.5,1.5 0 0 1 17,16 H4 A1.5,1.5 0 0 1 2.5,14.5 V9.5 A1.5,1.5 0 0 1 4,8 Z M21.5,10.5 V13.5 M10.5,9.5 L8,12.3 H11 L9.5,14.8"),
    Sync("M19.5,12 A7.5,7.5 0 0 1 6,16.5 M4.5,12 A7.5,7.5 0 0 1 18,7.5 M18,3.5 V7.5 H14 M6,20.5 V16.5 H10"),
    Vibrate("M9,5 H15 A1.5,1.5 0 0 1 16.5,6.5 V17.5 A1.5,1.5 0 0 1 15,19 H9 A1.5,1.5 0 0 1 7.5,17.5 V6.5 A1.5,1.5 0 0 1 9,5 Z M4,9 V15 M20,9 V15"),
    Broadcast("M12,13.5 A1.5,1.5 0 1 0 12,10.5 A1.5,1.5 0 0 0 12,13.5 Z M8,16 A5.5,5.5 0 0 1 8,8 M16,8 A5.5,5.5 0 0 1 16,16 M5.2,18.8 A9.5,9.5 0 0 1 5.2,5.2 M18.8,5.2 A9.5,9.5 0 0 1 18.8,18.8"),

    Run("M14.5,5.5 A1.5,1.5 0 1 0 14.5,2.5 A1.5,1.5 0 0 0 14.5,5.5 Z M7,10.5 L10.5,8 L14,9 L16,12.5 H19.5 M14,9 L11.5,14 L14.5,16.5 V21 M11.5,14 L9.5,17.5 H5.5"),
    Walk("M12.5,5.5 A1.5,1.5 0 1 0 12.5,2.5 A1.5,1.5 0 0 0 12.5,5.5 Z M12,8.5 L10.5,14.5 L13.5,17 V21.5 M10.5,14.5 L8,21.5 M12,8.5 L8.5,11 V14 M12,8.5 L14.5,12 L17,13"),
    Cycle("M6,20 A3.5,3.5 0 1 0 6,13 A3.5,3.5 0 0 0 6,20 Z M18,20 A3.5,3.5 0 1 0 18,13 A3.5,3.5 0 0 0 18,20 Z M6,16.5 L10,9.5 H15 L18,16.5 M10,9.5 L13,16.5 M8.5,6.5 H11 L10,9.5 M14,6 H16.5 L15,9.5"),
    Swim("M3,18 C5,16 7,16 9,18 S13,20 15,18 S19,16 21,18 M6.5,13.5 L11,9 L14.5,12.5 M11,9 L15,7 M18,10.5 A1.5,1.5 0 1 0 18,7.5 A1.5,1.5 0 0 0 18,10.5 Z"),
    Strength("M8,12 H16 M5.5,8 V16 M8,6.5 V17.5 M16,6.5 V17.5 M18.5,8 V16 M3,10.5 V13.5 M21,10.5 V13.5"),
    Yoga("M12,6 A1.5,1.5 0 1 0 12,3 A1.5,1.5 0 0 0 12,6 Z M12,8.5 V14 M5,10.5 L12,9 L19,10.5 M12,14 L6.5,17 L10,20 H14 L17.5,17 Z"),
    Ball("$RING M3.5,12 H20.5 M12,3.5 V20.5 M6,6 C8.5,8.5 8.5,15.5 6,18 M18,6 C15.5,8.5 15.5,15.5 18,18"),
    Mountain("M2.5,19.5 L9,7 L13,14 L15.5,10.5 L21.5,19.5 Z M7.2,10.5 L9,12 L10.7,10.5"),
}

@Composable
fun Icon(glyph: Glyph, tint: Color = Palette.White, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(path, tint, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun IconButton(
    glyph: Glyph,
    description: String,
    tint: Color = Palette.White,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(Space.touch)
            .clip(Shapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, if (enabled) tint else Palette.Ghost) }
}
