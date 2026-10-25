package app.circlet.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import app.circlet.ui.design.LiveDot
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Icon
import app.circlet.ui.state.BASELINE_NIGHTS
import java.time.Instant
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.DaySummary
import app.circlet.core.LinkState
import app.circlet.core.LiveWorkout
import app.circlet.core.MetricKind
import app.circlet.core.SkinTempFlag
import app.circlet.core.StrapState
import app.circlet.core.WellnessKind
import app.circlet.ui.design.AbsentDash
import app.circlet.ui.design.InfoButton
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.StressTimeline
import app.circlet.core.STRESS_HIGH_CUTOFF
import app.circlet.core.STRESS_LOW_CUTOFF
import androidx.compose.ui.draw.clip
import app.circlet.ui.design.BaselineLegend
import app.circlet.ui.design.BaselineTile
import app.circlet.ui.design.SectionTitle
import app.circlet.ui.design.change
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Figure
import app.circlet.ui.design.GhostRow
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.HeartRateChart
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.MetricDial
import app.circlet.ui.design.MetricRow
import app.circlet.ui.design.NoticeCard
import app.circlet.ui.design.Palette
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.Sweep
import app.circlet.ui.design.TileRow
import app.circlet.ui.design.body
import app.circlet.ui.design.decimal
import app.circlet.ui.design.forKind
import app.circlet.ui.design.glyph
import app.circlet.ui.design.grouped
import app.circlet.ui.design.hoursMinutes
import app.circlet.ui.design.stalled
import app.circlet.ui.design.syncProgressLabel
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.design.positionTag
import app.circlet.ui.design.signedDeviation
import app.circlet.ui.design.words
import app.circlet.ui.state.DayUiState
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.Notice
import app.circlet.ui.state.hasData
import app.circlet.ui.state.valueOrNull

/** The three screens a dial or a row on Home opens. */
enum class DayScreen { SLEEP, RECOVERY, STRAIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: DayUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onShift: (Long) -> Unit,
    onOpen: (DayScreen) -> Unit,
    onNotice: (Notice) -> Unit,
    onRetry: () -> Unit,
    onProfile: () -> Unit,
    onStrap: () -> Unit,
    onOpenActivity: (String) -> Unit = {},
    onOpenAllActivities: () -> Unit = {},
    onOpenMetric: (MetricKind) -> Unit = {},
    onOpenStress: () -> Unit = {},
    live: LiveWorkout? = null,
    onStartWorkout: () -> Unit = {},
    onOpenLive: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(state.date, state.today, state.strap, onShift, onProfile, onStrap)
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            state = pull,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = Palette.CardRaised,
                    color = Palette.Teal,
                )
            },
        ) {
            ScreenColumn {
                val summary = state.day.valueOrNull
                val loading = state.day is Loadable.Loading
                if (live != null) LiveWorkoutCard(live, onOpenLive)
                Dials(summary, loading, onOpen)
                HeartCard(state, summary, loading)
                StressCard(state, summary, loading, onOpenStress)
                when (val day = state.day) {
                    is Loadable.Failed -> ErrorState(day.message, onRetry)
                    else -> state.notice?.let { DayNotice(it) { onNotice(it) } }
                }
                SectionTitle("My Day")
                MyDayCard(
                    summary, loading, onOpen, onOpenActivity, onOpenAllActivities,
                    onStartWorkout = onStartWorkout.takeIf { state.isToday && live == null }, today = state.isToday,
                )
                PlanCard(state, summary, loading)
                Dashboard(state, summary, onOpenMetric)
            }
        }
    }
}

@Composable
private fun Dials(summary: DaySummary?, loading: Boolean, onOpen: (DayScreen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val sleep = summary?.sleepPerformance?.percent
        MetricDial(
            "SLEEP", Glyph.Moon, Palette.Sleep, sleep?.value?.toString(), "%", sleep?.value?.div(100f),
            loading,
        ) { onOpen(DayScreen.SLEEP) }
        val recovery = summary?.recovery?.score
        MetricDial(
            "RECOVERY", Glyph.Pulse, Palette.recovery(recovery?.value), recovery?.value?.toString(), "%", recovery?.value?.div(100f),
            loading,
        ) { onOpen(DayScreen.RECOVERY) }
        val strain = summary?.strain?.score
        MetricDial(
            "STRAIN", Glyph.Bolt, Palette.Strain, strain?.value?.decimal(), "", strain?.value?.div(21.0)?.toFloat(),
            loading,
        ) { onOpen(DayScreen.STRAIN) }
    }
}

/** The contextual card for a [Notice]. [onAction] is only reachable for notices that carry a button. */
@Composable
fun DayNotice(notice: Notice, onAction: () -> Unit) {
    when (notice) {
        Notice.NeedsPermission -> NoticeCard(
            Glyph.Bluetooth, "Bluetooth access is off", "Circlet needs the Nearby devices permission to reach your strap.",
            action = "ALLOW ACCESS", onAction = onAction,
        )
        Notice.Unpaired -> NoticeCard(
            Glyph.Strap, "No strap paired", "Pair your strap and Circlet starts reading your heart rate straight away.",
            action = "PAIR STRAP", onAction = onAction,
        )
        Notice.BluetoothOff -> NoticeCard(Glyph.Bluetooth, "Bluetooth is turned off", "Turn it on and your strap reconnects on its own.")
        Notice.Searching -> NoticeCard(
            Glyph.Search, "Looking for your strap",
            "Keep it within a few metres. It reconnects by itself, and nothing is lost while it is away.",
        ) { Sweep(Palette.Sleep) }
        is Notice.StrapTrouble -> NoticeCard(
            Glyph.Warning, "The strap needs attention", notice.error.words(),
            tint = Palette.Warn, action = "OPEN STRAP", onAction = onAction,
        )
        is Notice.CatchingUp -> NoticeCard(
            Glyph.Pulse, "Catching up on history",
            "${hoursMinutes(notice.minutes)} of heart rate pulled from the strap so far. Scores fill in as it arrives.",
        ) { Sweep(Palette.Strain) }
        Notice.FirstNight -> NoticeCard(
            Glyph.Moon, "Your first night is ahead",
            "Wear the strap to bed. Sleep appears tomorrow morning, and Strain builds through today.",
        )
        is Notice.Calibrating -> NoticeCard(
            Glyph.Pulse, "Learning your baseline",
            "${notice.nights} of $BASELINE_NIGHTS nights recorded, ${notice.left} to go. " +
                "Until then recovery is an early estimate against typical adult values.",
        )
        is Notice.Wellness -> NoticeCard(
            Glyph.Heart,
            when (notice.flag.kind) {
                WellnessKind.ILLNESS -> "Your body may be fighting something"
                WellnessKind.OVERREACHING -> "You may be overreaching"
            },
            notice.flag.detail.trimEnd('.') + "." +
                if (notice.flag.confidence == Confidence.HIGH) "" else " This is an early signal, not a diagnosis.",
            tint = Palette.Warn,
        )
        is Notice.EmptyDay -> if (notice.today) {
            NoticeCard(Glyph.Strap, "Nothing from today yet", "${notice.reason.words()}. Pull down to sync.")
        } else {
            NoticeCard(Glyph.Strap, "Nothing recorded this day", "${notice.reason.words()}.")
        }
    }
}

private fun StrapState.status(): String = when {
    sync.draining -> syncProgressLabel(sync)
    link == LinkState.CONNECTED && sync.stalled() -> "SYNC STALLED"
    link == LinkState.CONNECTED && onWrist == false -> "OFF WRIST"
    link == LinkState.CONNECTED -> "LIVE"
    link == LinkState.CONNECTING || link == LinkState.RECONNECTING -> "SEARCHING"
    else -> "OFFLINE"
}

@Composable
private fun HeartCard(state: DayUiState, summary: DaySummary?, loading: Boolean) {
    Card("HEART RATE", trailing = { InfoButton("Heart rate", "Today shows the live reading from your strap; earlier days show the day's average. Hold and drag the chart to read any minute.") }) {
        val strap = state.strap
        val connected = strap.link == LinkState.CONNECTED
        // Today shows the live reading; an earlier day shows that day's average instead.
        val live = strap.liveHeartRateBpm?.takeIf { it > 0 }
        val shown = if (state.isToday) live else summary?.heart?.averageBpm?.value
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                loading -> Skeleton(Modifier.padding(vertical = 16.dp).size(width = 56.dp, height = 16.dp))
                shown != null -> Text(shown.toString(), style = number(40.sp))
                else -> AbsentDash(Modifier.padding(vertical = 22.dp).size(width = 34.dp, height = 4.dp))
            }
            Text(if (state.isToday) "  BPM" else "  AVG BPM", style = label(12.sp), modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.weight(1f))
            if (state.isToday) {
                LiveDot(if (connected) Palette.Teal else Palette.Faint, connected && live != null)
                Text(strap.status(), style = label(11.sp, if (connected) Palette.Teal else Palette.Faint), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        HeartRateChart(
            summary?.heartRateByMinute.orEmpty(),
            Palette.Strain,
            if (state.isToday) "Your day's heart rate draws here as the strap syncs" else "No heart rate was recorded on this day",
            loading = loading,
        )
    }
}

/** The day's stress score and its timeline. Tapping the card opens the full stress day. */
@Composable
private fun StressCard(state: DayUiState, summary: DaySummary?, loading: Boolean, onOpen: () -> Unit) {
    val stress = summary?.stress
    val score = stress?.score?.value
    Card(
        "STRESS MONITOR",
        modifier = Modifier
            .clip(Shapes.card)
            .clickable(role = Role.Button, onClickLabel = "Open stress", onClick = onOpen),
        trailing = { InfoButton("Stress monitor", "Stress runs from 0 to 100 and is read through the day from the rhythm of your heartbeats, using Baevsky's stress index. Higher means your body is under more load. Hold and drag the chart to read any hour, or tap the card for the full day.") },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            when {
                loading -> Skeleton(Modifier.padding(vertical = 12.dp).size(width = 56.dp, height = 16.dp))
                score == null -> AbsentDash(Modifier.padding(vertical = 18.dp).size(width = 34.dp, height = 4.dp))
                else -> {
                    Text(score.toInt().toString(), style = number(40.sp, Palette.stress(score)))
                    Text(" / 100", style = label(11.sp, Palette.Faint), modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (score != null) {
                Text(
                    when {
                        score >= STRESS_HIGH_CUTOFF -> "HIGH"
                        score >= STRESS_LOW_CUTOFF -> "MEDIUM"
                        else -> "LOW"
                    },
                    style = label(12.sp, Palette.stress(score)),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        if (!loading) {
            Spacer(Modifier.height(8.dp))
            StressTimeline(
                stress?.timeline.orEmpty(),
                state.date.atStartOfDay(LocalTimeFormat.current.zone).toInstant(),
                if (state.isToday) "Stress draws here through the day as the strap syncs" else stress?.score?.absentReason?.words() ?: "No stress recorded this day",
            )
        }
    }
}

@Composable
private fun PlanCard(state: DayUiState, summary: DaySummary?, loading: Boolean) {
    Card(if (state.isToday) "TODAY'S PLAN" else "PLAN FOR THE DAY", trailing = { InfoButton("Plan", "The optimal strain comes from that morning's recovery. Sleep need starts from your baseline need and rises with the day's strain and any sleep debt you carry.") }) {
        val target = summary?.targetStrain?.score
        val optimal = summary?.targetStrain?.range
        val need = summary?.sleepNeed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Figure(
                optimal?.let { "${it.low.decimal()}–${it.high.decimal()}" } ?: target?.value?.decimal(),
                if (optimal != null) "OPTIMAL STRAIN" else "STRAIN TARGET", Palette.Strain,
                absent = target?.absentReason, loading = loading,
                modifier = Modifier.weight(1f),
            )
            Figure(
                need?.duration?.value?.hoursMinutes(), if (state.isToday) "SLEEP NEED TONIGHT" else "SLEEP NEED", Palette.Sleep,
                absent = need?.duration?.absentReason, alignEnd = true, loading = loading,
                modifier = Modifier.weight(1f),
            )
        }
        val bedtime = need?.recommendedBedtime
        if (bedtime != null && state.isToday) {
            Spacer(Modifier.height(12.dp))
            Text("Be in bed by ${LocalTimeFormat.current.time(bedtime)} to meet it with a 7:00 wake.", style = body(13.sp, Palette.Faint))
        }
    }
}

@Composable
private fun MyDayCard(
    summary: DaySummary?,
    loading: Boolean,
    onOpen: (DayScreen) -> Unit,
    onOpenActivity: (String) -> Unit,
    onOpenAllActivities: () -> Unit,
    onStartWorkout: (() -> Unit)?,
    today: Boolean,
) {
    val format = LocalTimeFormat.current
    Card(if (today) "TODAY'S ACTIVITIES" else "ACTIVITIES") {
        if (loading) {
            repeat(2) {
                if (it > 0) Divider()
                Skeleton(Modifier.padding(vertical = 14.dp).size(width = 200.dp, height = 28.dp))
            }
            return@Card
        }
        val sleep = summary?.sleep?.value
        if (sleep != null) {
            TileRow(sleep.timeAsleep.hoursMinutes(), Palette.Sleep, "SLEEP", format.range(sleep.start, sleep.end)) { onOpen(DayScreen.SLEEP) }
        } else {
            val reason = summary?.sleep?.absentReason?.takeIf { summary.hasData }
            GhostRow(Glyph.Moon, "NO SLEEP YET", reason?.words() ?: "Sleep appears here after a night with the strap on")
        }
        summary?.naps?.forEach { nap ->
            Divider()
            TileRow(nap.timeAsleep.hoursMinutes(), Palette.Sleep, "NAP", format.range(nap.start, nap.end)) { onOpen(DayScreen.SLEEP) }
        }
        Divider()
        val activities = summary?.activities.orEmpty()
        if (activities.isEmpty()) GhostRow(Glyph.Bolt, "NO ACTIVITIES YET", "Activities appear here after ten minutes of raised heart rate")
        activities.forEachIndexed { i, activity ->
            if (i > 0) Divider()
            TileRow(
                activity.strain.value?.decimal(), Palette.Strain, activity.sport.label(),
                format.range(activity.start, activity.end), glyph = activity.sport.glyph(),
            ) { onOpenActivity(activity.id) }
        }
        Divider()
        if (onStartWorkout != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("START A WORKOUT") { onStartWorkout() }
        }
        QuietButton("ALL ACTIVITIES") { onOpenAllActivities() }
    }
}

/** The workout being recorded, shown above everything else on Home. Tapping it opens the live page. */
@Composable
private fun LiveWorkoutCard(live: LiveWorkout, onOpen: () -> Unit) {
    var now by androidx.compose.runtime.remember(live.startedAt) { androidx.compose.runtime.mutableStateOf(Instant.now()) }
    androidx.compose.runtime.LaunchedEffect(live.startedAt) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }
    val seconds = live.activeDuration(now).seconds
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Open the live workout", onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(color = if (live.paused) Palette.Warn else Palette.Teal, active = !live.paused)
                    Spacer(Modifier.width(8.dp))
                    val state = if (live.paused) "PAUSED" else "IN PROGRESS"
                    Text(
                        "${live.sport.label().uppercase()} $state",
                        style = label(12.sp, if (live.paused) Palette.Warn else Palette.Teal),
                    )
                }
                Text(
                    "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60),
                    style = number(34.sp, Palette.White),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(live.heartRateBpm?.toString() ?: "–", style = number(26.sp, Palette.White))
                Text("BPM", style = label(10.sp, Palette.Faint))
            }
            Icon(Glyph.ChevronRight, Palette.Faint, 18.dp, Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Dashboard(state: DayUiState, summary: DaySummary?, onOpenMetric: (MetricKind) -> Unit) {
    SectionTitle("My Dashboard")
    if (summary == null) {
        for ((glyph, name) in listOf(
            Glyph.Pulse to "HEART RATE VARIABILITY", Glyph.Heart to "RESTING HEART RATE", Glyph.Breathing to "RESPIRATORY RATE",
            Glyph.Moon to "HOURS OF SLEEP", Glyph.Flame to "CALORIES", Glyph.Steps to "STEPS",
        )) BaselineTile(glyph, name, null, null, null)
        return
    }
    fun usual(pick: (DaySummary) -> Double?) = average(state.earlier.mapNotNull(pick))

    val hrv = summary.heart.hrv.rmssdMs.value
    val hrvUsual = usual { it.heart.hrv.rmssdMs.value }
    BaselineTile(
        Glyph.Pulse, "HEART RATE VARIABILITY", hrv?.decimal(0), hrvUsual?.decimal(0), change(hrv, hrvUsual, true, 1.0),
    ) { onOpenMetric(MetricKind.HRV) }

    val resting = summary.heart.resting.bpm.value?.toDouble()
    val restingUsual = usual { it.heart.resting.bpm.value?.toDouble() }
    BaselineTile(
        Glyph.Heart, "RESTING HEART RATE", resting?.decimal(0), restingUsual?.decimal(0), change(resting, restingUsual, false, 1.0), unit = "BPM",
    ) { onOpenMetric(MetricKind.RESTING_HEART_RATE) }

    val breaths = summary.heart.respiratoryRate.breathsPerMinute.value
    val breathsUsual = usual { it.heart.respiratoryRate.breathsPerMinute.value }
    BaselineTile(
        Glyph.Breathing, "RESPIRATORY RATE", breaths?.decimal(), breathsUsual?.decimal(), change(breaths, breathsUsual, null, 0.1), unit = "RPM",
    ) { onOpenMetric(MetricKind.RESPIRATORY_RATE) }

    // Skin temperature is already a deviation from the user's own nights, so its baseline is zero.
    // While that baseline builds, the deviation is an early estimate and the tile says so.
    val skin = summary.skinTemperature
    val deviation = skin?.deviation?.value
    val building = skin?.flag == SkinTempFlag.NOT_ENOUGH_BASELINE
    BaselineTile(
        Glyph.Target, "SKIN TEMPERATURE", deviation?.signedDeviation(),
        when {
            building && deviation != null -> "ESTIMATE"
            building -> "${skin?.baselineNights?.coerceAtMost(7)} OF 7 NIGHTS"
            deviation != null -> "0.0"
            else -> null
        },
        change(deviation, 0.0, null, 0.1), unit = "SD",
    ) { onOpenMetric(MetricKind.SKIN_TEMPERATURE) }

    val asleep = summary.sleep.value?.timeAsleep?.toMinutes()?.toDouble()
    val asleepUsual = usual { it.sleep.value?.timeAsleep?.toMinutes()?.toDouble() }
    BaselineTile(
        Glyph.Moon, "HOURS OF SLEEP", asleep?.let { hoursMinutes(it.toInt()) }, asleepUsual?.let { hoursMinutes(it.toInt()) },
        change(asleep, asleepUsual, true, 1.0),
    ) { onOpenMetric(MetricKind.SLEEP_DURATION) }

    val calories = summary.calories.value?.toDouble()
    val caloriesUsual = usual { it.calories.value?.toDouble() }
    BaselineTile(
        Glyph.Flame, "CALORIES", summary.calories.value?.grouped(), caloriesUsual?.toInt()?.grouped(),
        change(calories, caloriesUsual, null, 1.0), unit = "KCAL",
    ) { onOpenMetric(MetricKind.CALORIES) }

    val steps = summary.steps.count.value?.toDouble()
    val stepsUsual = usual { it.steps.count.value?.toDouble() }
    BaselineTile(
        Glyph.Steps, "STEPS", summary.steps.count.value?.grouped(), stepsUsual?.toInt()?.grouped(),
        change(steps, stepsUsual, true, 1.0),
    ) { onOpenMetric(MetricKind.STEPS) }
    BaselineLegend(state.earlier.size, state.isToday)
}
