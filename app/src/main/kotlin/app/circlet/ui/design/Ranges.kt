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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * A bar showing the user's own normal band (teal, top) and an ideal band (sleep blue, below),
 * with a white marker at [value]. Null [value] draws the track alone.
 */
@Composable
fun RangeBar(value: Double?, personal: ReferenceRange?, ideal: ReferenceRange?, modifier: Modifier = Modifier) {
    val axis = rangeAxis(value, personal, ideal)
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val track = 10.dp.toPx()
        val radius = CornerRadius(track / 2)
        val width = size.width
        fun at(v: Double): Float = axis.fraction(v) * width
        drawRoundRect(Palette.Track, Offset.Zero, Size(width, track), radius)
        personal?.let { band ->
            val left = at(band.low)
            drawRoundRect(
                Palette.Teal.copy(alpha = 0.45f), Offset(left, 0f),
                Size((at(band.high) - left).coerceAtLeast(2.dp.toPx()), track), radius,
            )
        }
        ideal?.let { band ->
            val left = at(band.low)
            drawRoundRect(
                Palette.Sleep.copy(alpha = 0.7f), Offset(left, track + 4.dp.toPx()),
                Size((at(band.high) - left).coerceAtLeast(2.dp.toPx()), 2.dp.toPx()), radius,
            )
        }
        if (value != null) {
            val x = at(value)
            drawRect(Palette.White, Offset(x - 1.dp.toPx(), -2.dp.toPx()), Size(2.dp.toPx(), track + 4.dp.toPx()))
        }
    }
}

/**
 * The words under a [RangeBar]: the user's own band with how many days it is built from,
 * and the ideal band or its label. [format] turns a band edge into the metric's unit.
 */
@Composable
fun RangeLegend(
    personal: ReferenceRange?,
    personalDays: Int,
    ideal: ReferenceRange?,
    idealLabel: String?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Palette.Teal.copy(alpha = 0.45f)))
            Spacer(Modifier.width(8.dp))
            val text = if (personal != null) {
                "YOUR NORMAL  ${format(personal.low)} – ${format(personal.high)}"
            } else {
                "YOUR NORMAL  BUILDING · $personalDays DAYS"
            }
            Text(text, style = label(10.sp, Palette.Faint))
        }
        if (ideal != null || idealLabel != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Palette.Sleep.copy(alpha = 0.7f)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ideal != null) "TYPICAL  ${format(ideal.low)} – ${format(ideal.high)}" else "TYPICAL",
                    style = label(10.sp, Palette.Faint),
                )
            }
            if (idealLabel != null) {
                Text(idealLabel, style = body(11.sp, Palette.Faint), modifier = Modifier.padding(start = 16.dp, top = 2.dp))
            }
        }
    }
}
