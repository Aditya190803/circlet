package app.circlet.ui.journal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.CorrelationMetric
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.DivergingBar
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.Palette
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.design.words
import app.circlet.ui.state.containerViewModel

/** Which tags move recovery, HRV and sleep, from the days logged so far. */
@Composable
fun JournalInsightsScreen(onBack: () -> Unit) {
    val vm = containerViewModel { container, _ -> JournalInsightsViewModel(container.journal) }
    val metric by vm.metric.collectAsState()
    val state by vm.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        DetailHeader("WHAT MOVES YOUR NUMBERS", onBack = onBack)
        ScreenColumn {
            val metrics = CorrelationMetric.entries
            Segmented(
                options = metrics.map { it.label() },
                selected = metrics.indexOf(metric),
                onSelect = { vm.select(metrics[it]) },
            )
            when (val current = state) {
                InsightsState.Loading -> {
                    Skeleton(Modifier.fillMaxWidth().height(96.dp))
                    Skeleton(Modifier.fillMaxWidth().height(96.dp))
                }
                is InsightsState.Failed -> ErrorState(current.message, onRetry = vm::load)
                is InsightsState.Ready -> InsightsBody(current, metric)
            }
        }
    }
}

@Composable
private fun InsightsBody(ready: InsightsState.Ready, metric: CorrelationMetric) {
    val split = splitCorrelations(ready.correlations, metric, ready.tags)
    val names = ready.tags.associate { it.id to it.name }

    if (split.measured.isEmpty()) {
        EmptyState(
            Glyph.Trends,
            "NOT ENOUGH DAYS YET",
            "Keep logging for a couple of weeks. A tag shows its effect on ${metric.label().lowercase()} once it has " +
                "at least $MIN_DAYS_PER_SIDE days with it and $MIN_DAYS_PER_SIDE days without it.",
        )
    } else {
        Card("EFFECT ON ${metric.label()}") {
            split.measured.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(16.dp))
                val correlation = row.correlation
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (names[correlation.tagId] ?: correlation.tagId).uppercase(),
                        style = label(13.sp, Palette.White),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${signedEffect(row.effect)} ${metric.unit()}",
                        style = number(15.sp, Palette.White),
                    )
                }
                Spacer(Modifier.height(8.dp))
                DivergingBar(
                    fraction = barFraction(row.effect, split.measured),
                    positive = Palette.Teal,
                    negative = Palette.Danger,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${correlation.daysWith} days with, ${correlation.daysWithout} without. " +
                        correlation.effect.confidence.words() + ".",
                    style = body(12.sp, Palette.Faint),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Bars are scaled to the largest effect shown. A tag's effect is the average on days it was logged " +
                    "minus the average on days it was not.",
                style = body(12.sp, Palette.Faint),
            )
        }
    }

    if (split.notEnough.isNotEmpty()) {
        Card("NOT ENOUGH DATA YET") {
            Text(
                "These tags need at least $MIN_DAYS_PER_SIDE days with and $MIN_DAYS_PER_SIDE days without before an effect is shown.",
                style = body(13.sp, Palette.Faint),
            )
            Spacer(Modifier.height(4.dp))
            split.notEnough.forEach { row ->
                ListRow(
                    title = names[row.tagId] ?: row.tagId,
                    subtitle = "${row.daysWith} days with, ${row.daysWithout} without",
                )
            }
        }
    }
}
