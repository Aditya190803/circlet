package app.circlet.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.Confidence
import kotlin.math.cos
import kotlin.math.sin

/** Alpha that breathes between 1 and [low] while something is loading. */
@Composable
fun breathing(low: Float = 0.35f): State<Float> {
    val pulse = rememberInfiniteTransition(label = "breathing")
    return pulse.animateFloat(1f, low, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
}

/**
 * The signature dial.
 * A null [fraction] draws a dashed outline for the caller to put the metric's glyph in.
 * [loading] draws a breathing track and ignores [fraction].
 * [marker] is a notch on the ring at that fraction, used for a target.
 */
@Composable
fun Ring(
    fraction: Float?,
    color: Color,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    marker: Float? = null,
    content: @Composable () -> Unit,
) {
    val sweep by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(Motion.RING, easing = FastOutSlowInEasing), label = "ring")
    val breath by breathing()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = Offset(w / 2, w / 2)
            val arc = Size(this.size.width - w, this.size.height - w)
            when {
                loading -> drawArc(Palette.Track.copy(alpha = Palette.Track.alpha * breath), 0f, 360f, false, inset, arc, style = Stroke(w))
                fraction == null -> {
                    // Fit a whole number of dashes around the circle so there is no seam.
                    val circumference = (Math.PI * arc.width).toFloat()
                    val unit = circumference / Math.round(circumference / (w * 1.5f))
                    val dash = PathEffect.dashPathEffect(floatArrayOf(unit * 0.22f, unit * 0.78f))
                    drawArc(Palette.Ghost, -90f, 360f, false, inset, arc, style = Stroke(w * 0.5f, cap = StrokeCap.Round, pathEffect = dash))
                }
                else -> {
                    drawArc(Palette.Track, 0f, 360f, false, inset, arc, style = Stroke(w))
                    if (sweep > 0f) {
                        drawArc(color, -90f, 360f * sweep, false, inset, arc, style = Stroke(w, cap = StrokeCap.Round))
                    }
                }
            }
            if (marker != null && !loading) {
                val angle = Math.toRadians(360.0 * marker.coerceIn(0f, 1f) - 90.0)
                val radius = arc.width / 2
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    Palette.White,
                    center + direction * (radius - w * 0.9f),
                    center + direction * (radius + w * 0.9f),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        content()
    }
}

@Composable
fun RingValue(value: String, unit: String, size: TextUnit, color: Color = Palette.White) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = number(size, color))
        if (unit.isNotEmpty()) Text(unit, style = number(size * 0.5f, color), modifier = Modifier.padding(bottom = (size.value * 0.14f).dp))
    }
}

/** Three rising steps. One, two or three are lit for low, medium and high confidence. */
@Composable
fun ConfidenceMark(confidence: Confidence, modifier: Modifier = Modifier) {
    val lit = when (confidence) {
        Confidence.HIGH -> 3
        Confidence.MEDIUM -> 2
        Confidence.LOW -> 1
    }
    val description = confidence.words()
    Canvas(
        modifier
            .size(width = 13.dp, height = 10.dp)
            .semantics { contentDescription = description },
    ) {
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        for (step in 0 until 3) {
            val height = size.height * (step + 2) / 4f
            drawRoundRect(
                if (step < lit) Palette.Faint else Palette.Track,
                Offset(step * (bar + gap), size.height - height),
                Size(bar, height),
                CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** A home-screen dial: ring, value, and the metric's name underneath. */
@Composable
fun MetricDial(
    title: String,
    glyph: Glyph,
    color: Color,
    value: String?,
    unit: String,
    fraction: Float?,
    confidence: Confidence? = null,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Ring(if (value == null) null else fraction, color, 104.dp, 7.dp, loading = loading) {
            when {
                loading -> Unit
                value == null -> Icon(glyph, Palette.Faint, 28.dp)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // The spacer mirrors the mark so the value stays optically centred.
                    if (confidence != null) Spacer(Modifier.height(12.dp))
                    RingValue(value, unit, 30.sp)
                    if (confidence != null) ConfidenceMark(confidence, Modifier.padding(top = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = label(12.sp, Palette.White))
            Icon(Glyph.ChevronRight, Palette.Faint, 14.dp, Modifier.padding(start = 2.dp))
        }
    }
}

/** The large dial that opens a detail screen, with one sentence under it. */
@Composable
fun HeroDial(
    caption: String,
    glyph: Glyph,
    color: Color,
    value: String?,
    unit: String,
    fraction: Float?,
    message: String,
    confidence: Confidence? = null,
    loading: Boolean = false,
    marker: Float? = null,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Ring(if (value == null) null else fraction, color, 220.dp, 13.dp, loading = loading, marker = if (value == null) null else marker) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    loading -> Unit
                    value == null -> {
                        Icon(glyph, Palette.Faint, 44.dp)
                        Spacer(Modifier.height(10.dp))
                    }
                    else -> RingValue(value, unit, 64.sp)
                }
                if (!loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(caption, style = label(12.sp))
                        if (confidence != null && value != null) {
                            Spacer(Modifier.width(8.dp))
                            ConfidenceMark(confidence)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (loading) {
            Skeleton(Modifier.size(width = 240.dp, height = 14.dp))
            Spacer(Modifier.height(8.dp))
            Skeleton(Modifier.size(width = 180.dp, height = 14.dp))
        } else {
            Text(message, style = body(), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
