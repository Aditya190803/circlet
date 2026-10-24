package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.ReferenceRange
import kotlin.math.abs

/** The visible span of a [RangeBar], from [low] at the left edge to [high] at the right. */
data class RangeAxis(val low: Double, val high: Double) {
    /** Where [value] sits along the bar: 0 at the left edge, 1 at the right, clamped to the bar. */
    fun fraction(value: Double): Float = ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The axis a bar needs to show every band and the value. Each end is padded by a tenth of the
 * span, or by one unit when the span is zero, so no band is drawn on the edge.
 * With nothing to show the axis is 0 to 1.
 */
fun rangeAxis(value: Double?, vararg bands: ReferenceRange?): RangeAxis {
    val points = bands.filterNotNull().flatMap { listOf(it.low, it.high) } + listOfNotNull(value)
    if (points.isEmpty()) return RangeAxis(0.0, 1.0)
    val low = points.reduce(::minOf)
    val high = points.reduce(::maxOf)
    val pad = if (high > low) (high - low) * 0.1 else 1.0
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
fun Double.signedDeviation(digits: Int = 1): String = (if (this < 0) "−" else "+") + abs(this).decimal(digits)

/**
 * A thin track with the two bands drawn so they never compete: the user's own usual band
 * as a solid stretch in [accent], the ideal band as an outline around the track, and a
 * marker at [value]. Null [value] draws no marker.
 */
@Composable
fun RangeBar(
    value: Double?,
    personal: ReferenceRange?,
    ideal: ReferenceRange?,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Teal,
) {
    val axis = rangeAxis(value, personal, ideal)
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val middle = size.height / 2
        val width = size.width
        fun span(range: ReferenceRange, minimum: Float): Pair<Float, Float> {
            val left = axis.fraction(range.low) * width
            val length = (axis.fraction(range.high) * width - left).coerceAtLeast(minimum)
            return left.coerceAtMost(width - length) to length
        }
        val track = 4.dp.toPx()
        drawRoundRect(Palette.Track, Offset(0f, middle - track / 2), Size(width, track), CornerRadius(track / 2))
        ideal?.let {
            val outline = 14.dp.toPx()
            val stroke = 1.dp.toPx()
            val (left, length) = span(it, outline)
            drawRoundRect(
                IdealLine,
                Offset(left + stroke / 2, middle - outline / 2),
                Size(length - stroke, outline),
                CornerRadius(outline / 2),
                style = Stroke(stroke),
            )
        }
        personal?.let {
            val band = 6.dp.toPx()
            val (left, length) = span(it, band)
            drawRoundRect(accent, Offset(left, middle - band / 2), Size(length, band), CornerRadius(band / 2))
        }
        if (value != null) {
            val x = axis.fraction(value) * width
            val marker = Size(4.dp.toPx(), size.height - 4.dp.toPx())
            val halo = 2.dp.toPx()
            val left = (x - marker.width / 2).coerceIn(halo, width - marker.width - halo)
            drawRoundRect(
                Palette.Card,
                Offset(left - halo, 0f),
                Size(marker.width + halo * 2, size.height),
                CornerRadius(marker.width / 2 + halo),
            )
            drawRoundRect(Palette.White, Offset(left, halo), marker, CornerRadius(marker.width / 2))
        }
    }
}

private val IdealLine = Palette.Faint

/**
 * The key under a [RangeBar], drawn with the same marks as the bar: a solid swatch for the
 * user's own band, an outlined one for the ideal band. [format] turns a band edge into the metric's unit.
 */
@Composable
fun RangeLegend(
    personal: ReferenceRange?,
    personalDays: Int,
    ideal: ReferenceRange?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Teal,
) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(6.dp))
        Text("USUAL ", style = label(10.sp, Palette.Faint))
        Text(
            if (personal != null) "${format(personal.low)}–${format(personal.high)}" else "BUILDING · $personalDays DAYS",
            style = if (personal != null) number(12.sp, Palette.Dim) else label(10.sp, Palette.Faint),
        )
        if (ideal != null) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(width = 14.dp, height = 10.dp).border(1.dp, IdealLine, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text("IDEAL ", style = label(10.sp, Palette.Faint))
            Text("${format(ideal.low)}–${format(ideal.high)}", style = number(12.sp, Palette.Dim))
        }
    }
}
