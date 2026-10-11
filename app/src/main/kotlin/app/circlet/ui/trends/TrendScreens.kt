package app.circlet.ui.trends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricsRepository
import app.circlet.core.RecordKind
import app.circlet.core.TimeSource
import app.circlet.core.TrendRange
import app.circlet.core.WeeklyRecap
import app.circlet.ui.LocalMessenger
import app.circlet.ui.design.RangeBar
import app.circlet.ui.design.RangeLegend
import app.circlet.ui.design.forKind
import app.circlet.ui.state.today
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.IconButton
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.MonthCell
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.Space
import app.circlet.ui.design.Sparkline
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.WeekBars
import app.circlet.ui.design.body
import app.circlet.ui.design.decimal
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.hoursMinutes
import app.circlet.ui.design.label
import app.circlet.ui.design.narrowDay
import app.circlet.ui.design.number
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.containerViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The month calendar, the per-metric overview, and the entry rows to the recap and records. */
@Composable
fun TrendsScreen(
    onBack: () -> Unit,
    onOpenMetric: (MetricKind) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenRecap: () -> Unit,
    onOpenRecords: () -> Unit,
) {
    val vm = containerViewModel { c, _ -> TrendsViewModel(c.metrics, c.time) }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn {
        DetailHeader("TRENDS", onBack = onBack)
        when (val calendar = state.calendar) {
            Loadable.Loading -> Card(title = monthTitle(state.month)) {
                Skeleton(Modifier.fillMaxWidth().height(240.dp))
            }
            is Loadable.Failed -> ErrorState(calendar.message)
            is Loadable.Ready -> Card(
                title = monthTitle(state.month),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Glyph.ChevronLeft, "Previous month", onClick = { vm.shiftMonth(-1) })
                        IconButton(
                            Glyph.ChevronRight,
                            "Next month",
                            enabled = state.canShowNext,
                            onClick = { vm.shiftMonth(1) },
                        )
                    }
                },
            ) {
                WeekdayHeader()
                CalendarGrid(state.month, state.today, calendar.value, onOpenDay)
            }
        }
        when (val rows = state.rows) {
            Loadable.Loading -> Card(title = "LAST 30 DAYS") {
                Skeleton(Modifier.fillMaxWidth().height(160.dp))
            }
            is Loadable.Failed -> ErrorState(rows.message)
            is Loadable.Ready -> Card(title = "LAST 30 DAYS") {
                KindList(rows.value, onOpenMetric)
            }
        }
        Card(title = "REPORTS") {
            ListRow(
                title = "Weekly recap",
                glyph = Glyph.Document,
                subtitle = "Averages and days for one week",
                onClick = onOpenRecap,
            )
            ListRow(
                title = "Personal records",
                glyph = Glyph.Target,
                subtitle = "Your best day for each measure",
                onClick = onOpenRecords,
            )
        }
    }
}

/** One metric over a week or a month: gapped chart, min, average and max, change, and the day list. */
@Composable
fun MetricTrendScreen(kind: MetricKind, onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> MetricTrendViewModel(c.metrics, c.time) }
    LaunchedEffect(kind) { vm.open(kind) }
    val state by vm.state.collectAsStateWithLifecycle()
    val rangeVm = containerViewModel { c, _ -> TodayRangeViewModel(c.metrics, c.time) }
    val today by rangeVm.day.collectAsStateWithLifecycle()
    ScreenColumn {
        DetailHeader(kind.label().uppercase(), onBack = onBack)
        Segmented(
            options = listOf("Week", "Month"),
            selected = if (state.range == TrendRange.WEEK) 0 else 1,
            onSelect = { index -> vm.select(if (index == 0) TrendRange.WEEK else TrendRange.MONTH) },
        )
        when (val content = state.content) {
            Loadable.Loading -> Skeleton(Modifier.fillMaxWidth().height(220.dp))
            is Loadable.Failed -> ErrorState(content.message)
            is Loadable.Ready -> TrendBody(kind, content.value)
        }
        YourRangeCard(kind, today)
    }
}

/** Today's summary, read only for the YOUR RANGE card. Null until it arrives or when it cannot be read. */
private class TodayRangeViewModel(metrics: MetricsRepository, time: TimeSource) : ViewModel() {
    val day: StateFlow<DaySummary?> = metrics.observeDay(time.today())
        .map<DaySummary, DaySummary?> { it }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Where today's value sits against the user's own normal and the ideal band. Absent when today has no reference for [kind]. */
@Composable
private fun YourRangeCard(kind: MetricKind, day: DaySummary?) {
    val ref = day?.references?.forKind(kind) ?: return
    val value = day.metricValue(kind).value
    Card(title = "YOUR RANGE") {
        RangeBar(value, ref.personal, ref.ideal)
        RangeLegend(
            personal = ref.personal,
            personalDays = ref.personalDays,
            ideal = ref.ideal,
            idealLabel = ref.idealLabel,
            format = { kind.format(it) },
        )
        ref.idealSource?.let { source ->
            Spacer(Modifier.height(8.dp))
            Text(source, style = label(10.sp, Palette.Faint))
        }
    }
}

/** One week at a time, from the current week back. Every field of the recap is shown. */
@Composable
fun WeeklyRecapScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> WeeklyRecapViewModel(c.metrics, c.time) }
    val messenger = LocalMessenger.current
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn {
        DetailHeader("WEEKLY RECAP", subtitle = weekLabel(state.weekStart), onBack = onBack)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.ChevronLeft, "Previous week", onClick = { vm.shiftWeek(-1) })
            Text(
                weekLabel(state.weekStart),
                style = label(14.sp, Palette.White),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(
                Glyph.ChevronRight,
                "Next week",
                enabled = state.canShowNext,
                onClick = { vm.shiftWeek(1) },
            )
        }
        when (val content = state.content) {
            Loadable.Loading -> Skeleton(Modifier.fillMaxWidth().height(260.dp))
            is Loadable.Failed -> ErrorState(content.message)
            is Loadable.Ready -> RecapBody(content.value)
        }
    }
}

/** Personal records grouped by kind, each with its value, unit and date. */
@Composable
fun RecordsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> RecordsViewModel(c.metrics) }
    val messenger = LocalMessenger.current
    LaunchedEffect(vm) { vm.messages.collect { messenger.show(it) } }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenColumn {
        DetailHeader("PERSONAL RECORDS", onBack = onBack)
        when (val content = state.content) {
            Loadable.Loading -> Skeleton(Modifier.fillMaxWidth().height(200.dp))
            is Loadable.Failed -> ErrorState(content.message)
            is Loadable.Ready -> {
                if (content.value.isEmpty()) {
                    EmptyState(
                        glyph = Glyph.Target,
                        heading = "No records yet",
                        text = "Records appear as data builds up. Your best strain, longest sleep and " +
                            "highest heart rate will be listed here once there is enough history.",
                    )
                } else {
                    content.value.forEach { group -> RecordCard(group) }
                }
            }
        }
    }
}

@Composable
private fun KindList(rows: List<KindRow>, onOpenMetric: (MetricKind) -> Unit) {
    if (rows.all { it.latest == null }) {
        EmptyState(
            glyph = Glyph.Trends,
            heading = "No readings yet",
            text = "Trends fill in once the strap has recorded a few days.",
        )
        return
    }
    rows.forEach { row -> MetricStatRow(row, onClick = { onOpenMetric(row.kind) }) }
}

/** A [StatRow] with a sparkline between the name and the value. Names, notes and values follow the stat-row layout. */
@Composable
private fun MetricStatRow(row: KindRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.kind.label().uppercase(), style = label())
            val note = row.average?.let { "30-DAY AVG ${row.kind.withUnit(it)}" } ?: "NO READINGS IN THE LAST 30 DAYS"
            Text(note, style = label(10.sp, Palette.Faint), modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Sparkline(
            values = row.points,
            color = row.kind.sparkColor(),
            modifier = Modifier.width(64.dp).height(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        val latest = row.latest
        if (latest == null) {
            Text("NO READING", style = label(11.sp, Palette.Faint))
        } else {
            Text(row.kind.format(latest) + if (row.kind.unit() == "%") "%" else "", style = number(22.sp, Palette.White))
            val unit = row.kind.unit()
            if (unit.isNotEmpty() && unit != "%" && !row.kind.isDuration()) {
                Text(" $unit", style = label(11.sp, Palette.Faint))
            }
        }
        Icon(Glyph.ChevronRight, Palette.Faint, 18.dp, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        DayOfWeek.values().forEach { day ->
            Text(
                day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = label(11.sp, Palette.Faint),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    today: LocalDate,
    values: Map<LocalDate, Double?>,
    onOpenDay: (LocalDate) -> Unit,
) {
    monthGrid(month).chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CalendarCell(date, today, values, onOpenDay)
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(
    date: LocalDate?,
    today: LocalDate,
    values: Map<LocalDate, Double?>,
    onOpenDay: (LocalDate) -> Unit,
) {
    // Blanks, future days and days without a row stay empty rather than showing zero.
    if (date == null) {
        Spacer(Modifier.size(Space.touch))
        return
    }
    // Future days and days with nothing recorded show their number, dimmed, so the grid reads as a calendar.
    if (date.isAfter(today) || !values.containsKey(date)) {
        MonthCell(day = date.dayOfMonth, fraction = null, color = Palette.Faint, today = date == today, enabled = false, onClick = {})
        return
    }
    val score = values[date]
    MonthCell(
        day = date.dayOfMonth,
        fraction = score?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
        color = Palette.recovery(score?.roundToInt()),
        today = date == today,
        onClick = { onOpenDay(date) },
    )
}

@Composable
private fun TrendBody(kind: MetricKind, trend: MetricTrend) {
    val dates = trend.window.dates()
    val values = trend.points.map { it.value?.toFloat() }
    val isWeek = trend.range == TrendRange.WEEK
    Card(title = windowLabel(trend.window)) {
        WeekBars(
            values = values,
            labels = if (isWeek) {
                dates.map { it.narrowDay() }
            } else {
                dates.mapIndexed { index, date -> if (index % 5 == 0) date.dayOfMonth.toString() else "" }
            },
            max = maxOf(trend.stats.max ?: 0.0, 1.0).toFloat(),
            colors = trend.points.map { point ->
                if (kind == MetricKind.RECOVERY) Palette.recovery(point.value?.roundToInt()) else Palette.Teal
            },
            captions = if (isWeek) {
                trend.points.map { point -> point.value?.let { kind.format(it) } ?: "" }
            } else {
                values.map { "" }
            },
        )
    }
    Card(title = "SUMMARY") {
        Row(Modifier.fillMaxWidth()) {
            Figure(
                value = trend.stats.min?.let { kind.withUnit(it) },
                caption = "MIN",
                modifier = Modifier.weight(1f),
            )
            Figure(
                value = trend.stats.average?.let { kind.withUnit(it) },
                caption = "AVERAGE",
                modifier = Modifier.weight(1f),
            )
            Figure(
                value = trend.stats.max?.let { kind.withUnit(it) },
                caption = "MAX",
                alignEnd = true,
                modifier = Modifier.weight(1f),
            )
        }
        ChangeLine(kind, trend)
    }
    val withValues = trend.points.filter { it.value != null }.asReversed()
    Card(title = "DAYS") {
        if (withValues.isEmpty()) {
            Text("No readings in this period.", style = body(14.sp))
        }
        withValues.forEach { point ->
            ListRow(
                title = dateTitle(point.date, trend.window.to),
                value = point.value?.let { kind.withUnit(it) },
            )
        }
    }
}

@Composable
private fun ChangeLine(kind: MetricKind, trend: MetricTrend) {
    val span = "${trend.range.length()} days"
    val change = trend.change
    if (change == null) {
        Text(
            "Not enough readings in the previous $span to compare.",
            style = body(14.sp),
        )
        return
    }
    val percent = change.percent
    val text = if (percent != null) {
        "${signed(percent)}% against the previous $span"
    } else {
        val delta = listOf(signed(change.delta), kind.unit()).filter { it.isNotEmpty() }.joinToString(" ")
        "$delta against the previous $span"
    }
    Text(text, style = body(14.sp, Palette.White))
}

@Composable
private fun RecapBody(recap: WeeklyRecap) {
    val hasAny = recap.averageRecovery.value != null ||
        recap.averageStrain.value != null ||
        recap.averageSleepPerformance.value != null ||
        recap.averageHrvMs.value != null ||
        recap.sleepDebt.value != null
    if (!hasAny) {
        EmptyState(
            glyph = Glyph.Calendar,
            heading = "Not enough data for a recap",
            text = "A recap appears once the strap has recorded a few days this week. " +
                "Keep wearing it and check back.",
        )
        return
    }
    Card(title = "AVERAGES") {
        StatRow(
            name = "Recovery",
            value = recap.averageRecovery.value?.let { "${it.decimal(0)}%" },
            confidence = recap.averageRecovery.confidence,
            absent = recap.averageRecovery.absentReason,
        )
        StatRow(
            name = "Strain",
            value = recap.averageStrain.value?.decimal(1),
            confidence = recap.averageStrain.confidence,
            absent = recap.averageStrain.absentReason,
        )
        StatRow(
            name = "Sleep performance",
            value = recap.averageSleepPerformance.value?.let { "${it.decimal(0)}%" },
            confidence = recap.averageSleepPerformance.confidence,
            absent = recap.averageSleepPerformance.absentReason,
        )
        StatRow(
            name = "HRV",
            value = recap.averageHrvMs.value?.decimal(0),
            unit = "ms",
            confidence = recap.averageHrvMs.confidence,
            absent = recap.averageHrvMs.absentReason,
        )
        StatRow(
            name = "Sleep debt",
            value = recap.sleepDebt.value?.hoursMinutes(),
            confidence = recap.sleepDebt.confidence,
            absent = recap.sleepDebt.absentReason,
        )
    }
    Card(title = "DAYS") {
        StatRow(
            name = "Days with readings",
            value = recap.days.count { day -> MetricKind.values().any { day.metricValue(it).present } }.toString(),
        )
        if (recap.days.isNotEmpty()) {
            WeekBars(
                values = recap.days.map { it.recovery.score.value?.toFloat() },
                labels = recap.days.map { it.date.narrowDay() },
                max = 100f,
                colors = recap.days.map { Palette.recovery(it.recovery.score.value) },
                captions = recap.days.map { day -> day.recovery.score.value?.let { "${it}%" } ?: "" },
            )
        }
    }
}

@Composable
private fun RecordCard(group: RecordGroup) {
    Card(title = group.kind.label().uppercase()) {
        group.records.forEach { record ->
            ListRow(
                title = group.kind.withUnit(record.value),
                subtitle = record.date.shortDate(),
            )
        }
    }
}

/** Joins a value and its unit. Percent sits against the number; durations already read as hours and minutes. */
private fun joinUnit(value: String, unit: String, durationValue: Boolean = false): String = when {
    durationValue || unit.isEmpty() -> value
    unit == "%" -> value + unit
    else -> "$value $unit"
}

private fun MetricKind.withUnit(value: Double): String = joinUnit(format(value), unit(), isDuration())

private fun RecordKind.withUnit(value: Double): String = joinUnit(format(value), unit(), isDuration())

private fun signed(value: Double): String = (if (value > 0) "+" else "") + value.decimal(1)

private fun monthTitle(month: YearMonth): String =
    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())).uppercase()

private fun weekLabel(weekStart: LocalDate): String =
    "${weekStart.shortDate("d MMM")} to ${weekStart.plusDays(6).shortDate("d MMM")}"

private fun windowLabel(window: DayWindow): String =
    "${window.from.shortDate("d MMM")} to ${window.to.shortDate("d MMM")}".uppercase()

private fun LocalDate.shortDate(pattern: String = "d MMM yyyy"): String =
    format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
