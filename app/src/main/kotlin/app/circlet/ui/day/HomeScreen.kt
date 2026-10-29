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
import app.circlet.ui.design.unscored
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
                when (val day = state.day) {
                    is Loadable.Failed -> ErrorState(day.message, onRetry)
                    else -> state.notice?.let { DayNotice(it) { onNotice(it) } }
                }
                HeartCard(state, summary, loading)
                PlanCard(state, summary, loading)
                MyDayCard(
                    summary, loading, onOpen, onOpenActivity, onOpenAllActivities,
                    onStartWorkout = onStartWorkout.takeIf { state.isToday && live == null },
                )
                KeyStatistics(state, summary, loading, onOpenMetric)
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
    Card("HEART RATE") {
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

@Composable
private fun PlanCard(state: DayUiState, summary: DaySummary?, loading: Boolean) {
    Card(if (state.isToday) "TODAY'S PLAN" else "PLAN FOR THE DAY") {
        val target = summary?.targetStrain?.score
        val need = summary?.sleepNeed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Figure(
                target?.value?.decimal(), "STRAIN TARGET", Palette.Strain,
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
) {
    val format = LocalTimeFormat.current
    Card("MY DAY") {
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
            GhostRow(Glyph.Moon, "SLEEP", reason?.words() ?: "Picked up on its own overnight")
        }
        summary?.naps?.forEach { nap ->
            Divider()
            TileRow(nap.timeAsleep.hoursMinutes(), Palette.Sleep, "NAP", format.range(nap.start, nap.end)) { onOpen(DayScreen.SLEEP) }
        }
        Divider()
        val activities = summary?.activities.orEmpty()
        if (activities.isEmpty()) GhostRow(Glyph.Bolt, "ACTIVITY", "Logged after ten minutes of raised heart rate")
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
private fun KeyStatistics(state: DayUiState, summary: DaySummary?, loading: Boolean, onOpenMetric: (MetricKind) -> Unit) {
    Card("KEY STATISTICS") {
        if (summary == null) {
            for ((i, name) in listOf("HRV", "RESTING HEART RATE", "RESPIRATORY RATE", "HOURS OF SLEEP", "CALORIES", "STEPS").withIndex()) {
                if (i > 0) Divider()
                StatRow(name, null, loading = loading)
            }
            return@Card
        }
        val week = state.week
        val hrv = average(week.mapNotNull { it.heart.hrv.rmssdMs.value })
        val resting = average(week.mapNotNull { it.heart.resting.bpm.value?.toDouble() })
        val breaths = average(week.mapNotNull { it.heart.respiratoryRate.breathsPerMinute.value })
        val asleep = average(week.mapNotNull { it.sleep.value?.timeAsleep?.toMinutes()?.toDouble() })
        val refs = summary.references
        MetricRow(
            "HRV", summary.heart.hrv.rmssdMs, "MS", note = hrv?.let { "7-day avg ${it.decimal(0)}" },
            tag = positionTag(summary.heart.hrv.rmssdMs.value, refs.forKind(MetricKind.HRV)?.personal),
            onClick = { onOpenMetric(MetricKind.HRV) },
        ) { it.decimal(0) }
        Divider()
        MetricRow(
            "RESTING HEART RATE", summary.heart.resting.bpm, "BPM", note = resting?.let { "7-day avg ${it.decimal(0)}" },
            tag = positionTag(summary.heart.resting.bpm.value?.toDouble(), refs.forKind(MetricKind.RESTING_HEART_RATE)?.personal),
            onClick = { onOpenMetric(MetricKind.RESTING_HEART_RATE) },
        ) { it.toString() }
        Divider()
        MetricRow(
            "RESPIRATORY RATE", summary.heart.respiratoryRate.breathsPerMinute, "RPM", note = breaths?.let { "7-day avg ${it.decimal()}" },
            tag = positionTag(summary.heart.respiratoryRate.breathsPerMinute.value?.toDouble(), refs.forKind(MetricKind.RESPIRATORY_RATE)?.personal),
            onClick = { onOpenMetric(MetricKind.RESPIRATORY_RATE) },
        ) { it.decimal() }
        Divider()
        val skin = summary.skinTemperature
        val scored = skin != null && !skin.unscored && skin.flag != SkinTempFlag.NOT_ENOUGH_BASELINE
        val deviation = if (scored) skin?.deviation?.value else null
        StatRow(
            "SKIN TEMPERATURE", deviation?.signedDeviation(), "SD",
            note = when {
                skin == null -> "Current temperature unavailable from this strap"
                skin.unscored -> "Not scored yet for WHOOP 5.0 / MG"
                !scored -> "Building your baseline, ${skin.baselineNights} nights so far"
                else -> "Deviation from your own nights"
            },
            absent = if (scored && deviation == null) skin?.deviation?.absentReason ?: AbsentReason.NOT_ENOUGH_DATA else null,
            onClick = { onOpenMetric(MetricKind.SKIN_TEMPERATURE) },
        )
        Divider()
        MetricRow("HOURS OF SLEEP", summary.sleep, note = asleep?.let { "7-day avg ${hoursMinutes(it.toInt())}" }, onClick = { onOpenMetric(MetricKind.SLEEP_DURATION) }) { it.timeAsleep.hoursMinutes() }
        Divider()
        MetricRow("CALORIES", summary.calories, "KCAL", onClick = { onOpenMetric(MetricKind.CALORIES) }) { it.grouped() }
        Divider()
        MetricRow("STEPS", summary.steps.count, onClick = { onOpenMetric(MetricKind.STEPS) }) { it.grouped() }
    }
}
