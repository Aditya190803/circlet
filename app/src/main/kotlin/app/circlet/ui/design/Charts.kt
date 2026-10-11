package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.StressPoint
import app.circlet.core.ZoneMinutes
import java.time.Duration
import java.time.Instant

@Composable
private fun DayAxis() {
    val axis = LocalTimeFormat.current.dayAxis
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (tick in axis) Text(tick, style = label(10.sp, Palette.Faint))
    }
}

@Composable
private fun ChartNote(text: String) {
    Text(
        text,
        style = body(13.sp, Palette.Faint),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(Palette.Card)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Press and hold, then drag, to read a chart. Reports the touch as a fraction of the
 * chart width, and null when the finger lifts. Waiting for the hold leaves a plain
 * vertical swipe to the page scroll.
 */
fun Modifier.scrub(key: Any?, onChange: (Float?) -> Unit): Modifier = pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { onChange((it.x / size.width).coerceIn(0f, 1f)) },
        onDrag = { change, _ ->
            change.consume()
            onChange((change.position.x / size.width).coerceIn(0f, 1f))
        },
        onDragEnd = { onChange(null) },
        onDragCancel = { onChange(null) },
    )
}

/** The line above a chart that shows what is under the finger, or a quiet hint when nothing is held. */
@Composable
private fun ScrubReadout(value: String?, detail: String?, idle: String?) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(value, style = number(14.sp, Palette.White))
            if (detail != null) Text("  $detail", style = label(11.sp, Palette.Dim))
        } else if (idle != null) {
            Text(idle, style = label(10.sp, Palette.Ghost))
        }
    }
}

/** The five-minute bucket a touch at [fraction] of the chart width falls in, or null with no reading there. */
internal fun scrubBucket(minutes: List<Int?>, fraction: Float): IntRange? {
    if (minutes.isEmpty()) return null
    val minute = (fraction.coerceIn(0f, 1f) * (minutes.size - 1)).toInt()
    val start = minute - minute % 5
    val range = start until minOf(start + 5, minutes.size)
    return if (range.any { minutes[it] != null }) range else null
}

/** Minute of the day as a clock time. */
internal fun minuteLabel(minute: Int, twentyFourHour: Boolean): String {
    val hour = minute / 60
    val min = minute % 60
    if (twentyFourHour) return "%02d:%02d".format(hour, min)
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(h, min, if (hour < 12) "AM" else "PM")
}

/**
 * A day of heart rate, midnight to midnight.
 * [minutes] holds one entry per minute; null is a minute with no sample and breaks the line.
 * An empty or all-null list draws the grid with [emptyText] over it.
 * Press and hold, then drag, to read the chart: a vertical line marks the time, a horizontal
 * line the heart rate, and the readout gives the value with the range inside that five minutes.
 */
@Composable
fun HeartRateChart(
    minutes: List<Int?>,
    color: Color,
    emptyText: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val valid = minutes.filterNotNull()
    val twentyFourHour = LocalTimeFormat.current.twentyFourHour
    var touch by remember { mutableStateOf<Float?>(null) }
    val lo = if (valid.isEmpty()) 0f else valid.min() - 5f
    val hi = if (valid.isEmpty()) 1f else valid.max() + 5f
    val bucket = touch?.let { scrubBucket(minutes, it) }
    Column(modifier) {
        // The readout keeps its line whether or not a point is held, so the chart does not jump.
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bucket != null) {
                val readings = bucket.mapNotNull { minutes[it] }
                Text("${readings.average().roundToInt()} BPM", style = number(14.sp, Palette.White))
                Text(
                    "  ${minuteLabel(bucket.first, twentyFourHour)}",
                    style = label(11.sp, Palette.Dim),
                )
                Spacer(Modifier.weight(1f))
                Text("RANGE ${readings.min()}–${readings.max()}", style = label(10.sp, Palette.Faint))
            } else if (valid.isNotEmpty() && !loading) {
                Text("HOLD AND DRAG TO READ", style = label(10.sp, Palette.Ghost))
                Spacer(Modifier.weight(1f))
                Text("DAY RANGE ${valid.min()}–${valid.max()}", style = label(10.sp, Palette.Faint))
            }
        }
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .scrub(minutes.size) { touch = it },
            ) {
                for (i in 0..3) {
                    val y = size.height * i / 3
                    drawLine(Palette.CardLine, Offset(0f, y), Offset(size.width, y), 1f)
                }
                if (valid.isEmpty() || loading) return@Canvas
                val path = Path()
                var pen = false
                // Five-minute steps keep the path light without losing the day's shape.
                for (start in minutes.indices step 5) {
                    val step = (start until minOf(start + 5, minutes.size)).mapNotNull { minutes[it] }
                    if (step.isEmpty()) {
                        pen = false
                        continue
                    }
                    val x = size.width * start / minutes.size
                    val y = size.height * (1 - (step.average().toFloat() - lo) / (hi - lo))
                    if (pen) path.lineTo(x, y) else path.moveTo(x, y)
                    pen = true
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (bucket != null) {
                    val readings = bucket.mapNotNull { minutes[it] }
                    val x = size.width * bucket.first / minutes.size
                    val y = size.height * (1 - (readings.average().toFloat() - lo) / (hi - lo))
                    drawLine(Palette.Dim, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    drawLine(Palette.Faint, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    drawCircle(Palette.Card, 6.dp.toPx(), Offset(x, y))
                    drawCircle(color, 4.dp.toPx(), Offset(x, y))
                }
            }
            if (loading) Skeleton(Modifier.size(width = 160.dp, height = 14.dp)) else if (valid.isEmpty()) ChartNote(emptyText)
        }
        Spacer(Modifier.height(6.dp))
        DayAxis()
    }
}

/** Sleep stages across the night, one lane per stage, with the cycles bracketed along the top. */
@Composable
fun Hypnogram(session: SleepSession, modifier: Modifier = Modifier) {
    val order = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val measurer = rememberTextMeasurer()
    val cycleStyle = label(9.sp, Palette.Faint)
    val span = Duration.between(session.start, session.end).seconds.coerceAtLeast(1).toFloat()
    val cycles = session.cycles
    val timeFormat = LocalTimeFormat.current
    var touch by remember(session.id) { mutableStateOf<Float?>(null) }
    val touchedAt = touch?.let { session.start.plusSeconds((span * it).toLong()) }
    val touched = touchedAt?.let { at ->
        session.epochs.lastOrNull { !it.start.isAfter(at) && at.isBefore(it.start.plus(it.duration)) }
    }
    Column(modifier) {
    ScrubReadout(
        value = touched?.let { stageWords(it.stage) },
        detail = touchedAt?.takeIf { touched != null }?.let { timeFormat.time(it) },
        idle = null,
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (cycles.isEmpty()) 96.dp else 116.dp)
            .scrub(session.id) { touch = it }
            .semantics { contentDescription = "Sleep stages across the night, ${cycles.size} cycles" },
    ) {
        fun x(at: Instant) = size.width * Duration.between(session.start, at).seconds / span
        val top = if (cycles.isEmpty()) 0f else 20.dp.toPx()
        for (cycle in cycles) {
            val from = x(cycle.start)
            val to = x(cycle.end)
            val y = 13.dp.toPx()
            val tick = 3.dp.toPx()
            drawLine(Palette.Ghost, Offset(from, y), Offset(to, y), 1.dp.toPx())
            drawLine(Palette.Ghost, Offset(from, y - tick), Offset(from, y + tick), 1.dp.toPx())
            drawLine(Palette.Ghost, Offset(to, y - tick), Offset(to, y + tick), 1.dp.toPx())
            val text = measurer.measure(cycle.number.toString(), cycleStyle)
            if (to - from > text.size.width + 4.dp.toPx()) {
                drawText(text, topLeft = Offset((from + to - text.size.width) / 2, 0f))
            }
        }
        val lane = (size.height - top) / order.size
        for (epoch in session.epochs) {
            val from = x(epoch.start)
            val width = (size.width * epoch.duration.seconds / span).coerceAtLeast(1.5f)
            drawRoundRect(
                Palette.stage(epoch.stage),
                Offset(from, top + order.indexOf(epoch.stage) * lane + lane * 0.15f),
                Size(width, lane * 0.7f),
                CornerRadius(3f, 3f),
            )
        }
        touch?.let {
            val line = size.width * it
            drawLine(Palette.Dim, Offset(line, top), Offset(line, size.height), 1.dp.toPx())
        }
    }
    }
}

private fun stageWords(stage: SleepStage): String = when (stage) {
    SleepStage.AWAKE -> "AWAKE"
    SleepStage.LIGHT -> "LIGHT"
    SleepStage.DEEP -> "SWS (DEEP)"
    SleepStage.REM -> "REM"
}

/**
 * One bar per day. A null value draws the short ghost dash, so a week with gaps still reads
 * as a week. [selected] marks the day the screen is showing.
 */
@Composable
fun WeekBars(
    values: List<Float?>,
    labels: List<String>,
    max: Float,
    colors: List<Color>,
    captions: List<String>,
    selected: Int = values.lastIndex,
    loading: Boolean = false,
) {
    val flat = loading || values.all { it == null }
    var touch by remember(values.size) { mutableStateOf<Float?>(null) }
    val held = touch?.let { (it * values.size).toInt().coerceIn(0, values.lastIndex) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (flat) 44.dp else 150.dp)
            .scrub(values.size) { touch = it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(38.dp)) {
                Text(
                    if (loading) "" else captions[i],
                    style = label(10.sp, if (held != null && held != i) Palette.Ghost else Palette.White),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(14.dp)
                        .height(if (v == null || loading) 3.dp else (100 * (v / max).coerceIn(0.03f, 1f)).dp)
                        .clip(Shapes.bar)
                        .background(
                            when {
                                v == null || loading -> Palette.Ghost
                                held != null && held != i -> colors[i].copy(alpha = 0.3f)
                                else -> colors[i]
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(labels[i], style = label(10.sp, if (i == (held ?: selected)) Palette.White else Palette.Faint))
            }
        }
    }
}

/** Minutes in each heart-rate zone, top zone first. Null draws empty tracks. */
@Composable
fun ZoneBars(zones: ZoneMinutes?) {
    val total = (zones?.total ?: 0).coerceAtLeast(1)
    for (zone in ZoneMinutes.ZONE_COUNT - 1 downTo 0) {
        val minutes = zones?.minutes?.get(zone)
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ZONE $zone", style = label(11.sp), modifier = Modifier.width(60.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundBar)
                    .background(Palette.Track),
            ) {
                if (minutes != null && minutes > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth(minutes.toFloat() / total)
                            .height(10.dp)
                            .clip(RoundBar)
                            .background(Palette.Zones[zone]),
                    )
                }
            }
            if (minutes == null) {
                Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) { AbsentDash() }
            } else {
                Text(hoursMinutes(minutes), style = number(13.sp), textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
        }
    }
}

private val RoundBar = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)

/**
 * Stress through one day. Each point is a bar at its time of day, taller and warmer as the
 * score rises. [dayStart] is local midnight. An empty list draws the grid with [emptyText].
 */
@Composable
fun StressTimeline(points: List<StressPoint>, dayStart: Instant, emptyText: String, modifier: Modifier = Modifier) {
    val twentyFourHour = LocalTimeFormat.current.twentyFourHour
    var touch by remember(dayStart) { mutableStateOf<Float?>(null) }
    val hour = touch?.let { (it * 24).toInt().coerceIn(0, 23) }
    val inHour = hour?.let { h ->
        points.filter { Duration.between(dayStart, it.at).seconds in h * 3600L until (h + 1) * 3600L }
    }.orEmpty()
    Column(modifier) {
        ScrubReadout(
            value = if (inHour.isEmpty()) null else "STRESS ${inHour.map { it.score }.average().roundToInt()}",
            detail = hour?.takeIf { inHour.isNotEmpty() }?.let { minuteLabel(it * 60, twentyFourHour) },
            idle = null,
        )
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .scrub(dayStart) { touch = it },
            ) {
                for (i in 0..2) {
                    val y = size.height * i / 2
                    drawLine(Palette.CardLine, Offset(0f, y), Offset(size.width, y), 1f)
                }
                val slot = size.width / 24f
                val bar = slot * 0.6f
                for (point in points) {
                    val hours = Duration.between(dayStart, point.at).seconds / 3600f
                    if (hours < 0f || hours >= 24f) continue
                    val height = size.height * (point.score / 100.0).toFloat().coerceIn(0.04f, 1f)
                    val dim = hour != null && hours.toInt() != hour
                    drawRoundRect(
                        if (dim) Palette.stress(point.score).copy(alpha = 0.3f) else Palette.stress(point.score),
                        Offset(hours * slot + (slot - bar) / 2, size.height - height),
                        Size(bar, height),
                        CornerRadius(3f, 3f),
                    )
                }
            }
            if (points.isEmpty()) ChartNote(emptyText)
        }
        Spacer(Modifier.height(6.dp))
        DayAxis()
    }
}

/**
 * One day in a month grid: a small ring around the day number.
 * A null [fraction] is a day with no score and draws a plain number.
 */
@Composable
fun MonthCell(
    day: Int,
    fraction: Float?,
    color: Color,
    selected: Boolean = false,
    today: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Space.touch)
            .clip(Shapes.pill)
            .then(if (selected) Modifier.background(Palette.CardRaised) else Modifier)
            .then(if (today) Modifier.border(1.dp, Palette.Faint, Shapes.pill) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction != null) {
            Canvas(Modifier.size(36.dp)) {
                val w = 3.dp.toPx()
                val arc = Size(size.width - w, size.height - w)
                drawArc(Palette.Track, 0f, 360f, false, Offset(w / 2, w / 2), arc, style = Stroke(w))
                drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(w / 2, w / 2), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Text(
            day.toString(),
            style = number(13.sp, if (!enabled) Palette.Ghost else if (fraction == null) Palette.Faint else Palette.White),
        )
    }
}

/**
 * A small trend line. It draws measured points only: nulls break the line, and with fewer
 * than two real points it draws the absent dash and no line at all.
 */
@Composable
fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier = Modifier) {
    val real = values.filterNotNull()
    if (real.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { AbsentDash() }
        return
    }
    Canvas(modifier) {
        val lo = real.min()
        val hi = real.max()
        val range = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = 3.dp.toPx()
        fun point(index: Int, value: Float) = Offset(
            pad + (size.width - pad * 2) * index / (values.size - 1).coerceAtLeast(1),
            pad + (size.height - pad * 2) * (1 - (value - lo) / range),
        )
        val path = Path()
        var pen = false
        var last: Offset? = null
        values.forEachIndexed { index, value ->
            if (value == null) {
                pen = false
                return@forEachIndexed
            }
            val at = point(index, value)
            if (pen) path.lineTo(at.x, at.y) else path.moveTo(at.x, at.y)
            pen = true
            last = at
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        last?.let { drawCircle(color, 3.dp.toPx(), it) }
    }
}

/**
 * A bar that grows right of centre for a positive [fraction] and left for a negative one,
 * from -1 to 1. Null draws the empty track.
 */
@Composable
fun DivergingBar(fraction: Float?, positive: Color, negative: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .height(10.dp)
            .clip(RoundBar)
            .background(Palette.Track),
    ) {
        val middle = size.width / 2
        drawLine(Palette.Ghost, Offset(middle, 0f), Offset(middle, size.height), 1.dp.toPx())
        if (fraction == null || fraction == 0f) return@Canvas
        val length = middle * fraction.coerceIn(-1f, 1f)
        drawRoundRect(
            if (length > 0) positive else negative,
            Offset(if (length > 0) middle else middle + length, 0f),
            Size(kotlin.math.abs(length), size.height),
            CornerRadius(size.height / 2),
        )
    }
}

/** The strap's battery. A null [percent] draws the empty outline. */
@Composable
fun BatteryGlyph(percent: Int?, color: Color) {
    Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
        val body = Size(size.width - 3.dp.toPx(), size.height)
        drawRoundRect(color, size = body, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(
            color,
            Offset(size.width - 2.dp.toPx(), size.height * 0.3f),
            Size(2.dp.toPx(), size.height * 0.4f),
            CornerRadius(1.dp.toPx()),
        )
        if (percent != null) {
            val pad = 2.5.dp.toPx()
            drawRoundRect(
                color,
                Offset(pad, pad),
                Size((body.width - pad * 2) * percent.coerceIn(4, 100) / 100f, body.height - pad * 2),
                CornerRadius(1.dp.toPx()),
            )
        }
    }
}
