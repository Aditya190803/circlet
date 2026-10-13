package app.circlet.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.circlet.AppContainer
import app.circlet.core.ClockStyle
import app.circlet.core.LinkState
import app.circlet.core.MetricKind
import app.circlet.ui.alarm.AlarmsScreen
import app.circlet.ui.day.DayScreen
import app.circlet.ui.day.HomeScreen
import app.circlet.ui.day.RecoveryScreen
import app.circlet.ui.day.SleepScreen
import app.circlet.ui.day.StrainScreen
import app.circlet.ui.debug.DebugHooks
import app.circlet.ui.design.CircletTheme
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.MessageBar
import app.circlet.ui.design.Motion
import app.circlet.ui.design.Palette
import app.circlet.ui.design.TimeFormat
import app.circlet.ui.design.label
import app.circlet.ui.more.AboutScreen
import app.circlet.ui.more.MoreActions
import app.circlet.ui.more.NotificationsScreen
import app.circlet.ui.more.MoreScreen
import app.circlet.ui.more.PairScreen
import app.circlet.ui.more.ProfileScreen
import app.circlet.ui.more.UnitsScreen
import app.circlet.ui.more.ZonesScreen
import app.circlet.ui.more.rememberNotice
import app.circlet.ui.journal.JournalInsightsScreen
import app.circlet.ui.journal.JournalScreen
import app.circlet.ui.onboarding.OnboardingFlow
import app.circlet.ui.portability.DataScreen
import app.circlet.ui.more.BreatheScreen
import app.circlet.ui.portability.ImportScreen
import app.circlet.ui.stress.StressScreen
import app.circlet.ui.trends.MetricTrendScreen
import app.circlet.ui.trends.RecordsScreen
import app.circlet.ui.trends.TrendsScreen
import app.circlet.ui.trends.WeeklyRecapScreen
import app.circlet.ui.workout.ActivityDetailScreen
import app.circlet.ui.workout.LiveWorkoutScreen
import app.circlet.ui.workout.StartWorkoutScreen
import app.circlet.ui.workout.WorkoutsScreen
import app.circlet.ui.state.DayViewModel
import app.circlet.ui.state.MoreViewModel
import app.circlet.ui.state.Notice
import app.circlet.ui.state.PairingViewModel
import app.circlet.ui.state.containerViewModel
import app.circlet.ui.strap.PairingFix
import app.circlet.ui.strap.rememberAccess
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/** The five destinations on the bottom bar. */
enum class Tab(val route: String, val title: String, val glyph: Glyph) {
    HOME("home", "HOME", Glyph.Home),
    SLEEP("sleep", "SLEEP", Glyph.Moon),
    RECOVERY("recovery", "RECOVERY", Glyph.Pulse),
    STRAIN("strain", "STRAIN", Glyph.Bolt),
    MORE("more", "MORE", Glyph.More),
}

/**
 * Screens pushed on top of a tab.
 *
 * Route table:
 * - more/profile, more/units, more/about, more/pair, more/data, more/import, more/zones: More rows and setup screens
 * - trends: TrendsScreen. trends/metric/{kind}: MetricTrendScreen. trends/recap: WeeklyRecapScreen. trends/records: RecordsScreen
 * - alarms: AlarmsScreen
 * - stress/{date}: StressScreen
 * - journal/{date}: JournalScreen. insights/journal: JournalInsightsScreen
 * - workouts/{date}: WorkoutsScreen. workout/{id}/{date}: ActivityDetailScreen
 * - workout/start: StartWorkoutScreen. workout/live: LiveWorkoutScreen
 * - debug/gallery: debug builds only
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val PROFILE = "more/profile"
    const val UNITS = "more/units"
    const val ABOUT = "more/about"
    const val PAIR = "more/pair"
    const val DATA = "more/data"
    const val IMPORT = "more/import"
    const val BREATHE = "more/breathe"
    const val NOTIFICATIONS = "more/notifications"
    const val ZONES = "more/zones"
    const val TRENDS = "trends"
    const val RECAP = "trends/recap"
    const val RECORDS = "trends/records"
    const val ALARMS = "alarms"
    const val JOURNAL_INSIGHTS = "insights/journal"
    const val START_WORKOUT = "workout/start"
    const val LIVE_WORKOUT = "workout/live"
    const val GALLERY = "debug/gallery"

    fun trend(kind: MetricKind) = "trends/metric/${kind.name}"
    fun stress(date: LocalDate) = "stress/$date"
    fun journal(date: LocalDate) = "journal/$date"
    fun workouts(date: LocalDate) = "workouts/$date"
    fun workout(id: String, date: LocalDate) = "workout/$id/$date"
}

private fun stringArg(name: String) = navArgument(name) { type = NavType.StringType }

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

private fun NavBackStackEntry.date(name: String): LocalDate = LocalDate.parse(arg(name))

/** Shows one short line above the navigation bar. Screens reach it through [LocalMessenger]. */
class Messenger {
    var text by mutableStateOf<String?>(null)
        private set
    var shown by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        text = message
        shown++
    }

    fun clear() {
        text = null
    }
}

val LocalMessenger = staticCompositionLocalOf { Messenger() }

@Composable
fun CircletRoot() {
    val context = LocalContext.current
    val container = remember { AppContainer.get(context) }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val timeFormat = remember(settings.clock) {
        TimeFormat(
            container.time.zone(),
            when (settings.clock) {
                ClockStyle.MATCH_SYSTEM -> DateFormat.is24HourFormat(context)
                ClockStyle.H12 -> false
                ClockStyle.H24 -> true
            },
        )
    }
    val messenger = remember { Messenger() }
    CircletTheme(timeFormat) {
        CompositionLocalProvider(LocalMessenger provides messenger) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.Background),
            ) { Shell(container, messenger) }
        }
    }
}

private fun NavHostController.select(tab: Tab) {
    navigate(tab.route) {
        popUpTo(Tab.HOME.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun Shell(container: AppContainer, messenger: Messenger) {
    val context = LocalContext.current
    val liveWorkout by container.workouts.live.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val start = remember { if (container.onboarding.completed.value) Tab.HOME.route else Routes.ONBOARDING }
    val entry by nav.currentBackStackEntryAsState()
    val tab = Tab.entries.find { it.route == entry?.destination?.route }
    val access = rememberAccess()

    // Scoped to the activity so Home and the three detail tabs share one selected date.
    val day = containerViewModel { c, saved -> DayViewModel(c.metrics, c.strap, c.time, saved) }
    val state by day.state.collectAsStateWithLifecycle()
    val refreshing by day.refreshing.collectAsStateWithLifecycle()
    LaunchedEffect(day) { day.messages.collect(messenger::show) }
    val inApp = entry != null && entry?.destination?.route != Routes.ONBOARDING
    LifecycleStartEffect(inApp) {
        if (inApp) day.setLive(true)
        onStopOrDispose { if (inApp) day.setLive(false) }
    }
    LaunchedEffect(messenger.shown) {
        if (messenger.text != null) {
            delay(3_500)
            messenger.clear()
        }
    }
    val fixPairing: (PairingFix) -> Unit = { fix ->
        when (fix) {
            PairingFix.ALLOW_NEARBY -> access.requestNearby()
            PairingFix.BLUETOOTH_SETTINGS -> runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .then(if (tab == null) Modifier.navigationBarsPadding() else Modifier),
        ) {
            NavHost(
                nav,
                startDestination = start,
                modifier = Modifier.weight(1f),
                enterTransition = { fadeIn(tween(Motion.ENTER)) + slideInVertically(tween(Motion.RISE)) { it / 24 } },
                exitTransition = { fadeOut(tween(Motion.EXIT)) },
                popEnterTransition = { fadeIn(tween(Motion.ENTER)) },
                popExitTransition = { fadeOut(tween(Motion.EXIT)) + slideOutDown() },
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingFlow {
                        nav.navigate(Tab.HOME.route) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                }
                composable(Tab.HOME.route) {
                    HomeScreen(
                        state, refreshing,
                        onRefresh = day::sync,
                        onShift = day::shift,
                        onOpen = { screen ->
                            nav.select(
                                when (screen) {
                                    DayScreen.SLEEP -> Tab.SLEEP
                                    DayScreen.RECOVERY -> Tab.RECOVERY
                                    DayScreen.STRAIN -> Tab.STRAIN
                                },
                            )
                        },
                        onNotice = { notice ->
                            when (notice) {
                                Notice.NeedsPermission -> access.requestNearby()
                                Notice.Unpaired -> nav.navigate(Routes.PAIR)
                                else -> nav.select(Tab.MORE)
                            }
                        },
                        onRetry = day::retry,
                        onProfile = { nav.navigate(Routes.PROFILE) },
                        onStrap = { nav.select(Tab.MORE) },
                        onOpenActivity = { id -> nav.navigate(Routes.workout(id, state.date)) },
                        onOpenAllActivities = { nav.navigate(Routes.workouts(state.date)) },
                        live = liveWorkout,
                        onStartWorkout = { nav.navigate(Routes.START_WORKOUT) },
                        onOpenLive = { nav.navigate(Routes.LIVE_WORKOUT) },
                        onOpenMetric = { kind -> nav.navigate(Routes.trend(kind)) },
                    )
                }
                composable(Tab.SLEEP.route) { SleepScreen(state, onBack = { nav.popBackStack() }, onRetry = day::retry) }
                composable(Tab.RECOVERY.route) { RecoveryScreen(state, onBack = { nav.popBackStack() }, onRetry = day::retry) }
                composable(Tab.STRAIN.route) {
                    StrainScreen(
                        state,
                        onBack = { nav.popBackStack() },
                        onRetry = day::retry,
                        onOpenActivity = { id -> nav.navigate(Routes.workout(id, state.date)) },
                    )
                }
                composable(Tab.MORE.route) {
                    val more = moreViewModel(messenger)
                    val strap by more.strapState.collectAsStateWithLifecycle()
                    val settings by more.settings.collectAsStateWithLifecycle()
                    val checks by more.checks.collectAsStateWithLifecycle()
                    val busy by more.busy.collectAsStateWithLifecycle()
                    MoreScreen(
                        strap, settings, checks, access, busy,
                        MoreActions(
                            sync = more::sync,
                            buzz = more::buzz,
                            find = more::find,
                            rename = more::rename,
                            forget = more::forget,
                            setBroadcast = more::setBroadcast,
                            setDoubleTap = { more.update(settings.copy(doubleTap = it)) },
                            pair = { nav.navigate(Routes.PAIR) },
                            profile = { nav.navigate(Routes.PROFILE) },
                            units = { nav.navigate(Routes.UNITS) },
                            notifications = { nav.navigate(Routes.NOTIFICATIONS) },
                            about = { nav.navigate(Routes.ABOUT) },
                            trends = { nav.navigate(Routes.TRENDS) },
                            journal = { nav.navigate(Routes.journal(state.date)) },
                            alarms = { nav.navigate(Routes.ALARMS) },
                            stress = { nav.navigate(Routes.stress(state.date)) },
                            workouts = { nav.navigate(Routes.workouts(state.date)) },
                            data = { nav.navigate(Routes.DATA) },
                            importHistory = { nav.navigate(Routes.IMPORT) },
                            breathe = { nav.navigate(Routes.BREATHE) },
                            gallery =DebugHooks.gallery?.let { { nav.navigate(Routes.GALLERY) } },
                        ),
                    )
                }
                composable(Routes.PROFILE) {
                    val more = moreViewModel(messenger)
                    val profile by more.profile.collectAsStateWithLifecycle()
                    val settings by more.settings.collectAsStateWithLifecycle()
                    val error by more.profileError.collectAsStateWithLifecycle()
                    ProfileScreen(
                        profile, settings.mass, error, more::saveProfile,
                        onZones = { nav.navigate(Routes.ZONES) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.UNITS) {
                    val more = moreViewModel(messenger)
                    val settings by more.settings.collectAsStateWithLifecycle()
                    UnitsScreen(settings, more::update) { nav.popBackStack() }
                }
                composable(Routes.NOTIFICATIONS) {
                    val more = moreViewModel(messenger)
                    val settings by more.settings.collectAsStateWithLifecycle()
                    NotificationsScreen(settings, more::update) { nav.popBackStack() }
                }
                composable(Routes.ABOUT) {
                    val notice = rememberNotice()
                    val version = remember {
                        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
                    }
                    AboutScreen(version, notice?.getOrNull(), noticeMissing = notice?.isFailure == true) { nav.popBackStack() }
                }
                composable(Routes.PAIR) {
                    val pairing = containerViewModel { c, _ -> PairingViewModel(c.strap) }
                    val strap by pairing.strapState.collectAsStateWithLifecycle()
                    val scanning by pairing.scanning.collectAsStateWithLifecycle()
                    val found by pairing.found.collectAsStateWithLifecycle()
                    val phase by pairing.phase.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { pairing.scan() }
                    DisposableEffect(Unit) { onDispose { pairing.stopScan() } }
                    LaunchedEffect(strap.link) {
                        if (strap.link == LinkState.CONNECTED) {
                            messenger.show("${strap.name ?: "Your strap"} is connected.")
                            nav.popBackStack()
                        }
                    }
                    PairScreen(phase, scanning, found, pairing::scan, pairing::pair, fixPairing) { nav.popBackStack() }
                }
                composable(Routes.DATA) { DataScreen { nav.popBackStack() } }
                composable(Routes.IMPORT) { ImportScreen { nav.popBackStack() } }
                composable(Routes.BREATHE) { BreatheScreen { nav.popBackStack() } }
                composable(Routes.ZONES) { ZonesScreen { nav.popBackStack() } }
                composable(Routes.TRENDS) {
                    TrendsScreen(
                        onBack = { nav.popBackStack() },
                        onOpenMetric = { kind -> nav.navigate(Routes.trend(kind)) },
                        onOpenDay = { date ->
                            val target = minOf(date, state.today)
                            day.shift(ChronoUnit.DAYS.between(state.date, target))
                            nav.select(Tab.HOME)
                        },
                        onOpenRecap = { nav.navigate(Routes.RECAP) },
                        onOpenRecords = { nav.navigate(Routes.RECORDS) },
                    )
                }
                composable(
                    "trends/metric/{kind}",
                    arguments = listOf(stringArg("kind")),
                ) { entry ->
                    MetricTrendScreen(MetricKind.valueOf(entry.arg("kind")), onBack = { nav.popBackStack() })
                }
                composable(Routes.RECAP) { WeeklyRecapScreen { nav.popBackStack() } }
                composable(Routes.RECORDS) { RecordsScreen { nav.popBackStack() } }
                composable(Routes.ALARMS) { AlarmsScreen { nav.popBackStack() } }
                composable("stress/{date}", arguments = listOf(stringArg("date"))) { entry ->
                    StressScreen(entry.date("date")) { nav.popBackStack() }
                }
                composable("journal/{date}", arguments = listOf(stringArg("date"))) { entry ->
                    JournalScreen(
                        entry.date("date"),
                        onBack = { nav.popBackStack() },
                        onOpenInsights = { nav.navigate(Routes.JOURNAL_INSIGHTS) },
                    )
                }
                composable(Routes.JOURNAL_INSIGHTS) { JournalInsightsScreen { nav.popBackStack() } }
                composable("workouts/{date}", arguments = listOf(stringArg("date"))) { entry ->
                    val date = entry.date("date")
                    WorkoutsScreen(
                        date,
                        onBack = { nav.popBackStack() },
                        onOpenActivity = { id -> nav.navigate(Routes.workout(id, date)) },
                        onStartWorkout = {
                            nav.navigate(if (liveWorkout != null) Routes.LIVE_WORKOUT else Routes.START_WORKOUT)
                        },
                    )
                }
                composable(
                    "workout/{id}/{date}",
                    arguments = listOf(stringArg("id"), stringArg("date")),
                ) { entry ->
                    ActivityDetailScreen(entry.arg("id"), entry.date("date")) { nav.popBackStack() }
                }
                composable(Routes.START_WORKOUT) {
                    StartWorkoutScreen(
                        onBack = { nav.popBackStack() },
                        onStarted = {
                            nav.navigate(Routes.LIVE_WORKOUT) { popUpTo(Routes.START_WORKOUT) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.LIVE_WORKOUT) {
                    LiveWorkoutScreen(
                        onBack = { nav.popBackStack() },
                        onFinished = { id ->
                            nav.navigate(Routes.workout(id, state.today)) { popUpTo(Routes.LIVE_WORKOUT) { inclusive = true } }
                        },
                    )
                }
                DebugHooks.gallery?.let { gallery ->
                    composable(Routes.GALLERY) { gallery { nav.popBackStack() } }
                }
            }
            if (tab != null) BottomBar(tab) { nav.select(it) }
        }
        messenger.text?.let {
            MessageBar(
                it,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (tab != null) 68.dp else 0.dp),
            )
        }
    }
}

private fun slideOutDown() = androidx.compose.animation.slideOutVertically(tween(Motion.EXIT)) { it / 24 }

@Composable
private fun moreViewModel(messenger: Messenger): MoreViewModel {
    val more = containerViewModel { c, _ -> MoreViewModel(c.strap, c.profile, c.settings, c.onboarding) }
    LaunchedEffect(more) { more.messages.collect(messenger::show) }
    return more
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.BgBottom)
            .navigationBarsPadding(),
    ) {
        for (tab in Tab.entries) {
            val on = tab == current
            val tint = if (on) Palette.White else Palette.Faint
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .selectable(selected = on, role = Role.Tab) {
                        if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(tab)
                    }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                Icon(tab.glyph, tint, 22.dp)
                Text(tab.title, style = label(10.sp, tint), maxLines = 1)
            }
        }
    }
}
