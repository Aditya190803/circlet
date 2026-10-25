package app.circlet.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The (i) in a card's title row. It opens a sheet that says what the card shows and how
 * Circlet works it out. Kept to the title row's height so cards with and without it line up.
 */
@Composable
fun InfoButton(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .wrapContentHeight(unbounded = true)
                .offset(x = 8.dp)
                .size(36.dp)
                .clip(Shapes.pill)
                .clickable(role = Role.Button, onClickLabel = "About $title") { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Info, Palette.Faint, 20.dp) }
    }
    if (open) {
        Sheet(title, onDismiss = { open = false }) {
            Text(text, style = body(15.sp, Palette.Dim))
        }
    }
}

/** One night's window for [SleepWindows]: the local date it ended on, and when it began and ended. */
data class SleepWindow(val date: LocalDate, val start: Instant, val end: Instant)

/**
 * Minutes from noon of the day before [date] to [at], so an evening bedtime and the next
 * morning's wake sit on one rising scale.
 */
internal fun minutesFromNoonBefore(date: LocalDate, at: Instant, zone: ZoneId): Long =
    java.time.Duration.between(date.minusDays(1).atTime(LocalTime.NOON).atZone(zone).toInstant(), at).toMinutes()

/**
 * Bed and wake times across a week, one bar per night on a clock that runs down the chart
 * from evening to morning, with dashed lines at the average bedtime and wake time.
 * [nights] holds one entry per day of [labels]; a null draws no bar for that day.
 */
@Composable
fun SleepWindows(nights: List<SleepWindow?>, labels: List<String>, selected: Int = nights.lastIndex) {
    val format = LocalTimeFormat.current
    val spans = nights.map { night ->
        night?.let { minutesFromNoonBefore(it.date, it.start, format.zone) to minutesFromNoonBefore(it.date, it.end, format.zone) }
    }
    val real = spans.filterNotNull()
    if (real.isEmpty()) {
        EmptyState(Glyph.Moon, "NO NIGHTS THIS WEEK", "Bed and wake times draw here once nights are recorded.")
        return
    }
    val step = 180L
    val low = floor((real.minOf { it.first } - 30).toDouble() / step).toLong() * step
    val high = ceil((real.maxOf { it.second } + 30).toDouble() / step).toLong() * step
    val span = (high - low).toFloat()
    val bed = real.map { it.first }.average()
    val wake = real.map { it.second }.average()
    val plot = 180.dp
    val gutter = 52.dp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(gutter).height(plot)) {
                var tick = low
                while (tick <= high) {
                    val y = plot * ((tick - low) / span)
                    Text(
                        hourLabel(((tick + 12 * 60) % 1440).toInt(), format.twentyFourHour),
                        style = label(10.sp, Palette.Faint),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset(y = y - 7.dp),
                    )
                    tick += step
                }
            }
            Canvas(Modifier.weight(1f).height(plot)) {
                var tick = low
                while (tick <= high) {
                    val y = size.height * ((tick - low) / span)
                    drawLine(Palette.Track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    tick += step
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                for (mark in listOf(bed, wake)) {
                    val y = size.height * ((mark - low) / span).toFloat()
                    drawLine(Palette.Dim, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                }
                val slot = size.width / nights.size
                val bar = minOf(16.dp.toPx(), slot * 0.5f)
                spans.forEachIndexed { i, night ->
                    if (night == null) return@forEachIndexed
                    val top = size.height * ((night.first - low) / span)
                    val bottom = size.height * ((night.second - low) / span)
                    drawRoundRect(
                        if (i == selected) Palette.Sleep else Palette.Sleep.copy(alpha = 0.55f),
                        Offset(slot * i + (slot - bar) / 2, top),
                        Size(bar, (bottom - top).coerceAtLeast(2.dp.toPx())),
                        CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(start = gutter)) {
            labels.forEachIndexed { i, day ->
                Text(
                    day,
                    style = body(12.sp, if (i == selected) Palette.White else Palette.Faint).copy(lineHeight = 15.sp),
                    textAlign = TextAlign.Center,
                    softWrap = false,
                    modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(width = 22.dp, height = 2.dp)) {
                drawLine(
                    Palette.Dim, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
                )
            }
            Text(
                "Average bedtime ${hourMinute(bed, format.twentyFourHour)} · wake ${hourMinute(wake, format.twentyFourHour)}",
                style = body(13.sp, Palette.Dim),
            )
        }
    }
}

/** "10 PM" or "22:00" for a minute of the day on the hour. */
private fun hourLabel(minuteOfDay: Int, twentyFourHour: Boolean): String {
    val hour = minuteOfDay / 60
    if (twentyFourHour) return "%02d:00".format(hour)
    val h = if (hour % 12 == 0) 12 else hour % 12
    return "$h ${if (hour < 12) "AM" else "PM"}"
}

/** A clock time for minutes counted from the noon before, such as an average bedtime. */
private fun hourMinute(minutesFromNoon: Double, twentyFourHour: Boolean): String =
    minuteLabel(((Math.round(minutesFromNoon) + 12 * 60) % 1440).toInt(), twentyFourHour)
