package app.circlet.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.AbsentReason
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.celsius
import app.circlet.core.SleepSession
import app.circlet.ui.design.AbsentDash
import app.circlet.ui.design.StatCell
import app.circlet.ui.design.StageBreakdown
import app.circlet.ui.design.Space
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.IconButton
import app.circlet.ui.design.Card
import app.circlet.ui.design.DivergingBar
import app.circlet.ui.design.Divider
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.GhostRow
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.HeroDial
import app.circlet.ui.design.Hypnogram
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.MetricRow
import app.circlet.ui.design.Palette
import app.circlet.ui.design.RangeBar
import app.circlet.ui.design.RangeLegend
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.TileRow
import app.circlet.ui.design.WeekBars
import app.circlet.ui.design.ZoneBars
import app.circlet.ui.design.body
import app.circlet.ui.design.dateTitle
import app.circlet.ui.design.decimal
import app.circlet.ui.design.forKind
import app.circlet.ui.design.glyph
import app.circlet.ui.design.grouped
import app.circlet.ui.design.hoursMinutes
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.design.signedDeviation
import app.circlet.ui.design.words
import app.circlet.ui.design.ProgressSteps
import app.circlet.ui.state.BASELINE_NIGHTS
import app.circlet.ui.state.DayUiState
import app.circlet.ui.state.Loadable
import kotlin.math.abs

/**
 * Header plus the three states every detail screen shares.
 * [content] runs for a ready day; loading and failure are drawn here.
 */
@Composable
private fun DetailFrame(
    title: String,
    state: DayUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    loadingCaption: String,
    action: (@Composable (DaySummary) -> Unit)? = null,
    content: @Composable ColumnScope.(DaySummary) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val ready = (state.day as? Loadable.Ready)?.value
        DetailHeader(
            title, dateTitle(state.date, state.today), onBack,
            action = if (ready != null && action != null) ({ action(ready) }) else null,
        )
        ScreenColumn {
            when (val day = state.day) {
                Loadable.Loading -> {
                    HeroDial(loadingCaption, Glyph.Pulse, Palette.Track, null, "", null, "", loading = true)
                    repeat(2) {
                        Card {
                            Skeleton(Modifier.size(width = 140.dp, height = 14.dp))
                            Spacer(Modifier.height(18.dp))
                            Skeleton(Modifier.fillMaxWidth().height(14.dp))
                            Spacer(Modifier.height(12.dp))
                            Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
                        }
                    }
                }
                is Loadable.Failed -> ErrorState(day.message, onRetry)
                is Loadable.Ready -> content(day.value)
            }
        }
    }
}

/** A range bar and its legend for one reference. Draws nothing when the day has no reference for the metric. */
@Composable
private fun RangeBlock(ref: MetricReference?, value: Double?, accent: Color = Palette.Teal, format: (Double) -> String) {
    if (ref == null) return
    Spacer(Modifier.height(4.dp))
    RangeBar(value, ref.personal, ref.ideal, accent = accent)
    RangeLegend(ref.personal, ref.personalDays, ref.ideal, format, accent = accent)
}

/** "Avg of last 12 days" over the days before the selected one, or null with fewer than two. */
private fun <T : Any> DayUiState.typical(pick: (DaySummary) -> T?, format: (Double) -> String, toDouble: (T) -> Double): String? {
    val values = earlier.mapNotNull { day -> pick(day)?.let(toDouble) }
    return average(values)?.let { "avg of last ${values.size} days ${format(it)}" }
}

@Composable
fun SleepScreen(state: DayUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    val editor = remember(state.date) { SleepEditor() }
    DetailFrame(
        "Sleep", state, onBack, onRetry, "Sleep performance",
        action = { day ->
            if (day.sleep.value != null) IconButton(Glyph.Edit, "Edit sleep", Palette.Dim) { editor.editing = true }
        },
    ) { day ->
        val format = LocalTimeFormat.current
        val sleep = day.sleep.value
        val performance = day.sleepPerformance.percent
        val percent = performance.value
        HeroDial(
            "Sleep performance", Glyph.Moon, Palette.Sleep, percent?.toString(), "%", percent?.div(100f),
            when {
                percent == null && (day.sleep.absentReason ?: performance.absentReason) == AbsentReason.NO_SLEEP ->
                    "No sleep found for this day. Wear the strap overnight and it shows here after the morning sync."
                percent == null ->
                    "${(performance.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Sleep shows here after the morning sync."
                percent >= 85 -> "You met your sleep need."
                percent >= 70 -> "You got most of the sleep you needed."
                else -> "You fell well short of your sleep need."
            },
        )
        if (sleep != null) {
            NightCard(sleep)
        } else {
            Card("Sleep stages") {
                EmptyState(Glyph.Moon, "No sleep recorded", "Stages draw here after a night with the strap on, or add the night yourself.")
                Spacer(Modifier.height(Space.sm))
                SecondaryButton("Add sleep", glyph = Glyph.Plus) { editor.editing = true }
                RestoreSleepButton(state.date)
            }
        }
        Card("Hours vs. needed") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(
                    sleep?.timeAsleep?.hoursMinutes(), "Asleep",
                    absent = day.sleep.absentReason, modifier = Modifier.weight(1f),
                )
                Figure(
                    day.sleepNeed.duration.value?.hoursMinutes(), if (sleep != null) "Needed" else "Needed tonight", Palette.Sleep,
                    absent = day.sleepNeed.duration.absentReason, alignEnd = true,
                    modifier = Modifier.weight(1f),
                )
            }
            RangeBlock(day.references.forKind(MetricKind.SLEEP_DURATION), sleep?.timeAsleep?.toMinutes()?.toDouble(), Palette.Sleep) {
                hoursMinutes(it.toInt())
            }
            val debt = day.sleepDebt.shortfall.value?.takeIf { !it.isZero }
            val bedtime = day.sleepNeed.recommendedBedtime?.takeIf { state.isToday }
            if (debt != null || bedtime != null) {
                Divider()
                if (bedtime != null) StatRow("Bed by", format.time(bedtime), note = "To meet tonight's need")
                if (debt != null) StatRow("Sleep debt", debt.hoursMinutes())
            }
        }
        if (sleep != null) {
            Card("Sleep statistics") {
                Row(Modifier.fillMaxWidth()) {
                    StatCell("Time in bed", sleep.timeInBed.hoursMinutes(), modifier = Modifier.weight(1f))
                    StatCell(
                        "Efficiency", sleep.efficiency.value?.toString(), "%",
                        absent = sleep.efficiency.absentReason, modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    StatCell("Fell asleep in", sleep.latency.toMinutes().toString(), "min", modifier = Modifier.weight(1f))
                    val awake = sleep.wakeEvents.sumOf { it.duration.toMinutes() }
                    StatCell(
                        "Wake events", sleep.wakeEvents.size.toString(), if (awake > 0) "· $awake min" else "",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    StatCell(
                        "Consistency", day.sleepConsistency.index.value?.decimal(0), "%",
                        absent = day.sleepConsistency.index.absentReason, modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        "Respiratory rate", day.heart.respiratoryRate.breathsPerMinute.value?.decimal(), "rpm",
                        absent = day.heart.respiratoryRate.breathsPerMinute.absentReason, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        NapsCard(day, state.date)
        Card("Last 7 days") {
            val week = state.week
            WeekBars(
                week.map { it.sleep.value?.timeAsleep?.toMinutes()?.toFloat() }, week.dayLabels(), 600f,
                week.map { Palette.Sleep }, week.map { it.sleep.value?.timeAsleep?.hoursMinutes() ?: "" },
            )
        }
        SleepEditSheets(editor, day, state.date)
    }
}

/** The night itself: the scrubbable hypnogram over how its time split across the stages. */
@Composable
private fun NightCard(sleep: SleepSession) {
    Card("Sleep stages") {
        Hypnogram(sleep)
        Spacer(Modifier.height(18.dp))
        StageBreakdown(sleep)
    }
}

@Composable
fun RecoveryScreen(state: DayUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    DetailFrame("Recovery", state, onBack, onRetry, "Recovery") { day ->
        val recovery = day.recovery
        val score = recovery.score.value
        val calibrating = state.recoveryCalibrating
        HeroDial(
            "Recovery", Glyph.Pulse, Palette.recovery(score), score?.toString(), "%", score?.div(100f),
            when {
                score == null && calibrating ->
                    "Circlet is still learning your baseline. Recovery is scored from your heart rate variability, resting heart rate and sleep once it has a few nights to compare against."
                score == null ->
                    "${(recovery.score.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Recovery is scored each morning from your heart rate variability, resting heart rate and sleep."
                score >= 67 -> "You are well recovered. Your body is ready to take on strain today."
                score >= 34 -> "You are adequately recovered and can handle a moderate amount of strain."
                else -> "Your body is run down. Prioritise rest and keep strain light today."
            },
        )
        if (calibrating) {
            Card("Baseline") {
                StatRow(
                    "Nights", "${state.baselineNights} of $BASELINE_NIGHTS",
                    note = if (state.baselineNightsLeft == 1) "1 more night" else "${state.baselineNightsLeft} more nights",
                )
                ProgressSteps(state.baselineNights, BASELINE_NIGHTS, Palette.Teal)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Each night with the strap on adds to your baseline. Until it is ready the score is provisional: it is worked out from this night and typical adult values, and it sharpens as your own normal takes shape.",
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
        DriversCard(recovery.drivers)
        Card("Recovery statistics") {
            MetricRow(
                "HRV", day.heart.hrv.rmssdMs, "ms",
                note = state.typical({ it.heart.hrv.rmssdMs.value }, { it.decimal(0) }) { it },
            ) { it.decimal(0) }
            RangeBlock(day.references.forKind(MetricKind.HRV), day.heart.hrv.rmssdMs.value) { it.decimal(0) }
            Divider()
            MetricRow(
                "Resting heart rate", day.heart.resting.bpm, "bpm",
                note = state.typical({ it.heart.resting.bpm.value }, { it.decimal(0) }) { it.toDouble() },
            ) { it.toString() }
            RangeBlock(day.references.forKind(MetricKind.RESTING_HEART_RATE), day.heart.resting.bpm.value?.toDouble()) { it.decimal(0) }
            Divider()
            MetricRow(
                "Respiratory rate", day.heart.respiratoryRate.breathsPerMinute, "rpm",
                note = state.typical({ it.heart.respiratoryRate.breathsPerMinute.value }, { it.decimal() }) { it },
            ) { it.decimal() }
            RangeBlock(
                day.references.forKind(MetricKind.RESPIRATORY_RATE),
                day.heart.respiratoryRate.breathsPerMinute.value?.toDouble(),
            ) { it.decimal() }
            Divider()
            MetricRow("Sleep performance", day.sleepPerformance.percent, "%") { it.toString() }
        }
        Card("HRV in detail") {
            MetricRow("SDNN", day.heart.hrv.sdnnMs, "ms") { it.decimal(0) }
            Divider()
            StatRow(
                "LF / HF", day.heart.hrv.frequency?.lfHfRatio?.decimal(2),
                absent = if (day.heart.hrv.frequency == null) day.heart.hrv.rmssdMs.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
            )
        }
        SkinTemperatureCard(day.skinTemperature)
        Card("Last 7 days") {
            val week = state.week
            WeekBars(
                week.map { it.recovery.score.value?.toFloat() }, week.dayLabels(), 100f,
                week.map { Palette.recovery(it.recovery.score.value) }, week.map { it.recovery.score.value?.let { v -> "$v%" } ?: "" },
            )
        }
    }
}

/**
 * Skin temperature for the night. Degrees appear when they are known: WHOOP's own figure on an
 * imported night, or the reading a WHOOP 5.0 reports. On WHOOP 4.0 the night is shown as a deviation
 * in standard deviations of the user's own nights, because that strap reports uncalibrated counts.
 */
@Composable
private fun SkinTemperatureCard(skin: SkinTemperature?) {
    Card("Skin temperature") {
        if (skin == null) {
            Text(
                "No skin temperature for this night yet. It appears once the strap has recorded enough of a night's sleep.",
                style = body(13.sp, Palette.Faint),
            )
            return@Card
        }
        val celsius = skin.celsius
        if (celsius != null) {
            StatRow("Skin temperature", celsius.decimal(1), "°C")
            Spacer(Modifier.height(6.dp))
            Text(
                if (skin.importedCelsius != null) {
                    "As scored by WHOOP, from your imported history."
                } else {
                    "Measured by your strap over the warm part of the night, when the wrist was covered and settled."
                },
                style = body(12.sp, Palette.Faint),
            )
            if (skin.importedCelsius != null) return@Card
            Spacer(Modifier.height(12.dp))
        }
        val nights = "${skin.baselineNights} prior ${if (skin.baselineNights == 1) "night" else "nights"}"
        if (skin.flag == SkinTempFlag.NOT_ENOUGH_BASELINE) {
            Text(
                "Building your baseline: $nights so far. The comparison with your usual nights starts after seven.",
                style = body(13.sp, Palette.Faint),
            )
            if (celsius == null) Gen4Note()
            return@Card
        }
        val deviation = skin.deviation.value
        StatRow(
            "Versus your usual", deviation?.signedDeviation(), "SD",
            color = if (skin.flag == SkinTempFlag.ELEVATED) Palette.Warn else Palette.White,
            absent = if (deviation == null) skin.deviation.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                deviation == null -> "No comparable reading for this night."
                skin.flag == SkinTempFlag.ELEVATED -> "Above your usual nights for two nights running, compared with $nights."
                else -> "Close to your usual nights, compared with $nights."
            },
            style = body(12.sp, Palette.Faint),
        )
        if (celsius == null) Gen4Note()
    }
}

@Composable
private fun Gen4Note() {
    Spacer(Modifier.height(6.dp))
    Text(
        "WHOOP 4.0 reports raw sensor counts, which WHOOP turns into degrees only on its servers. " +
            "Circlet will not guess a temperature, so it shows how tonight compares with your own nights.",
        style = body(12.sp, Palette.Faint),
    )
}

private fun RecoveryDriverKind.label() = when (this) {
    RecoveryDriverKind.HRV -> "HRV"
    RecoveryDriverKind.RESTING_HEART_RATE -> "Resting heart rate"
    RecoveryDriverKind.SLEEP -> "Sleep"
    RecoveryDriverKind.RESPIRATORY_RATE -> "Respiratory rate"
}

/** The inputs behind the score, largest effect first, each as points added or taken. */
@Composable
fun DriversCard(drivers: List<RecoveryDriver>) {
    Card("What moved your recovery") {
        val scale = drivers.mapNotNull { it.contribution }.maxOfOrNull { abs(it) }?.coerceAtLeast(5.0) ?: 5.0
        drivers.sortedByDescending { abs(it.contribution ?: -1.0) }.forEachIndexed { i, driver ->
            if (i > 0) Divider()
            val points = driver.contribution
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(driver.kind.label(), style = label())
                        driver.absentReason?.let { Text(it.words(), style = body(12.sp, Palette.Faint), modifier = Modifier.padding(top = 2.dp)) }
                    }
                    if (points == null) {
                        AbsentDash()
                    } else {
                        // U+2212 is the typographic minus, the same width as the plus beside it.
                        val sign = if (points >= 0) "+" else "−"
                        Text(
                            sign + abs(points).decimal(),
                            style = number(18.sp, if (points >= 0) Palette.RecoveryHigh else Palette.Danger),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                DivergingBar(points?.div(scale)?.toFloat(), Palette.RecoveryHigh, Palette.Danger, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Points each input added to or took from the score.", style = body(12.sp, Palette.Faint))
    }
}

@Composable
fun StrainScreen(
    state: DayUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenActivity: (String) -> Unit = {},
) {
    DetailFrame("Strain", state, onBack, onRetry, "Day strain") { day ->
        val format = LocalTimeFormat.current
        val strain = day.strain.score.value
        val target = day.targetStrain.score
        HeroDial(
            "Day strain", Glyph.Bolt, Palette.Strain, strain?.decimal(), "", strain?.div(21.0)?.toFloat(),
            when {
                strain == null ->
                    "${(day.strain.score.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Strain runs from 0 to 21 and climbs as your heart works harder through the day."
                strain >= 18 -> "All out. Your body took on a very heavy cardiovascular load."
                strain >= 14 -> "High strain. This level of load builds fitness."
                strain >= 10 -> "Moderate strain. Enough to maintain your fitness."
                else -> "Light strain. A restorative day for your body."
            },
            marker = target.value?.div(21.0)?.toFloat(),
        )
        Card(if (state.isToday) "Today's target" else "Target") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(strain?.decimal(), "Day strain", absent = day.strain.score.absentReason, modifier = Modifier.weight(1f))
                Figure(
                    target.value?.decimal(), "Target", Palette.Strain,
                    absent = target.absentReason, alignEnd = true, modifier = Modifier.weight(1f),
                )
            }
            RangeBlock(day.references.forKind(MetricKind.STRAIN), strain, Palette.Strain) { it.decimal() }
            val goal = target.value
            if (strain != null && goal != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (strain >= goal) "Target reached. The notch on the dial marks it."
                    else "${(goal - strain).decimal()} to go. The notch on the dial marks the target your recovery suggests.",
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
        Card("Strain statistics") {
            MetricRow("Average heart rate", day.heart.averageBpm, "bpm") { it.toString() }
            Divider()
            MetricRow("Max heart rate", day.heart.maxBpm, "bpm") { it.toString() }
            Divider()
            MetricRow("Calories", day.calories, "kcal") { it.grouped() }
            Divider()
            MetricRow("Steps", day.steps.count, note = "Counted by this phone") { it.grouped() }
        }
        Card("Fitness") {
            MetricRow("VO2 max", day.vo2Max.mlPerKgMin, "ml/kg/min") { it.decimal() }
            RangeBlock(day.references.forKind(MetricKind.VO2_MAX), day.vo2Max.mlPerKgMin.value, Palette.Strain) { it.decimal() }
        }
        Card("Heart rate zones") {
            ZoneBars(day.strain.zoneMinutes.takeIf { strain != null })
        }
        Card("Activities") {
            if (day.activities.isEmpty()) GhostRow(Glyph.Bolt, "Activity", "Logged after ten minutes of raised heart rate")
            day.activities.forEachIndexed { i, activity ->
                if (i > 0) Divider()
                val heart = listOfNotNull(
                    activity.averageHeartRateBpm.value?.let { "Avg $it" },
                    activity.maxHeartRateBpm.value?.let { "Max $it bpm" },
                    activity.calories.value?.let { "${it.grouped()} kcal" },
                )
                TileRow(
                    activity.strain.value?.decimal(), Palette.Strain, activity.sport.label(),
                    format.range(activity.start, activity.end), glyph = activity.sport.glyph(),
                    detail = heart.joinToString(" · ").ifEmpty { null },
                    onClick = { onOpenActivity(activity.id) },
                )
            }
        }
        Card("Last 7 days") {
            val week = state.week
            WeekBars(
                week.map { it.strain.score.value?.toFloat() }, week.dayLabels(), 21f,
                week.map { Palette.Strain }, week.map { it.strain.score.value?.decimal() ?: "" },
            )
        }
    }
}
