package app.circlet.ui.day

import app.circlet.core.BandSteps
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.circlet.ui.design.Tag
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
import app.circlet.core.CalibrationStatus
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.SkinTemperature
import app.circlet.core.SleepSession
import app.circlet.ui.design.AbsentDash
import app.circlet.ui.design.InfoButton
import app.circlet.ui.design.SleepWindow
import app.circlet.ui.design.SleepWindows
import app.circlet.ui.design.BaselineLegend
import app.circlet.ui.design.BaselineRow
import app.circlet.ui.design.SectionTitle
import app.circlet.ui.design.change
import app.circlet.ui.design.toward
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
private fun RangeBlock(ref: MetricReference?, value: Double?, format: (Double) -> String) {
    if (ref == null) return
    Spacer(Modifier.height(10.dp))
    RangeBar(
        value, ref.personal, ref.ideal, valueText = value?.let(format), toward = ref.kind.toward(),
        usualText = ref.personal?.let { "${format(it.low)}–${format(it.high)}" },
    )
    RangeLegend(ref.personal, ref.personalDays, ref.ideal, format)
}

/** A bar-chart ceiling a little above the week's highest value, so the tallest bar keeps room for its label. */
private fun DayUiState.weekTop(pick: (DaySummary) -> Double?): Float =
    ((week.mapNotNull(pick).maxOrNull() ?: 1.0) * 1.15).toFloat().coerceAtLeast(1f)

/** The mean of [pick] over the days before the one shown, or null with fewer than two. */
private fun DayUiState.usual(pick: (DaySummary) -> Double?): Double? = average(earlier.mapNotNull(pick))

/** One metric's name over its range bar. Draws nothing when the day has no reference for it. */
@Composable
private fun RangeItem(name: String, ref: MetricReference?, value: Double?, unit: String, format: (Double) -> String) {
    if (ref == null) return
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = label(13.sp, Palette.White), modifier = Modifier.weight(1f))
        if (value == null) AbsentDash()
        Text(unit, style = label(10.sp, Palette.Faint), modifier = Modifier.padding(start = 6.dp))
    }
    RangeBlock(ref, value, format)
    Spacer(Modifier.height(12.dp))
}

/**
 * The experimental band step count for the day beside the phone's, while the user has band
 * steps on or the day already holds a band count.
 */
@Composable
private fun BandStepsCard(band: BandSteps, state: DayUiState, day: DaySummary) {
    val on by band.enabled.collectAsState()
    val days by band.days.collectAsState()
    val counted = days[state.date]
    if (!on && counted == null) return
    Card("BAND STEPS", trailing = { Tag("EXPERIMENTAL", Palette.Warn) }) {
        StatRow(
            "COUNTED BY THE BAND", counted?.steps?.grouped(),
            note = if (counted == null) "Open Circlet with the band connected to count" else "During ${hoursMinutes(counted.streamedSeconds / 60)} of live streaming",
        )
        Divider()
        StatRow("COUNTED BY THE PHONE", day.steps.count.value?.grouped(), absent = day.steps.count.absentReason)
        Spacer(Modifier.height(6.dp))
        Text(
            "The band counts only while Circlet is open and connected, so it covers part of the day. It is not yet validated; compare it with the phone on a walk.",
            style = body(12.sp, Palette.Faint),
        )
    }
}

/** One card of bars for the last seven days, under the screen's "Weekly Trends" heading. */
@Composable
private fun WeeklyTrend(
    title: String,
    state: DayUiState,
    max: Float,
    color: (DaySummary) -> Color,
    pick: (DaySummary) -> Float?,
    caption: (DaySummary) -> String?,
) {
    Card(title) {
        val week = state.week
        WeekBars(week.map(pick), week.dayLabels(), max, week.map(color), week.map { caption(it) ?: "" })
    }
}

@Composable
fun SleepScreen(state: DayUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    val editor = remember(state.date) { SleepEditor() }
    DetailFrame(
        "SLEEP", state, onBack, onRetry, "SLEEP PERFORMANCE",
        action = { day ->
            if (day.sleep.value != null) IconButton(Glyph.Edit, "Edit sleep", Palette.Dim) { editor.editing = true }
        },
    ) { day ->
        val format = LocalTimeFormat.current
        val sleep = day.sleep.value
        val performance = day.sleepPerformance.percent
        val percent = performance.value
        // Whether the need was met is read from the hours themselves, never from a percentage cut-off.
        val short = sleep?.timeAsleep?.let { asleep -> day.sleepNeed.duration.value?.minus(asleep) }
        HeroDial(
            "SLEEP PERFORMANCE", Glyph.Moon, Palette.Sleep, percent?.toString(), "%", percent?.div(100f),
            when {
                percent == null && (day.sleep.absentReason ?: performance.absentReason) == AbsentReason.NO_SLEEP ->
                    "No sleep found for this day. Wear the strap overnight and it shows here after the morning sync."
                percent == null ->
                    "${(performance.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Sleep shows here after the morning sync."
                short != null && (short.isNegative || short.isZero) -> "You met your sleep need."
                short != null && percent >= 70 -> "You got most of the sleep you needed, ${short.hoursMinutes()} short."
                short != null -> "You fell short of your sleep need by ${short.hoursMinutes()}."
                percent >= 70 -> "You got most of the sleep you needed."
                else -> "You fell short of your sleep need."
            },
        )
        if (sleep != null) {
            NightContributors(state, day, sleep)
            NightCard(sleep)
        } else {
            Card("SLEEP STAGES", trailing = { InfoButton("Sleep stages", "Your night split into awake, light, deep (slow-wave) and REM sleep. Circlet estimates the stage every 30 seconds from your heart rate, its beat-to-beat variability and your movement. Hold and drag the chart to read any moment. Percentages are shares of your time in bed.") }) {
                EmptyState(Glyph.Moon, "NO SLEEP RECORDED", "Stages draw here after a night with the strap on, or add the night yourself.")
                Spacer(Modifier.height(Space.sm))
                SecondaryButton("ADD SLEEP", glyph = Glyph.Plus) { editor.editing = true }
                RestoreSleepButton(state.date)
            }
        }
        Card("HOURS VS. NEEDED", trailing = { InfoButton("Hours vs. needed", "How long you slept against what you needed. Your need starts from a baseline for adults, rises after harder days and when you carry sleep debt, and becomes your own as nights build up. The bar places the night against your usual range and the ideal range.") }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(
                    sleep?.timeAsleep?.hoursMinutes(), "ASLEEP",
                    absent = day.sleep.absentReason, modifier = Modifier.weight(1f),
                )
                Figure(
                    day.sleepNeed.duration.value?.hoursMinutes(), if (sleep != null) "NEEDED" else "NEEDED TONIGHT", Palette.Sleep,
                    absent = day.sleepNeed.duration.absentReason, alignEnd = true,
                    modifier = Modifier.weight(1f),
                )
            }
            RangeBlock(day.references.forKind(MetricKind.SLEEP_DURATION), sleep?.timeAsleep?.toMinutes()?.toDouble()) {
                hoursMinutes(it.toInt())
            }
            val debt = day.sleepDebt.shortfall.value?.takeIf { !it.isZero }
            val bedtime = day.sleepNeed.recommendedBedtime?.takeIf { state.isToday }
            if (debt != null || bedtime != null) {
                Divider()
                if (bedtime != null) StatRow("BED BY", format.time(bedtime), note = "To meet tonight's need")
                if (debt != null) StatRow("SLEEP DEBT", debt.hoursMinutes())
            }
        }
        if (sleep != null) {
            Card("SLEEP STATISTICS", trailing = { InfoButton("Sleep statistics", "Time in bed is the whole sleep window. Fell asleep in is how long it took to drop off once the window began. Wake events count the times you woke during the night.") }) {
                Row(Modifier.fillMaxWidth()) {
                    StatCell("TIME IN BED", sleep.timeInBed.hoursMinutes(), modifier = Modifier.weight(1f))
                    StatCell("FELL ASLEEP IN", sleep.latency.toMinutes().toString(), "MIN", modifier = Modifier.weight(1f))
                    StatCell("WAKE EVENTS", sleep.wakeEvents.size.toString(), modifier = Modifier.weight(1f))
                }
            }
        }
        NapsCard(day, state.date)
        SectionTitle("Weekly Trends")
        WeeklyTrend(
            "HOURS OF SLEEP", state, 600f, { Palette.Sleep },
            { it.sleep.value?.timeAsleep?.toMinutes()?.toFloat() }, { it.sleep.value?.timeAsleep?.hoursMinutes() },
        )
        WeeklyTrend(
            "SLEEP PERFORMANCE", state, 100f, { Palette.Sleep },
            { it.sleepPerformance.percent.value?.toFloat() }, { it.sleepPerformance.percent.value?.let { v -> "$v%" } },
        )
        Card("SLEEP CONSISTENCY", trailing = { InfoButton("Sleep consistency", "When you went to bed and woke up on each night of the week. The dashed lines mark your average bedtime and wake time; keeping close to them makes sleep more consistent.") }) {
            val week = state.week
            SleepWindows(
                week.map { d -> d.sleep.value?.let { SleepWindow(d.date, it.start, it.end) } },
                week.dayLabels(),
            )
        }
        SleepEditSheets(editor, day, state.date)
    }
}

/** How last night compares with the nights before it. */
@Composable
private fun NightContributors(state: DayUiState, day: DaySummary, sleep: SleepSession) {
    Card {
        val asleep = sleep.timeAsleep.toMinutes().toDouble()
        val asleepUsual = state.usual { it.sleep.value?.timeAsleep?.toMinutes()?.toDouble() }
        BaselineRow(
            Glyph.Moon, "HOURS OF SLEEP", hoursMinutes(asleep.toInt()), asleepUsual?.let { hoursMinutes(it.toInt()) },
            change(asleep, asleepUsual, true, 1.0),
        )
        Divider()
        val efficiency = sleep.efficiency.value?.toDouble()
        val efficiencyUsual = state.usual { it.sleep.value?.efficiency?.value?.toDouble() }
        BaselineRow(
            Glyph.Clock, "SLEEP EFFICIENCY", efficiency?.let { "${it.toInt()}%" }, efficiencyUsual?.let { "${it.decimal(0)}%" },
            change(efficiency, efficiencyUsual, true, 1.0),
        )
        Divider()
        val consistency = day.sleepConsistency.index.value
        val consistencyUsual = state.usual { it.sleepConsistency.index.value }
        BaselineRow(
            Glyph.Calendar, "SLEEP CONSISTENCY", consistency?.let { "${it.decimal(0)}%" }, consistencyUsual?.let { "${it.decimal(0)}%" },
            change(consistency, consistencyUsual, true, 1.0),
        )
        Divider()
        val breaths = day.heart.respiratoryRate.breathsPerMinute.value
        val breathsUsual = state.usual { it.heart.respiratoryRate.breathsPerMinute.value }
        BaselineRow(
            Glyph.Breathing, "RESPIRATORY RATE", breaths?.decimal(), breathsUsual?.decimal(),
            change(breaths, breathsUsual, null, 0.1), unit = "RPM",
        )
        BaselineLegend(state.earlier.size, state.isToday)
    }
}

/** The night itself: the scrubbable hypnogram over how its time split across the stages. */
@Composable
private fun NightCard(sleep: SleepSession) {
    Card("SLEEP STAGES", trailing = { InfoButton("Sleep stages", "Your night split into awake, light, deep (slow-wave) and REM sleep. Circlet estimates the stage every 30 seconds from your heart rate, its beat-to-beat variability and your movement. Hold and drag the chart to read any moment. Percentages are shares of your time in bed.") }) {
        Hypnogram(sleep)
        Spacer(Modifier.height(18.dp))
        StageBreakdown(sleep)
    }
}

@Composable
fun RecoveryScreen(state: DayUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    DetailFrame("RECOVERY", state, onBack, onRetry, "RECOVERY") { day ->
        val recovery = day.recovery
        val score = recovery.score.value
        val calibrating = recovery.calibration == CalibrationStatus.CALIBRATING
        HeroDial(
            "RECOVERY", Glyph.Pulse, Palette.recovery(score), score?.toString(), "%", score?.div(100f),
            when {
                score == null && calibrating ->
                    "Circlet is still learning your baseline. Recovery is scored from your heart rate variability, resting heart rate and sleep once it has a few nights to compare against."
                score == null ->
                    "${(recovery.score.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Recovery is scored each morning from your heart rate variability, resting heart rate and sleep."
                score >= 67 && state.isToday -> "You are well recovered. Your body is ready to take on strain today."
                score >= 67 -> "You were well recovered, ready to take on strain that day."
                score >= 34 && state.isToday -> "You are adequately recovered and can handle a moderate amount of strain."
                score >= 34 -> "You were adequately recovered, able to handle a moderate amount of strain that day."
                state.isToday -> "Your body is run down. Prioritise rest and keep strain light today."
                else -> "Your body was run down that day, best suited to rest and light strain."
            },
            tag = if (score != null && calibrating) "EARLY ESTIMATE · ${state.baselineNights} OF $BASELINE_NIGHTS NIGHTS" else null,
        )
        Card {
            val hrv = day.heart.hrv.rmssdMs.value
            val hrvUsual = state.usual { it.heart.hrv.rmssdMs.value }
            BaselineRow(Glyph.Pulse, "HEART RATE VARIABILITY", hrv?.decimal(0), hrvUsual?.decimal(0), change(hrv, hrvUsual, true, 1.0))
            Divider()
            val resting = day.heart.resting.bpm.value?.toDouble()
            val restingUsual = state.usual { it.heart.resting.bpm.value?.toDouble() }
            BaselineRow(Glyph.Heart, "RESTING HEART RATE", resting?.decimal(0), restingUsual?.decimal(0), change(resting, restingUsual, false, 1.0), unit = "BPM")
            Divider()
            val breaths = day.heart.respiratoryRate.breathsPerMinute.value
            val breathsUsual = state.usual { it.heart.respiratoryRate.breathsPerMinute.value }
            BaselineRow(Glyph.Breathing, "RESPIRATORY RATE", breaths?.decimal(), breathsUsual?.decimal(), change(breaths, breathsUsual, null, 0.1), unit = "RPM")
            Divider()
            val performance = day.sleepPerformance.percent.value?.toDouble()
            val performanceUsual = state.usual { it.sleepPerformance.percent.value?.toDouble() }
            BaselineRow(
                Glyph.Moon, "SLEEP PERFORMANCE", performance?.let { "${it.toInt()}%" }, performanceUsual?.let { "${it.decimal(0)}%" },
                change(performance, performanceUsual, true, 1.0),
            )
            BaselineLegend(state.earlier.size, state.isToday)
        }
        if (calibrating) {
            Card("BASELINE") {
                StatRow(
                    "NIGHTS", "${state.baselineNights} OF $BASELINE_NIGHTS",
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
        val refs = day.references
        if (listOf(MetricKind.HRV, MetricKind.RESTING_HEART_RATE, MetricKind.RESPIRATORY_RATE).any { refs.forKind(it) != null }) {
            Card("YOUR NORMAL RANGE", trailing = { InfoButton("Your normal range", "The grey bracket is your usual range over recent nights. Teal segments mark the healthy range for adults like you, and orange segments sit further from it. The marker is this day's value.") }) {
                RangeItem("HRV", refs.forKind(MetricKind.HRV), day.heart.hrv.rmssdMs.value, "") { it.decimal(0) }
                RangeItem(
                    "RESTING HEART RATE", refs.forKind(MetricKind.RESTING_HEART_RATE), day.heart.resting.bpm.value?.toDouble(), "BPM",
                ) { it.decimal(0) }
                RangeItem(
                    "RESPIRATORY RATE", refs.forKind(MetricKind.RESPIRATORY_RATE),
                    day.heart.respiratoryRate.breathsPerMinute.value, "RPM",
                ) { it.decimal() }
            }
        }
        Card("HRV IN DETAIL", trailing = { InfoButton("HRV in detail", "SDNN is the overall spread of the gaps between heartbeats overnight. LF / HF compares slower and faster rhythms in that variability, a rough read of how stress and rest systems are balanced.") }) {
            MetricRow("SDNN", day.heart.hrv.sdnnMs, "MS") { it.decimal(0) }
            Divider()
            StatRow(
                "LF / HF", day.heart.hrv.frequency?.lfHfRatio?.decimal(2),
                absent = if (day.heart.hrv.frequency == null) day.heart.hrv.rmssdMs.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
            )
        }
        SkinTemperatureCard(day.skinTemperature)
        SectionTitle("Weekly Trends")
        WeeklyTrend(
            "RECOVERY", state, 100f, { Palette.recovery(it.recovery.score.value) },
            { it.recovery.score.value?.toFloat() }, { it.recovery.score.value?.let { v -> "$v%" } },
        )
        WeeklyTrend(
            "HEART RATE VARIABILITY", state, state.weekTop { it.heart.hrv.rmssdMs.value }, { Palette.Strain },
            { it.heart.hrv.rmssdMs.value?.toFloat() }, { it.heart.hrv.rmssdMs.value?.decimal(0) },
        )
        WeeklyTrend(
            "RESTING HEART RATE", state, state.weekTop { it.heart.resting.bpm.value?.toDouble() }, { Palette.Strain },
            { it.heart.resting.bpm.value?.toFloat() }, { it.heart.resting.bpm.value?.toString() },
        )
    }
}

/** WHOOP-imported degrees are the only verified temperature readings available. */
@Composable
private fun SkinTemperatureCard(skin: SkinTemperature?) {
    Card("SKIN TEMPERATURE", trailing = {
        InfoButton(
            "Skin temperature",
            "Circlet cannot read verified skin temperature from WHOOP 4.0 through this connection. " +
                "More nights or another sync will not unlock it. You can view nightly temperatures imported from a WHOOP export.",
        )
    }) {
        val imported = skin?.importedCelsius?.takeIf { it.isFinite() && it in 20.0..45.0 }
        if (imported != null) {
            StatRow("NIGHTLY TEMPERATURE", imported.decimal(1), "°C")
            Spacer(Modifier.height(6.dp))
            Text("Scored by WHOOP, from your imported history.", style = body(13.sp, Palette.Faint))
        } else {
            Text("Unavailable through this connection", style = body(15.sp, Palette.White))
            Spacer(Modifier.height(8.dp))
            Text(
                "Circlet cannot read verified skin temperature from WHOOP 4.0. This is not a baseline or wear-time issue. " +
                    "Nightly temperatures from a WHOOP export can be viewed here.",
                style = body(13.sp, Palette.Faint),
            )
        }
    }
}

private fun RecoveryDriverKind.label() = when (this) {
    RecoveryDriverKind.HRV -> "HRV"
    RecoveryDriverKind.RESTING_HEART_RATE -> "RESTING HEART RATE"
    RecoveryDriverKind.SLEEP -> "SLEEP"
    RecoveryDriverKind.RESPIRATORY_RATE -> "RESPIRATORY RATE"
}

/** The inputs behind the score, largest effect first, each as points added or taken. */
@Composable
fun DriversCard(drivers: List<RecoveryDriver>) {
    Card("WHAT MOVED YOUR RECOVERY", trailing = { InfoButton("What moved your recovery", "The points each input added to or took from your recovery score, compared with your own baseline. Inputs further from your normal move the score more.") }) {
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
                            style = number(18.sp, if (points >= 0) Palette.Better else Palette.Worse),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                DivergingBar(points?.div(scale)?.toFloat(), Palette.Better, Palette.Worse, Modifier.fillMaxWidth())
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
    bandSteps: BandSteps? = null,
) {
    DetailFrame("STRAIN", state, onBack, onRetry, "DAY STRAIN") { day ->
        val format = LocalTimeFormat.current
        val strain = day.strain.score.value
        val target = day.targetStrain.score
        HeroDial(
            "DAY STRAIN", Glyph.Bolt, Palette.Strain, strain?.decimal(), "", strain?.div(21.0)?.toFloat(),
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
        Card {
            val zones = day.strain.zoneMinutes.takeIf { strain != null }
            fun zoneSum(of: DaySummary, range: IntRange) =
                of.strain.zoneMinutes.takeIf { of.strain.score.present }?.minutes?.slice(range)?.sum()?.toDouble()
            val low = zones?.minutes?.slice(1..3)?.sum()?.toDouble()
            val lowUsual = state.usual { zoneSum(it, 1..3) }
            BaselineRow(
                Glyph.Heart, "HR ZONES 1-3", low?.let { hoursMinutes(it.toInt()) }, lowUsual?.let { hoursMinutes(it.toInt()) },
                change(low, lowUsual, true, 1.0),
            )
            Divider()
            val high = zones?.minutes?.slice(4..5)?.sum()?.toDouble()
            val highUsual = state.usual { zoneSum(it, 4..5) }
            BaselineRow(
                Glyph.Pulse, "HR ZONES 4-5", high?.let { hoursMinutes(it.toInt()) }, highUsual?.let { hoursMinutes(it.toInt()) },
                change(high, highUsual, true, 1.0),
            )
            Divider()
            val average = day.heart.averageBpm.value?.toDouble()
            val averageUsual = state.usual { it.heart.averageBpm.value?.toDouble() }
            BaselineRow(Glyph.Heart, "AVERAGE HEART RATE", average?.decimal(0), averageUsual?.decimal(0), change(average, averageUsual, null, 1.0), unit = "BPM")
            Divider()
            val calories = day.calories.value?.toDouble()
            val caloriesUsual = state.usual { it.calories.value?.toDouble() }
            BaselineRow(
                Glyph.Flame, "CALORIES", day.calories.value?.grouped(), caloriesUsual?.toInt()?.grouped(),
                change(calories, caloriesUsual, null, 1.0), unit = "KCAL",
            )
            Divider()
            val steps = day.steps.count.value?.toDouble()
            val stepsUsual = state.usual { it.steps.count.value?.toDouble() }
            BaselineRow(
                Glyph.Steps, "STEPS (PHONE)", day.steps.count.value?.grouped(), stepsUsual?.toInt()?.grouped(),
                change(steps, stepsUsual, true, 1.0),
            )
            BaselineLegend(state.earlier.size, state.isToday)
        }
        bandSteps?.let { BandStepsCard(it, state, day) }
        Card(if (state.isToday) "TODAY'S TARGET" else "TARGET", trailing = { InfoButton("Strain target", "Day strain runs from 0 to 21 and climbs as your heart works harder. The optimal range comes from that morning's recovery: the better recovered you are, the harder the day it suggests.") }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(strain?.decimal(), "DAY STRAIN", absent = day.strain.score.absentReason, modifier = Modifier.weight(1f))
                val optimal = day.targetStrain.range
                Figure(
                    optimal?.let { "${it.low.decimal()}–${it.high.decimal()}" } ?: target.value?.decimal(),
                    if (optimal != null) "OPTIMAL" else "TARGET", Palette.Strain,
                    absent = target.absentReason, alignEnd = true, modifier = Modifier.weight(1f),
                )
            }
            val ref = day.references.forKind(MetricKind.STRAIN)
            val optimal = day.targetStrain.range
            if (ref != null || optimal != null) {
                Spacer(Modifier.height(10.dp))
                RangeBar(
                    strain, ref?.personal, optimal, valueText = strain?.decimal(),
                    usualText = ref?.personal?.let { "${it.low.decimal()}–${it.high.decimal()}" },
                )
                RangeLegend(ref?.personal, ref?.personalDays ?: 0, optimal, { it.decimal() }, idealName = "OPTIMAL")
            }
            val goal = target.value
            if (strain != null && goal != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        optimal == null && strain >= goal -> "Target reached. The notch on the dial marks it."
                        optimal == null -> "${(goal - strain).decimal()} to go. The notch on the dial marks the target your recovery suggests."
                        strain < optimal.low ->
                            "${(optimal.low - strain).decimal()} to reach the optimal range your recovery suggests. The notch on the dial marks its middle."
                        strain <= optimal.high -> if (state.isToday) "Inside your optimal range for today." else "Inside the optimal range for that day."
                        else -> if (state.isToday) "Past your optimal range for today." else "Past the optimal range for that day."
                    },
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
        Card("HEART RATE ZONES", trailing = { InfoButton("Heart rate zones", "Time spent in each heart rate zone. Zones come from your heart rate reserve, the span between your resting and maximum heart rate, or from your own zones if you set them in your profile.") }) {
            ZoneBars(day.strain.zoneMinutes.takeIf { strain != null })
        }
        Card("FITNESS", trailing = { InfoButton("Fitness", "VO2 max is an estimate of aerobic fitness worked out on your phone from your maximum and resting heart rate, not a lab test. Max heart rate is the highest reading of the day.") }) {
            val vo2 = day.references.forKind(MetricKind.VO2_MAX)
            if (vo2 != null) {
                RangeItem("VO2 MAX", vo2, day.vo2Max.mlPerKgMin.value, "ML/KG/MIN") { it.decimal() }
            } else {
                MetricRow("VO2 MAX", day.vo2Max.mlPerKgMin, "ML/KG/MIN") { it.decimal() }
                Divider()
            }
            MetricRow("MAX HEART RATE", day.heart.maxBpm, "BPM") { it.toString() }
        }
        Card("ACTIVITIES") {
            if (day.activities.isEmpty()) GhostRow(Glyph.Bolt, "NO ACTIVITIES YET", "Activities appear here after ten minutes of raised heart rate")
            day.activities.forEachIndexed { i, activity ->
                if (i > 0) Divider()
                val heart = listOfNotNull(
                    activity.averageHeartRateBpm.value?.let { "AVG $it" },
                    activity.maxHeartRateBpm.value?.let { "MAX $it BPM" },
                    activity.calories.value?.let { "${it.grouped()} KCAL" },
                )
                TileRow(
                    activity.strain.value?.decimal(), Palette.Strain, activity.sport.label(),
                    format.range(activity.start, activity.end), glyph = activity.sport.glyph(),
                    detail = heart.joinToString(" · ").ifEmpty { null },
                    onClick = { onOpenActivity(activity.id) },
                )
            }
        }
        SectionTitle("Weekly Trends")
        WeeklyTrend(
            "STRAIN", state, 21f, { Palette.Strain },
            { it.strain.score.value?.toFloat() }, { it.strain.score.value?.decimal() },
        )
        WeeklyTrend(
            "CALORIES", state, state.weekTop { it.calories.value?.toDouble() }, { Palette.Strain },
            { it.calories.value?.toFloat() }, { it.calories.value?.grouped() },
        )
        WeeklyTrend(
            "STEPS", state, state.weekTop { it.steps.count.value?.toDouble() }, { Palette.Strain },
            { it.steps.count.value?.toFloat() }, { it.steps.count.value?.let { v -> if (v >= 10_000) "${v / 1000}K" else v.grouped() } },
        )
    }

}
