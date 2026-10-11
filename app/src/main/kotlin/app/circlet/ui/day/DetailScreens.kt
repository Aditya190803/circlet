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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.AbsentReason
import app.circlet.core.CalibrationStatus
import app.circlet.core.DaySummary
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.ui.design.AbsentDash
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
    content: @Composable ColumnScope.(DaySummary) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title, dateTitle(state.date, state.today), onBack)
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
    RangeBar(value, ref.personal, ref.ideal)
    RangeLegend(ref.personal, ref.personalDays, ref.ideal, ref.idealLabel, format)
}

/** "Avg of last 12 days" over the days before the selected one, or null with fewer than two. */
private fun <T : Any> DayUiState.typical(pick: (DaySummary) -> T?, format: (Double) -> String, toDouble: (T) -> Double): String? {
    val values = earlier.mapNotNull { day -> pick(day)?.let(toDouble) }
    return average(values)?.let { "avg of last ${values.size} days ${format(it)}" }
}

@Composable
fun SleepScreen(state: DayUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    DetailFrame("SLEEP", state, onBack, onRetry, "SLEEP PERFORMANCE") { day ->
        val format = LocalTimeFormat.current
        val sleep = day.sleep.value
        val performance = day.sleepPerformance.percent
        val percent = performance.value
        HeroDial(
            "SLEEP PERFORMANCE", Glyph.Moon, Palette.Sleep, percent?.toString(), "%", percent?.div(100f),
            when {
                percent == null && (day.sleep.absentReason ?: performance.absentReason) == AbsentReason.NO_SLEEP ->
                    "No sleep was found for this day. Wear the strap overnight and it is picked up on its own, then shown here after the morning sync."
                percent == null ->
                    "${(performance.absentReason ?: AbsentReason.NOT_ENOUGH_DATA).words()}. Sleep is picked up on its own overnight and shown here after the morning sync."
                percent >= 85 -> "You got enough sleep to meet your need. Keep it up."
                percent >= 70 -> "You got most of the sleep you needed. A little more will help your recovery."
                else -> "You fell well short of your sleep need. Try to get to bed earlier tonight."
            },
            confidence = performance.confidence,
        )
        Card("HOURS VS. NEEDED") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(
                    sleep?.timeAsleep?.hoursMinutes(), "HOURS OF SLEEP",
                    absent = day.sleep.absentReason, confidence = day.sleep.confidence, modifier = Modifier.weight(1f),
                )
                Figure(
                    day.sleepNeed.duration.value?.hoursMinutes(), if (sleep != null) "SLEEP NEEDED" else "NEEDED TONIGHT", Palette.Sleep,
                    absent = day.sleepNeed.duration.absentReason, confidence = day.sleepNeed.duration.confidence, alignEnd = true,
                    modifier = Modifier.weight(1f),
                )
            }
            RangeBlock(day.references.forKind(MetricKind.SLEEP_DURATION), sleep?.timeAsleep?.toMinutes()?.toDouble()) { hoursMinutes(it.toInt()) }
            val debt = day.sleepDebt.shortfall.value
            val bedtime = day.sleepNeed.recommendedBedtime
            val lines = listOfNotNull(
                debt?.takeIf { !it.isZero }?.let { "You are carrying ${it.hoursMinutes()} of sleep debt." },
                bedtime?.takeIf { state.isToday }?.let { "Be in bed by ${format.time(it)} tonight to meet your need." },
            )
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(lines.joinToString(" "), style = body(13.sp, Palette.Faint))
            }
        }
        if (sleep != null) {
            StagesCard(sleep)
            Card("SLEEP STATISTICS") {
                StatRow("TIME IN BED", sleep.timeInBed.hoursMinutes())
                Divider()
                MetricRow("SLEEP EFFICIENCY", sleep.efficiency, "%") { it.toString() }
                RangeBlock(day.references.forKind(MetricKind.SLEEP_EFFICIENCY), sleep.efficiency.value?.toDouble()) { "${it.decimal(0)}%" }
                Divider()
                StatRow("TIME TO FALL ASLEEP", sleep.latency.toMinutes().toString(), "MIN")
                Divider()
                StatRow(
                    "WAKE EVENTS", sleep.wakeEvents.size.toString(),
                    note = sleep.wakeEvents.takeIf { it.isNotEmpty() }?.let { events -> "${events.sumOf { it.duration.toMinutes() }} min awake in total" },
                )
                Divider()
                MetricRow("CONSISTENCY", day.sleepConsistency.index, "%", note = "How closely recent bedtimes agree") { it.decimal(0) }
                Divider()
                MetricRow("RESPIRATORY RATE", day.heart.respiratoryRate.breathsPerMinute, "RPM") { it.decimal() }
            }
        } else {
            Card("SLEEP STAGES") {
                EmptyState(Glyph.Moon, "NO STAGES YET", "Awake, light, deep and REM draw here after a night with the strap on.")
            }
        }
        Card("NAPS") {
            if (day.naps.isEmpty()) GhostRow(Glyph.Moon, "NAP", "Daytime sleep is kept apart from the night")
            day.naps.forEachIndexed { i, nap ->
                if (i > 0) Divider()
                TileRow(nap.timeAsleep.hoursMinutes(), Palette.Sleep, "NAP", format.range(nap.start, nap.end))
            }
        }
        Card("LAST 7 DAYS") {
            val week = state.week
            WeekBars(
                week.map { it.sleep.value?.timeAsleep?.toMinutes()?.toFloat() }, week.dayLabels(), 600f,
                week.map { Palette.Sleep }, week.map { it.sleep.value?.timeAsleep?.hoursMinutes() ?: "" },
            )
        }
    }
}

@Composable
private fun StagesCard(sleep: SleepSession) {
    val format = LocalTimeFormat.current
    Card("SLEEP STAGES") {
        Hypnogram(sleep)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format.time(sleep.start), style = label(10.sp, Palette.Faint))
            if (sleep.cycles.isNotEmpty()) {
                Text("${sleep.cycles.size} ${if (sleep.cycles.size == 1) "CYCLE" else "CYCLES"}", style = label(10.sp, Palette.Faint))
            }
            Text(format.time(sleep.end), style = label(10.sp, Palette.Faint))
        }
        Spacer(Modifier.height(8.dp))
        val inBed = sleep.timeInBed.toMinutes().coerceAtLeast(1)
        for ((name, stage) in listOf("AWAKE" to SleepStage.AWAKE, "LIGHT" to SleepStage.LIGHT, "SWS (DEEP)" to SleepStage.DEEP, "REM" to SleepStage.REM)) {
            Divider()
            val minutes = sleep.epochs.filter { it.stage == stage }.sumOf { it.duration.toMinutes() }
            StatRow(name, hoursMinutes(minutes.toInt()), "${minutes * 100 / inBed}%", Palette.stage(stage))
        }
        Spacer(Modifier.height(8.dp))
        Text("Stages are estimated from movement, heart rate and its variability.", style = body(12.sp, Palette.Faint))
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
                score >= 67 -> "You are well recovered. Your body is ready to take on strain today."
                score >= 34 -> "You are adequately recovered and can handle a moderate amount of strain."
                else -> "Your body is run down. Prioritise rest and keep strain light today."
            },
            confidence = recovery.score.confidence,
        )
        if (calibrating) {
            Card("BASELINE") {
                StatRow("NIGHTS RECORDED", state.nightsRecorded.toString())
                Spacer(Modifier.height(6.dp))
                Text(
                    "Each night with the strap on adds to your baseline. Until it is ready the score is provisional: it is worked out from this night and typical adult values, and it sharpens as your own normal takes shape.",
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
        DriversCard(recovery.drivers)
        Card("RECOVERY STATISTICS") {
            MetricRow(
                "HRV", day.heart.hrv.rmssdMs, "MS",
                note = state.typical({ it.heart.hrv.rmssdMs.value }, { it.decimal(0) }) { it },
            ) { it.decimal(0) }
            RangeBlock(day.references.forKind(MetricKind.HRV), day.heart.hrv.rmssdMs.value) { it.decimal(0) }
            Divider()
            MetricRow(
                "RESTING HEART RATE", day.heart.resting.bpm, "BPM",
                note = state.typical({ it.heart.resting.bpm.value }, { it.decimal(0) }) { it.toDouble() },
            ) { it.toString() }
            RangeBlock(day.references.forKind(MetricKind.RESTING_HEART_RATE), day.heart.resting.bpm.value?.toDouble()) { it.decimal(0) }
            Divider()
            MetricRow(
                "RESPIRATORY RATE", day.heart.respiratoryRate.breathsPerMinute, "RPM",
                note = state.typical({ it.heart.respiratoryRate.breathsPerMinute.value }, { it.decimal() }) { it },
            ) { it.decimal() }
            RangeBlock(
                day.references.forKind(MetricKind.RESPIRATORY_RATE),
                day.heart.respiratoryRate.breathsPerMinute.value?.toDouble(),
            ) { it.decimal() }
            Divider()
            MetricRow("SLEEP PERFORMANCE", day.sleepPerformance.percent, "%") { it.toString() }
        }
        Card("HRV IN DETAIL") {
            MetricRow("SDNN", day.heart.hrv.sdnnMs, "MS") { it.decimal(0) }
            Divider()
            StatRow(
                "LF / HF", day.heart.hrv.frequency?.lfHfRatio?.decimal(2),
                note = day.heart.hrv.frequency?.let { "LF ${it.lfMs2.decimal(0)} · HF ${it.hfMs2.decimal(0)} ms²" },
                absent = if (day.heart.hrv.frequency == null) day.heart.hrv.rmssdMs.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
            )
        }
        SkinTemperatureCard(day.skinTemperature)
        Card("LAST 7 DAYS") {
            val week = state.week
            WeekBars(
                week.map { it.recovery.score.value?.toFloat() }, week.dayLabels(), 100f,
                week.map { Palette.recovery(it.recovery.score.value) }, week.map { it.recovery.score.value?.let { v -> "$v%" } ?: "" },
            )
        }
    }
}

/**
 * Skin temperature for the night, as a deviation in standard deviations of the user's own nights.
 * It never shows degrees: the strap has no calibrated temperature.
 */
@Composable
private fun SkinTemperatureCard(skin: SkinTemperature?) {
    Card("SKIN TEMPERATURE") {
        when {
            skin == null -> Text(
                "Skin temperature is not scored for this night. It needs the strap worn through the night.",
                style = body(13.sp, Palette.Faint),
            )
            skin.flag == SkinTempFlag.NOT_ENOUGH_BASELINE -> Text(
                "Building your baseline. ${skin.baselineNights} prior ${if (skin.baselineNights == 1) "night" else "nights"} so far, " +
                    "so there is nothing to compare yet. Readings are a deviation from your own nights, not a temperature.",
                style = body(13.sp, Palette.Faint),
            )
            else -> {
                val deviation = skin.deviation.value
                StatRow(
                    "DEVIATION", deviation?.signedDeviation(), "SD",
                    color = if (skin.flag == SkinTempFlag.ELEVATED) Palette.Warn else Palette.White,
                    confidence = skin.deviation.confidence.takeIf { deviation != null },
                    absent = if (deviation == null) skin.deviation.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        deviation == null -> "No reading for this night. The value is in standard deviations of your own nights, never degrees."
                        skin.flag == SkinTempFlag.ELEVATED ->
                            "Above your usual nights, compared with ${skin.baselineNights} prior ${if (skin.baselineNights == 1) "night" else "nights"}. This is relative to you, not a temperature."
                        else ->
                            "Close to your usual nights, compared with ${skin.baselineNights} prior ${if (skin.baselineNights == 1) "night" else "nights"}. This is relative to you, not a temperature."
                    },
                    style = body(12.sp, Palette.Faint),
                )
            }
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
    Card("WHAT MOVED YOUR RECOVERY") {
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
            confidence = day.strain.score.confidence,
            marker = target.value?.div(21.0)?.toFloat(),
        )
        Card(if (state.isToday) "TODAY'S TARGET" else "TARGET") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Figure(strain?.decimal(), "DAY STRAIN", absent = day.strain.score.absentReason, modifier = Modifier.weight(1f))
                Figure(
                    target.value?.decimal(), "TARGET", Palette.Strain,
                    absent = target.absentReason, confidence = target.confidence, alignEnd = true, modifier = Modifier.weight(1f),
                )
            }
            RangeBlock(day.references.forKind(MetricKind.STRAIN), strain) { it.decimal() }
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
        Card("STRAIN STATISTICS") {
            MetricRow("AVERAGE HEART RATE", day.heart.averageBpm, "BPM") { it.toString() }
            Divider()
            MetricRow("MAX HEART RATE", day.heart.maxBpm, "BPM") { it.toString() }
            Divider()
            MetricRow("CALORIES", day.calories, "KCAL") { it.grouped() }
            Divider()
            MetricRow("STEPS", day.steps.count, tag = "ESTIMATE", note = "Counted from wrist movement") { it.grouped() }
        }
        Card("FITNESS") {
            MetricRow("VO2 MAX", day.vo2Max.mlPerKgMin, "ML/KG/MIN") { it.decimal() }
            RangeBlock(day.references.forKind(MetricKind.VO2_MAX), day.vo2Max.mlPerKgMin.value) { it.decimal() }
        }
        Card("HEART RATE ZONES") {
            ZoneBars(day.strain.zoneMinutes.takeIf { strain != null })
        }
        Card("ACTIVITIES") {
            if (day.activities.isEmpty()) GhostRow(Glyph.Bolt, "ACTIVITY", "Logged after ten minutes of raised heart rate")
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
        Card("LAST 7 DAYS") {
            val week = state.week
            WeekBars(
                week.map { it.strain.score.value?.toFloat() }, week.dayLabels(), 21f,
                week.map { Palette.Strain }, week.map { it.strain.score.value?.decimal() ?: "" },
            )
        }
    }
}
