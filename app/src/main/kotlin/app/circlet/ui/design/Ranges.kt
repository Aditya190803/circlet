package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.ReferenceRange
import kotlin.math.abs
import kotlin.math.roundToInt

/** The visible span of a [RangeBar], from [low] at the left edge to [high] at the right. */
data class RangeAxis(val low: Double, val high: Double) {
    /** Where [value] sits along the bar: 0 at the left edge, 1 at the right, clamped to the bar. */
    fun fraction(value: Double): Float = ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The axis a bar needs to show every band and the value. Each end is padded by three tenths of the
 * span, or by one unit when the span is zero, so the bar shows room either side of its bands.
 * With nothing to show the axis is 0 to 1.
 */
fun rangeAxis(value: Double?, vararg bands: ReferenceRange?): RangeAxis {
    val points = bands.filterNotNull().flatMap { listOf(it.low, it.high) } + listOfNotNull(value)
    if (points.isEmpty()) return RangeAxis(0.0, 1.0)
    val low = points.reduce(::minOf)
    val high = points.reduce(::maxOf)
    val pad = if (high > low) (high - low) * 0.3 else 1.0
    return RangeAxis(low - pad, high + pad)
}

/**
 * Where [value] sits against the user's own [band], as a quiet tag. Both ends are inclusive.
 * Null when either side is missing, so the caller draws no tag.
 */
fun positionTag(value: Double?, band: ReferenceRange?): String? {
    if (value == null || band == null) return null
    return when {
        value < band.low -> "BELOW RANGE"
        value > band.high -> "ABOVE RANGE"
        else -> "IN RANGE"
    }
}

/** The reference for [kind] in a day's references, or null when the day has none for it. */
fun List<MetricReference>.forKind(kind: MetricKind): MetricReference? = firstOrNull { it.kind == kind }

/** A skin temperature deviation as a signed number with no unit. The caller adds "SD". */
fun Double.signedDeviation(digits: Int = 1): String {
    val size = abs(this).decimal(digits)
    return when {
        size.none { it in '1'..'9' } -> size
        this < 0 -> "−$size"
        else -> "+$size"
    }
}

/** Which way along a range bar is better: towards the middle of the good band, or past one end of it. */
enum class Toward { MIDDLE, HIGH, LOW }

/** HRV and VO2 max are better higher, resting heart rate lower; everything else is best inside its band. */
fun MetricKind.toward(): Toward = when (this) {
    MetricKind.HRV, MetricKind.VO2_MAX -> Toward.HIGH
    MetricKind.RESTING_HEART_RATE -> Toward.LOW
    else -> Toward.MIDDLE
}

/**
 * How good a point on a range bar is, from 0 to 1. Inside [good] it is at least 0.5, rising to 1
 * at the band's middle, or at its better end and beyond for [Toward.HIGH] and [Toward.LOW].
 * Outside it falls below 0.5, reaching 0 a third of the [span] away. Null [good] is 0.5 everywhere.
 */
fun rangeScore(at: Double, good: ReferenceRange?, span: Double, toward: Toward = Toward.MIDDLE): Float {
    if (good == null) return 0.5f
    val width = good.high - good.low
    if (toward == Toward.HIGH && at > good.high || toward == Toward.LOW && at < good.low) return 1f
    if (at in good.low..good.high) {
        val closeness = when {
            width <= 0 -> 1.0
            toward == Toward.HIGH -> (at - good.low) / width
            toward == Toward.LOW -> (good.high - at) / width
            else -> 1 - abs(at - (good.low + width / 2)) / (width / 2)
        }
        return (0.5 + 0.5 * closeness).toFloat()
    }
    val away = if (at < good.low) good.low - at else at - good.high
    return (0.5 * (1 - (away / (span / 3)).coerceAtMost(1.0))).toFloat().coerceAtMost(0.49f)
}

/** The colour of a [rangeScore]: orange far from the good band, brown near it, teal inside it. */
fun rangeColor(score: Float): Color = when {
    score < 0.5f -> lerp(RangeFar, RangeNear, score / 0.5f)
    else -> lerp(RangeEdge, Palette.Teal, (score - 0.5f) / 0.5f)
}

private val RangeFar = Color(0xFFFCA82B)
private val RangeNear = Color(0xFF7A5C34)
private val RangeEdge = Color(0xFF1C7A66)
private val Usual = Palette.Dim
private const val SEGMENTS = 10

/**
 * The range bar, drawn the way WHOOP's Healthspan draws one: [valueText] above a white
 * marker at [value], over a segmented track coloured by closeness to the [ideal] band (or the
 * [personal] one without it) and [toward] its better side, with the user's own usual band as a
 * grey stretch underneath.
 * Null [value] draws no marker.
 */
@Composable
fun RangeBar(
    value: Double?,
    personal: ReferenceRange?,
    ideal: ReferenceRange?,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    toward: Toward = Toward.MIDDLE,
    usualText: String? = null,
) {
    val axis = rangeAxis(value, personal, ideal)
    val at = value?.let(axis::fraction)
    Column(modifier.fillMaxWidth()) {
        if (at != null && valueText != null) {
            Pinned(at) { Text(valueText, style = number(18.sp)) }
            Spacer(Modifier.height(2.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(if (personal != null) 32.dp else 20.dp)) {
            val width = size.width
            val marker = 9.dp.toPx()
            val track = 6.dp.toPx()
            val top = marker + 2.dp.toPx()
            val gap = 3.dp.toPx()
            val segment = (width - gap * (SEGMENTS - 1)) / SEGMENTS
            val good = ideal ?: personal
            val span = axis.high - axis.low
            for (i in 0 until SEGMENTS) {
                val left = i * (segment + gap)
                val middle = axis.low + span * (left + segment / 2) / width
                val color = if (good == null) Palette.Ghost else rangeColor(rangeScore(middle, good, span, toward))
                drawRoundRect(color, Offset(left, top), Size(segment, track), CornerRadius(track / 2))
            }
            personal?.let {
                // A bracket under the track, open towards it, so the usual stretch reads as a span.
                val least = 12.dp.toPx()
                val middle = (axis.fraction(it.low) + axis.fraction(it.high)) / 2 * width
                val half = maxOf(least, (axis.fraction(it.high) - axis.fraction(it.low)) * width) / 2
                val left = (middle - half).coerceIn(1.dp.toPx(), width - least)
                val right = (middle + half).coerceIn(left + least, width - 1.dp.toPx())
                val tick = top + track + 4.dp.toPx()
                val y = tick + 6.dp.toPx()
                val stroke = 1.5.dp.toPx()
                val bracket = Path().apply {
                    moveTo(left, tick); lineTo(left, y); lineTo(right, y); lineTo(right, tick)
                }
                drawPath(bracket, Usual, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            if (at != null) {
                val x = (at * width).coerceIn(marker * 0.6f, width - marker * 0.6f)
                val path = Path().apply {
                    moveTo(x - marker * 0.65f, 0f)
                    lineTo(x + marker * 0.65f, 0f)
                    lineTo(x, marker)
                    close()
                }
                drawPath(path, Palette.White)
                drawLine(Palette.White, Offset(x, marker - 1f), Offset(x, top + track), strokeWidth = 2.dp.toPx())
            }
        }
        if (personal != null && usualText != null) {
            Spacer(Modifier.height(3.dp))
            Pinned((axis.fraction(personal.low) + axis.fraction(personal.high)) / 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("USUAL ", style = label(10.sp, Palette.Faint), maxLines = 1, softWrap = false)
                    Text(usualText, style = number(13.sp, Palette.Dim), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** One line of [content] centred on [at], a fraction of the width, and kept inside the bar's edges. */
@Composable
private fun Pinned(at: Float, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placed = measurables.single().measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, placed.height) {
            val x = (at * constraints.maxWidth - placed.width / 2f).roundToInt()
            placed.place(x.coerceIn(0, (constraints.maxWidth - placed.width).coerceAtLeast(0)), 0)
        }
    }
}

/**
 * The key under a [RangeBar] for what the bar does not label itself: the ideal band, named
 * [idealName], and the usual band while it is still building. Draws nothing when neither applies.
 * [format] turns a band edge into the metric's unit.
 */
@Composable
fun RangeLegend(
    personal: ReferenceRange?,
    personalDays: Int,
    ideal: ReferenceRange?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    idealName: String = "IDEAL",
) {
    if (personal != null && ideal == null) {
        Spacer(modifier.height(10.dp))
        return
    }
    // Each part keeps its interval on one line; the two parts stack rather than squeeze.
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ideal != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).clip(CircleShape).background(Palette.Teal))
                Spacer(Modifier.width(6.dp))
                Text("$idealName ", style = label(10.sp, Palette.Faint), maxLines = 1, softWrap = false)
                Text("${format(ideal.low)}–${format(ideal.high)}", style = number(13.sp, Palette.Teal), maxLines = 1, softWrap = false)
            }
        }
        if (personal == null) {
            Text("USUAL · BUILDING · $personalDays DAYS", style = label(10.sp, Palette.Faint), maxLines = 1, softWrap = false)
        }
    }
}
