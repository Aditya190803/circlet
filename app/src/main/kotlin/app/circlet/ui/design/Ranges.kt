package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * One track holding both bands: the typical band as a wide muted stretch, the user's own
 * usual band as a solid stretch inside it, and a dot at [value]. Null [value] draws no dot.
 */
@Composable
fun RangeBar(value: Double?, personal: ReferenceRange?, ideal: ReferenceRange?, modifier: Modifier = Modifier) {
    val axis = rangeAxis(value, personal, ideal)
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val track = 8.dp.toPx()
        val top = (size.height - track) / 2
        val radius = CornerRadius(track / 2)
        val width = size.width
        fun band(range: ReferenceRange, color: Color) {
            val left = axis.fraction(range.low) * width
            val length = (axis.fraction(range.high) * width - left).coerceAtLeast(track)
            drawRoundRect(color, Offset(left.coerceAtMost(width - length), top), Size(length, track), radius)
        }
        drawRoundRect(Palette.Track, Offset(0f, top), Size(width, track), radius)
        ideal?.let { band(it, TypicalBand) }
        personal?.let { band(it, Palette.Teal) }
        if (value != null) {
            val centre = Offset(axis.fraction(value) * width, size.height / 2)
            drawCircle(Palette.Card, 7.dp.toPx(), centre)
            drawCircle(Palette.White, 5.dp.toPx(), centre)
        }
    }
}

private val TypicalBand = Palette.Sleep.copy(alpha = 0.4f)

/**
 * The key under a [RangeBar]: the user's own band on the left, the typical band on
 * the right, each beside the colour it is drawn in. [format] turns a band edge into the metric's unit.
 */
@Composable
fun RangeLegend(
    personal: ReferenceRange?,
    personalDays: Int,
    ideal: ReferenceRange?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Teal))
        Spacer(Modifier.width(6.dp))
        val text = if (personal != null) {
            "YOUR USUAL  ${format(personal.low)} – ${format(personal.high)}"
        } else {
            "YOUR USUAL  BUILDING · $personalDays DAYS"
        }
        Text(text, style = label(10.sp, Palette.Dim))
        if (ideal != null) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(TypicalBand))
            Spacer(Modifier.width(6.dp))
            Text("IDEAL  ${format(ideal.low)} – ${format(ideal.high)}", style = label(10.sp, Palette.Faint))
        }
    }
}
