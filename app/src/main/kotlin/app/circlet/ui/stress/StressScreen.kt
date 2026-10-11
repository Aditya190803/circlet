package app.circlet.ui.stress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.AbsentReason
import app.circlet.core.Metric
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.day.dayLabels
import app.circlet.ui.design.Card
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.HeroDial
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.StressTimeline
import app.circlet.ui.design.WeekBars
import app.circlet.ui.design.body
import app.circlet.ui.design.words
import app.circlet.ui.state.containerViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day stress: the score, minutes per band, the timeline, and the last seven days. */
@Composable
fun StressScreen(date: LocalDate, onBack: () -> Unit) {
    val vm = containerViewModel { container, _ ->
        StressViewModel(container.metrics, container.time, date)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(date) { vm.select(date) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "STRESS",
            subtitle = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).uppercase(),
            onBack = onBack,
        )
        ScreenColumn {
            when (val s = state) {
                StressUiState.Loading -> {
                    Card(title = "STRESS") { Skeleton(Modifier.fillMaxWidth().height(14.dp)) }
                    Card(title = "TIME IN BAND") { Skeleton(Modifier.fillMaxWidth().height(14.dp)) }
                }
                is StressUiState.Ready -> {
                    val score = s.score
                    val value = score.value
                    val bands = s.bands
                    val bandReason = if (bands == null) absentReason(score) else null

                    Card(title = "STRESS") {
                        HeroDial(
                            caption = "DAY STRESS",
                            glyph = Glyph.Stress,
                            color = if (value == null) Palette.Faint else Palette.stress(value),
                            value = value?.toInt()?.toString(),
                            unit = "/ 100",
                            fraction = value?.let { (it / 100.0).toFloat() },
                            message = if (value == null) {
                                absentReason(score).words()
                            } else {
                                "Higher means more stress on your body today."
                            },
                            confidence = if (value == null) null else score.confidence,
                        )
                    }

                    Card(title = "TIME IN BAND") {
                        StatRow(
                            name = "LOW",
                            value = bands?.low?.toString(),
                            unit = "MIN",
                            color = Palette.StressLow,
                            absent = bandReason,
                        )
                        StatRow(
                            name = "MEDIUM",
                            value = bands?.medium?.toString(),
                            unit = "MIN",
                            color = Palette.StressMid,
                            absent = bandReason,
                        )
                        StatRow(
                            name = "HIGH",
                            value = bands?.high?.toString(),
                            unit = "MIN",
                            color = Palette.StressHigh,
                            absent = bandReason,
                        )
                        if (bands != null) {
                            Text(
                                "Counted from the timeline samples. Gaps longer than 10 minutes are not counted.",
                                style = body(13.sp, Palette.Faint),
                            )
                        }
                    }

                    Card(title = "TIMELINE") {
                        StressTimeline(
                            points = s.timeline,
                            dayStart = s.dayStart,
                            emptyText = if (value == null) absentReason(score).words() else "No samples recorded for this day.",
                        )
                    }

                    Card(title = "THIS WEEK") {
                        WeekBars(
                            values = s.week.map { day -> day.stress.score.value?.toFloat() },
                            labels = s.week.dayLabels(),
                            max = 100f,
                            colors = s.week.map { day ->
                                day.stress.score.value?.let { Palette.stress(it) } ?: Palette.Faint
                            },
                            captions = s.week.map { day ->
                                day.stress.score.value?.let { it.toInt().toString() } ?: "-"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Why a day has no stress. A score that is missing without a reason is treated as not enough data. */
private fun absentReason(score: Metric<Double>): AbsentReason =
    score.absentReason ?: AbsentReason.NOT_ENOUGH_DATA
