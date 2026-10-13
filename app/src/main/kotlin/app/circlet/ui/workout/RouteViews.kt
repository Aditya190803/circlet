package app.circlet.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.circlet.core.Route
import app.circlet.core.RouteKind
import app.circlet.core.RoutePoint
import app.circlet.ui.design.Palette
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/** A pace for display: minutes per kilometre on foot, kilometres per hour on a bike. */
data class RoutePace(val value: String, val unit: String) {
    companion object {
        fun of(kind: RouteKind, distanceM: Double, seconds: Long): RoutePace? = when (kind) {
            RouteKind.FOOT -> Route.paceMinPerKm(distanceM, seconds)?.let {
                val total = (it * 60).roundToInt()
                RoutePace("%d:%02d".format(Locale.US, total / 60, total % 60), "MIN/KM")
            }
            RouteKind.CYCLING -> Route.speedKmh(distanceM, seconds)?.let {
                RoutePace("%.1f".format(Locale.US, it), "KM/H")
            }
        }
    }
}

fun distanceKm(metres: Double): String = "%.2f".format(Locale.US, metres / 1000.0)

/** The track fitted to the box with equal scale on both axes (equirectangular projection). */
@Composable
fun RouteTrack(points: List<RoutePoint>) {
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        if (points.size < 2) return@Canvas
        val meanLat = Math.toRadians(points.sumOf { it.lat } / points.size)
        val xs = points.map { it.lon * cos(meanLat) }
        val ys = points.map { it.lat }
        val minX = xs.min()
        val minY = ys.min()
        val spanX = (xs.max() - minX).coerceAtLeast(1e-9)
        val spanY = (ys.max() - minY).coerceAtLeast(1e-9)
        val pad = 12.dp.toPx()
        val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val offsetX = (size.width - spanX * scale) / 2
        val offsetY = (size.height - spanY * scale) / 2
        fun at(i: Int) = Offset((offsetX + (xs[i] - minX) * scale).toFloat(), (size.height - offsetY - (ys[i] - minY) * scale).toFloat())
        val line = Path()
        points.indices.forEach { i -> at(i).let { if (i == 0) line.moveTo(it.x, it.y) else line.lineTo(it.x, it.y) } }
        drawPath(line, Palette.Teal, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Palette.White, 4.dp.toPx(), at(0))
        drawCircle(Palette.Teal, 5.dp.toPx(), at(points.lastIndex))
    }
}
