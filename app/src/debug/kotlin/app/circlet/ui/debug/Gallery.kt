package app.circlet.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.AbsentReason
import app.circlet.core.Confidence
import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.LinkState
import app.circlet.core.MassUnit
import app.circlet.core.OnboardingChecks
import app.circlet.core.StrapError
import app.circlet.core.StrapState
import app.circlet.core.SyncProgress
import app.circlet.core.SystemTimeSource
import app.circlet.core.UserSettings
import app.circlet.core.ClockStyle
import app.circlet.core.DoubleTapAction
import app.circlet.core.WellnessFlag
import app.circlet.core.WellnessKind
import app.circlet.core.fake.FakeCirclet
import app.circlet.core.fake.FakeScenario
import app.circlet.ui.day.DayNotice
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.HomeScreen
import app.circlet.ui.day.RecoveryScreen
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.day.SleepScreen
import app.circlet.ui.day.StrainScreen
import app.circlet.ui.design.BatteryGlyph
import app.circlet.ui.design.Card
import app.circlet.ui.design.Chip
import app.circlet.ui.design.DivergingBar
import app.circlet.ui.design.Divider
import app.circlet.ui.design.EmptyState
import app.circlet.ui.design.ErrorState
import app.circlet.ui.design.Field
import app.circlet.ui.design.Figure
import app.circlet.ui.design.GhostRow
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.HeartRateChart
import app.circlet.ui.design.HeroDial
import app.circlet.ui.design.Hypnogram
import app.circlet.ui.design.Icon
import app.circlet.ui.design.ListRow
import app.circlet.ui.design.LiveDot
import app.circlet.ui.design.MessageBar
import app.circlet.ui.design.MetricDial
import app.circlet.ui.design.MonthCell
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.ProgressSteps
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.Sheet
import app.circlet.ui.design.Skeleton
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.Sparkline
import app.circlet.ui.design.StatRow
import app.circlet.ui.design.StressTimeline
import app.circlet.ui.design.Sweep
import app.circlet.ui.design.Tag
import app.circlet.ui.design.TextEntry
import app.circlet.ui.design.TileRow
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.WeekBars
import app.circlet.ui.design.ZoneBars
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.narrowDay
import app.circlet.ui.design.number
import app.circlet.ui.design.title
import app.circlet.ui.design.words
import app.circlet.ui.more.MoreActions
import app.circlet.ui.more.MoreScreen
import app.circlet.ui.more.ProfileDraft
import app.circlet.ui.onboarding.AccessPage
import app.circlet.ui.onboarding.PairingPage
import app.circlet.ui.onboarding.ProfilePage
import app.circlet.ui.onboarding.ReadyPage
import app.circlet.ui.onboarding.WelcomePage
import app.circlet.ui.state.DayUiState
import app.circlet.ui.state.Loadable
import app.circlet.ui.state.Notice
import app.circlet.ui.state.PairingPhase
import app.circlet.ui.state.StrapAction
import app.circlet.ui.state.dayUiState
import app.circlet.ui.state.today
import app.circlet.ui.state.valueOrNull
import app.circlet.ui.strap.Access
import kotlinx.coroutines.flow.flowOf

private class Entry(val group: String, val name: String, val content: @Composable () -> Unit)

/** The day state the fakes produce for [scenario], today. */
@Composable
private fun demoDay(scenario: FakeScenario): DayUiState {
    val fake = remember(scenario) { FakeCirclet(SystemTimeSource, scenario) }
    val today = remember { SystemTimeSource.today() }
    val flow = remember(fake) { dayUiState(fake, fake, SystemTimeSource, flowOf(today)) }
    val initial = remember(fake, today) { DayUiState(today, today, Loadable.Loading, emptyList(), fake.state.value) }
    val state by flow.collectAsStateWithLifecycle(initial)
    return state
}

private val someAccess = Access(nearby = true, notifications = false, battery = false, {}, {}, {})
private val fullAccess = Access(nearby = true, notifications = true, battery = true, {}, {}, {})
private val noAccess = Access(nearby = false, notifications = false, battery = false, {}, {}, {})
private val straps = listOf(FoundStrap("C0:FF:EE:00:00:01", "WHOOP 4A0934182", -58), FoundStrap("C0:FF:EE:00:00:02", "WHOOP 4C0211740", -77))
private val settings = UserSettings(MassUnit.KILOGRAM, ClockStyle.MATCH_SYSTEM, DoubleTapAction.STOP_ALARM, heartRateBroadcast = true)
private val checks = OnboardingChecks(true, true, false, false, true, true, true, true)

@Composable
private fun Home(state: DayUiState, refreshing: Boolean = false) = HomeScreen(state, refreshing, {}, {}, {}, {}, {}, {}, {})

private fun DayUiState.loading() = copy(day = Loadable.Loading, history = emptyList())

private fun DayUiState.failed() = copy(day = Loadable.Failed("Circlet could not read this day from its storage."), history = emptyList())

private fun DayUiState.yesterday() = copy(date = today.minusDays(1), history = history.dropLast(1), day = Loadable.Ready(history[history.size - 2]))

private val entries = listOf(
    Entry("Home", "Populated") { Home(demoDay(FakeScenario.PAIRED)) },
    Entry("Home", "Earlier day") { Home(demoDay(FakeScenario.PAIRED).let { if (it.history.size > 1) it.yesterday() else it }) },
    Entry("Home", "Calibrating") { Home(demoDay(FakeScenario.CALIBRATING)) },
    Entry("Home", "No data today") { Home(demoDay(FakeScenario.NO_DATA_TODAY)) },
    Entry("Home", "Unpaired, empty") { Home(demoDay(FakeScenario.UNPAIRED)) },
    Entry("Home", "Strap out of range") {
        Home(demoDay(FakeScenario.PAIRED).let { it.copy(strap = it.strap.copy(link = LinkState.RECONNECTING, liveHeartRateBpm = null)) })
    },
    Entry("Home", "Catching up on history") {
        Home(demoDay(FakeScenario.PAIRED).let { it.copy(strap = it.strap.copy(sync = SyncProgress(5_400, draining = true))) }, refreshing = true)
    },
    Entry("Home", "Strap error") {
        Home(demoDay(FakeScenario.PAIRED).let { it.copy(strap = it.strap.copy(error = StrapError.TimedOut)) })
    },
    Entry("Home", "Loading") { Home(demoDay(FakeScenario.PAIRED).loading()) },
    Entry("Home", "Error") { Home(demoDay(FakeScenario.PAIRED).failed()) },

    Entry("Sleep", "Populated") { SleepScreen(demoDay(FakeScenario.PAIRED), {}, {}) },
    Entry("Sleep", "Empty") { SleepScreen(demoDay(FakeScenario.NO_DATA_TODAY), {}, {}) },
    Entry("Sleep", "Never synced") { SleepScreen(demoDay(FakeScenario.UNPAIRED), {}, {}) },
    Entry("Sleep", "Loading") { SleepScreen(demoDay(FakeScenario.PAIRED).loading(), {}, {}) },
    Entry("Sleep", "Error") { SleepScreen(demoDay(FakeScenario.PAIRED).failed(), {}, {}) },

    Entry("Recovery", "Populated") { RecoveryScreen(demoDay(FakeScenario.PAIRED), {}, {}) },
    Entry("Recovery", "Calibrating") { RecoveryScreen(demoDay(FakeScenario.CALIBRATING), {}, {}) },
    Entry("Recovery", "Empty") { RecoveryScreen(demoDay(FakeScenario.NO_DATA_TODAY), {}, {}) },
    Entry("Recovery", "Loading") { RecoveryScreen(demoDay(FakeScenario.PAIRED).loading(), {}, {}) },
    Entry("Recovery", "Error") { RecoveryScreen(demoDay(FakeScenario.PAIRED).failed(), {}, {}) },

    Entry("Strain", "Populated") { StrainScreen(demoDay(FakeScenario.PAIRED), {}, {}) },
    Entry("Strain", "Empty") { StrainScreen(demoDay(FakeScenario.NO_DATA_TODAY), {}, {}) },
    Entry("Strain", "Loading") { StrainScreen(demoDay(FakeScenario.PAIRED).loading(), {}, {}) },
    Entry("Strain", "Error") { StrainScreen(demoDay(FakeScenario.PAIRED).failed(), {}, {}) },

    Entry("Onboarding", "Welcome") { WelcomePage {} },
    Entry("Onboarding", "Profile") { ProfilePage(remember { ProfileDraft("31", "168", "64", "", app.circlet.core.Sex.FEMALE) }, MassUnit.KILOGRAM, null) {} },
    Entry("Onboarding", "Profile, invalid") {
        ProfilePage(remember { ProfileDraft("7", "168", "", "", app.circlet.core.Sex.UNSPECIFIED) }, MassUnit.KILOGRAM, "Age must be between 1 and 120.") {}
    },
    Entry("Onboarding", "Permissions, none") { AccessPage(noAccess, {}, {}) },
    Entry("Onboarding", "Permissions, partly") { AccessPage(someAccess, {}, {}) },
    Entry("Onboarding", "Permissions, all") { AccessPage(fullAccess, {}, {}) },
    Entry("Onboarding", "Pairing, searching") { PairingPage(PairingPhase(), true, emptyList(), {}, {}, {}, {}) },
    Entry("Onboarding", "Pairing, straps found") { PairingPage(PairingPhase(), true, straps, {}, {}, {}, {}) },
    Entry("Onboarding", "Pairing, none found") { PairingPage(PairingPhase(), false, emptyList(), {}, {}, {}, {}) },
    Entry("Onboarding", "Pairing, connecting") { PairingPage(PairingPhase(connecting = straps[0]), false, straps, {}, {}, {}, {}) },
    Entry("Onboarding", "Pairing, bond refused") {
        PairingPage(PairingPhase(error = CoreError.Strap(StrapError.BondFailed)), false, straps, {}, {}, {}, {})
    },
    Entry("Onboarding", "Pairing, no permission") {
        PairingPage(PairingPhase(error = CoreError.Strap(StrapError.PermissionMissing)), false, emptyList(), {}, {}, {}, {})
    },
    Entry("Onboarding", "Pairing, timed out") {
        PairingPage(PairingPhase(error = CoreError.Strap(StrapError.TimedOut)), false, straps, {}, {}, {}, {})
    },
    Entry("Onboarding", "Connected, first sync") {
        ReadyPage(StrapState(LinkState.CONNECTED, "WHOOP 4A0934182", sync = SyncProgress(9_000, draining = true))) {}
    },
    Entry("Onboarding", "Connected, synced") { ReadyPage(demoDay(FakeScenario.PAIRED).strap) {} },

    Entry("More", "Connected") { MoreScreen(demoDay(FakeScenario.PAIRED).strap, settings, checks, someAccess, null, MoreActions(gallery = {})) },
    Entry("More", "Syncing") {
        MoreScreen(
            demoDay(FakeScenario.PAIRED).strap.copy(sync = SyncProgress(5_400, draining = true), charging = true),
            settings, checks, fullAccess, StrapAction.SYNC, MoreActions(),
        )
    },
    Entry("More", "Out of range, with error") {
        MoreScreen(
            demoDay(FakeScenario.PAIRED).strap.copy(link = LinkState.RECONNECTING, error = StrapError.LinkLost),
            settings, checks, fullAccess, null, MoreActions(),
        )
    },
    Entry("More", "Unpaired") { MoreScreen(StrapState(), settings, checks.copy(companionDeviceAssociated = false), noAccess, null, MoreActions()) },
)

/** Every component and every screen state, drawn from the fakes. Debug builds only. */
@Composable
fun Gallery(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val shown = open
    if (shown != null) {
        BackHandler { open = null }
        entries[shown].content()
        return
    }
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Screen gallery", onBack = onBack)
        Segmented(listOf("Screens", "Components", "Charts", "Tokens"), section, Modifier.padding(horizontal = 16.dp)) { section = it }
        Spacer(Modifier.height(12.dp))
        ScreenColumn {
            when (section) {
                0 -> for (group in entries.map { it.group }.distinct()) {
                    Card(group) {
                        entries.withIndex().filter { it.value.group == group }.forEachIndexed { i, entry ->
                            if (i > 0) Divider()
                            ListRow(entry.value.name, onClick = { open = entry.index })
                        }
                    }
                }
                1 -> Components()
                2 -> Charts()
                else -> Tokens()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tokens() {
    Card("Colour") {
        val swatches = listOf(
            "BgTop" to Palette.BgTop, "BgBottom" to Palette.BgBottom, "Card" to Palette.Card, "CardRaised" to Palette.CardRaised,
            "CardLine" to Palette.CardLine, "White" to Palette.White, "Dim" to Palette.Dim, "Faint" to Palette.Faint, "Ghost" to Palette.Ghost,
            "Sleep" to Palette.Sleep, "RecoveryHigh" to Palette.RecoveryHigh, "RecoveryMid" to Palette.RecoveryMid,
            "RecoveryLow" to Palette.RecoveryLow, "Strain" to Palette.Strain, "Teal" to Palette.Teal, "Danger" to Palette.Danger,
            "Awake" to Palette.Awake, "Light" to Palette.Light, "Deep" to Palette.Deep, "Rem" to Palette.Rem,
        ) + Palette.Zones.mapIndexed { i, colour -> "Zone $i" to colour }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, colour) in swatches) Swatch(name, colour)
        }
    }
    Card("Type") {
        Text("Title 30", style = title())
        Text("Title 18", style = title(18.sp))
        Spacer(Modifier.height(8.dp))
        Text("Body 15. Circlet reads your strap on your own phone.", style = body())
        Text("Body 13 faint. Stored on this phone only.", style = body(13.sp, Palette.Faint))
        Spacer(Modifier.height(8.dp))
        Text("Label 12", style = label())
        Text("Label 10 faint", style = label(10.sp, Palette.Faint))
        Spacer(Modifier.height(8.dp))
        Text("0123456789", style = number(34.sp))
        Text("1111111111", style = number(34.sp))
    }
    Card("Icons") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (glyph in Glyph.entries) {
                Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(glyph, size = 28.dp)
                    Text(glyph.name, style = body(10.sp, Palette.Faint), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, colour: Color) {
    Column(Modifier.width(72.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(Shapes.tile)
                .background(colour),
        )
        Text(name, style = body(10.sp, Palette.Faint), maxLines = 1)
    }
}

@Composable
private fun Components() {
    var sheet by remember { mutableStateOf(false) }
    Card("Dials") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricDial("Sleep", Glyph.Moon, Palette.Sleep, "81", "%", 0.81f) {}
            MetricDial("Recovery", Glyph.Pulse, Palette.RecoveryMid, "52", "%", 0.52f) {}
            MetricDial("Strain", Glyph.Bolt, Palette.Strain, "12.4", "", 0.59f) {}
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricDial("Absent", Glyph.Moon, Palette.Sleep, null, "%", null) {}
            MetricDial("Loading", Glyph.Pulse, Palette.Sleep, null, "%", null, loading = true) {}
            MetricDial("Low", Glyph.Pulse, Palette.RecoveryLow, "21", "%", 0.21f) {}
        }
        HeroDial("Day strain", Glyph.Bolt, Palette.Strain, "12.4", "", 0.59f, "Hero dial with a target notch.", marker = 0.72f)
    }
    Card("Stat rows") {
        StatRow("HRV", "52", "MS", note = "7-day avg 49")
        Divider()
        StatRow("Steps", "8,412", tag = "Estimate")
        Divider()
        StatRow("Loading", null, loading = true)
        for (reason in AbsentReason.entries) {
            Divider()
            StatRow(reason.name.replace('_', ' '), null, "MS", absent = reason)
        }
        Divider()
        Tag("Estimate")
    }
    Card("Figures") {
        Row(Modifier.fillMaxWidth()) {
            Figure("6:16", "Hours of sleep", modifier = Modifier.weight(1f))
            Figure(null, "Sleep needed", Palette.Sleep, absent = AbsentReason.CALIBRATING, alignEnd = true, modifier = Modifier.weight(1f))
        }
    }
    Card("Buttons") {
        PrimaryButton("Primary") { sheet = true }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Loading", loading = true) {}
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Disabled", enabled = false) {}
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Danger", danger = true) {}
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Secondary", glyph = Glyph.Vibrate, modifier = Modifier.weight(1f)) {}
            SecondaryButton("Disabled", enabled = false, modifier = Modifier.weight(1f)) {}
        }
        QuietButton("Quiet") {}
        QuietButton("Quiet danger", Palette.Danger) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("Allow") {}
            SmallButton("Disabled", enabled = false) {}
        }
    }
    Card("Controls") {
        var segment by remember { mutableIntStateOf(0) }
        var chip by remember { mutableStateOf(true) }
        var toggle by remember { mutableStateOf(true) }
        var text by remember { mutableStateOf("72") }
        var name by remember { mutableStateOf("") }
        Segmented(listOf("Week", "Month", "Year"), segment) { segment = it }
        Spacer(Modifier.height(8.dp))
        Segmented(listOf("Disabled", "Control"), 0, enabled = false) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Caffeine", chip) { chip = !chip }
            Chip("Alcohol", false) {}
            Chip("Run", true, glyph = Glyph.Run) {}
            Chip("Off", false, enabled = false) {}
        }
        ListRow("Toggle", Glyph.Broadcast, "A row with a switch") { Toggle(toggle, "Toggle") { toggle = it } }
        ListRow("Disabled toggle", enabled = false) { Toggle(false, "Disabled toggle", enabled = false) {} }
        Divider()
        Field("Field", text, "Hint under the name") { text = it }
        Field("Field in error", "9", "Enter 120 to 230", isError = true) {}
        Field("Field disabled", "64", enabled = false) {}
        TextEntry(name, "Name", placeholder = "Text entry") { name = it }
    }
    Card("Rows") {
        TileRow("6:16", Palette.Sleep, "Sleep", "11:45 PM – 6:24 AM") {}
        Divider()
        TileRow("9.8", Palette.Strain, "Running", "6:05 PM – 6:39 PM", glyph = Glyph.Run, detail = "Avg 152 · max 171 bpm · 310 kcal")
        Divider()
        TileRow(null, Palette.Strain, "Cycling", "7:10 AM – 7:40 AM", glyph = Glyph.Cycle)
        Divider()
        GhostRow(Glyph.Bolt, "Activity", "Logged after ten minutes of raised heart rate")
        Divider()
        ListRow("List row", Glyph.Person, "With a line of detail", onClick = {})
        Divider()
        ListRow("List row with a value", Glyph.Ruler, value = "KG", onClick = {})
    }
    Card("Progress") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveDot(Palette.Teal, true)
            LiveDot(Palette.Faint, false)
            Skeleton(Modifier.size(width = 120.dp, height = 14.dp))
        }
        Spacer(Modifier.height(12.dp))
        Sweep(Palette.Teal)
        Spacer(Modifier.height(12.dp))
        ProgressSteps(2, 4, Palette.Teal)
    }
    val notices = listOf(
        Notice.NeedsPermission, Notice.Unpaired, Notice.BluetoothOff, Notice.Searching, Notice.StrapTrouble(StrapError.BondFailed),
        Notice.CatchingUp(92), Notice.FirstNight, Notice.Calibrating(2, 14 - 2),
        Notice.Wellness(WellnessFlag(WellnessKind.ILLNESS, Confidence.LOW, "Resting heart rate is up and HRV is down")),
        Notice.Wellness(WellnessFlag(WellnessKind.OVERREACHING, Confidence.MEDIUM, "Recovery is low after a hard day")),
        Notice.EmptyDay(today = true, AbsentReason.STRAP_NOT_SYNCED), Notice.EmptyDay(today = false, AbsentReason.NOT_WORN),
    )
    for (notice in notices) DayNotice(notice) {}
    Card("Empty state") { EmptyState(Glyph.Moon, "No stages yet", "Awake, light, deep and REM draw here after a night with the strap on.") }
    ErrorState("Circlet could not read this day from its storage.") {}
    MessageBar("A message bar, shown above the navigation bar.")
    if (sheet) {
        Sheet("A sheet", onDismiss = { sheet = false }) {
            Text("One short task or a confirmation.", style = body(14.sp))
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Done") { sheet = false }
        }
    }
}

@Composable
private fun Charts() {
    val state = demoDay(FakeScenario.PAIRED)
    val day = state.day.valueOrNull
    val week = state.week
    Card("Heart rate") {
        HeartRateChart(day?.heartRateByMinute.orEmpty(), Palette.Strain, "")
        Spacer(Modifier.height(16.dp))
        HeartRateChart(emptyList(), Palette.Strain, "Your day's heart rate draws here as the strap syncs")
        Spacer(Modifier.height(16.dp))
        HeartRateChart(emptyList(), Palette.Strain, "", loading = true)
    }
    day?.sleep?.value?.let { Card("Hypnogram") { Hypnogram(it) } }
    Card("Week bars") {
        WeekBars(
            week.map { it.recovery.score.value?.toFloat() }, week.map { it.date.narrowDay() }, 100f,
            week.map { Palette.recovery(it.recovery.score.value) }, week.map { it.recovery.score.value?.let { v -> "$v%" } ?: "" },
        )
        Spacer(Modifier.height(16.dp))
        WeekBars(List(7) { null }, week.map { it.date.narrowDay() }.ifEmpty { List(7) { "" } }.take(7), 100f, List(7) { Palette.Sleep }, List(7) { "" })
    }
    Card("Zone bars") {
        ZoneBars(day?.strain?.zoneMinutes)
        Divider()
        ZoneBars(null)
    }
    Card("Stress timeline") {
        val start = state.date.atStartOfDay(SystemTimeSource.zone()).toInstant()
        StressTimeline(day?.stress?.timeline.orEmpty(), start, "")
        Spacer(Modifier.height(16.dp))
        StressTimeline(emptyList(), start, "Stress draws here once the strap has synced")
    }
    Card("Month cells") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonthCell(6, 0.82f, Palette.RecoveryHigh) {}
            MonthCell(7, 0.48f, Palette.RecoveryMid) {}
            MonthCell(8, 0.2f, Palette.RecoveryLow, selected = true) {}
            MonthCell(9, null, Palette.Sleep) {}
            MonthCell(10, 0.6f, Palette.RecoveryMid, today = true) {}
            MonthCell(11, null, Palette.Sleep, enabled = false) {}
        }
    }
    Card("Sparklines and bars") {
        val hrv = state.history.map { it.heart.hrv.rmssdMs.value?.toFloat() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Sparkline(hrv, Palette.Teal, Modifier.size(width = 96.dp, height = 36.dp))
            Sparkline(hrv.mapIndexed { i, v -> if (i % 4 == 2) null else v }, Palette.Sleep, Modifier.size(width = 96.dp, height = 36.dp))
            Sparkline(listOf(null, 52f, null), Palette.Sleep, Modifier.size(width = 60.dp, height = 36.dp))
        }
        Spacer(Modifier.height(16.dp))
        DivergingBar(0.7f, Palette.RecoveryHigh, Palette.Danger, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        DivergingBar(-0.4f, Palette.RecoveryHigh, Palette.Danger, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        DivergingBar(null, Palette.RecoveryHigh, Palette.Danger, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BatteryGlyph(76, Palette.White)
            BatteryGlyph(12, Palette.Danger)
            BatteryGlyph(null, Palette.Faint)
        }
    }
}
