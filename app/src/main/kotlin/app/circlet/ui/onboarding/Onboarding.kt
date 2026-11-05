package app.circlet.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.circlet.core.FoundStrap
import app.circlet.core.LinkState
import app.circlet.core.MassUnit
import app.circlet.core.StrapState
import app.circlet.ui.design.Card
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.IconButton
import app.circlet.ui.design.LocalTimeFormat
import app.circlet.ui.design.Motion
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.ProgressSteps
import app.circlet.ui.design.QuietButton
import app.circlet.ui.design.Ring
import app.circlet.ui.design.Space
import app.circlet.ui.design.Sweep
import app.circlet.ui.design.body
import app.circlet.ui.design.hoursMinutes
import app.circlet.ui.design.number
import app.circlet.ui.design.title
import app.circlet.ui.more.ProfileDraft
import app.circlet.ui.more.ProfileFields
import app.circlet.ui.more.rememberProfileDraft
import app.circlet.ui.state.OnboardingViewModel
import app.circlet.ui.state.PairingPhase
import app.circlet.ui.state.PairingViewModel
import app.circlet.ui.state.containerViewModel
import app.circlet.ui.strap.Access
import app.circlet.ui.strap.AccessRows
import app.circlet.ui.strap.PairingFix
import app.circlet.ui.strap.StrapFinder
import app.circlet.ui.strap.rememberAccess
import java.time.LocalDate

private const val WELCOME = 0
private const val PROFILE = 1
private const val ACCESS = 2
private const val PAIRING = 3
private const val READY = 4

/** Welcome, profile, permissions, pairing, connected. Pairing can be skipped. */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val onboarding = containerViewModel { c, _ -> OnboardingViewModel(c.onboarding, c.profile, c.settings) }
    val pairing = containerViewModel { c, _ -> PairingViewModel(c.strap) }
    val profile by onboarding.profile.collectAsStateWithLifecycle()
    val settings by onboarding.settings.collectAsStateWithLifecycle()
    val profileError by onboarding.profileError.collectAsStateWithLifecycle()
    val strap by pairing.strapState.collectAsStateWithLifecycle()
    val scanning by pairing.scanning.collectAsStateWithLifecycle()
    val found by pairing.found.collectAsStateWithLifecycle()
    val phase by pairing.phase.collectAsStateWithLifecycle()
    val access = rememberAccess()
    val context = LocalContext.current
    val today = LocalDate.now(LocalTimeFormat.current.zone)
    val draft = rememberProfileDraft(profile, settings.mass, today)

    var step by rememberSaveable { mutableIntStateOf(WELCOME) }
    BackHandler(step in PROFILE..PAIRING) {
        if (step == PAIRING) pairing.stopScan()
        step--
    }
    val finish = {
        pairing.stopScan()
        onboarding.finish()
        onFinished()
    }
    LaunchedEffect(step) { if (step == PAIRING) pairing.scan() }
    LaunchedEffect(step, strap.link) { if (step == PAIRING && strap.link == LinkState.CONNECTED) step = READY }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step in PROFILE..PAIRING) {
                IconButton(Glyph.ChevronLeft, "Back") {
                    if (step == PAIRING) pairing.stopScan()
                    step--
                }
                ProgressSteps(
                    step, 3, Palette.White,
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 56.dp),
                )
            }
        }
        AnimatedContent(
            step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(Motion.STEP)) { it / 6 * dir } + fadeIn(tween(Motion.STEP))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 6 * dir } + fadeOut(tween(200)))
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                WELCOME -> WelcomePage { step = PROFILE }
                PROFILE -> ProfilePage(draft, settings.mass, profileError) {
                    draft.toProfile(profile, settings.mass, today)?.let { onboarding.saveProfile(it) { step = ACCESS } }
                }
                ACCESS -> AccessPage(access, onNext = { step = PAIRING }, onSkip = finish)
                PAIRING -> PairingPage(
                    phase, scanning, found,
                    onScan = pairing::scan,
                    onPair = pairing::pair,
                    onFix = { fix ->
                        when (fix) {
                            PairingFix.ALLOW_NEARBY -> access.requestNearby()
                            PairingFix.BLUETOOTH_SETTINGS -> runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }
                    },
                    onSkip = finish,
                )
                else -> ReadyPage(strap, finish)
            }
        }
    }
}

/** The three dials drawing themselves in, one after another. */
@Composable
private fun Mark(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1800, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(224.dp)) {
        val rings = listOf(Palette.RecoveryHigh to 0.78f, Palette.Strain to 0.62f, Palette.Sleep to 0.86f)
        val w = 11.dp.toPx()
        rings.forEachIndexed { i, (color, target) ->
            val inset = w / 2 + i * (w + 9.dp.toPx())
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            val local = ((progress.value - i * 0.18f) / 0.64f).coerceIn(0f, 1f)
            drawArc(Palette.Track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
            if (local > 0f) {
                drawArc(color, -90f, 360f * target * local, false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Page(heading: String, text: String, footer: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.page),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(heading, style = title())
            Spacer(Modifier.height(10.dp))
            Text(text, style = body(16.sp))
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(start = Space.page, end = Space.page, bottom = 20.dp, top = 8.dp)) { footer() }
    }
}

@Composable
fun WelcomePage(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.page),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Mark() }
        Text("Your strap, read on\nyour own phone.", style = title(34.sp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Circlet reads your strap over Bluetooth and keeps your history on this phone, with an optional backup to your personal Firebase account. Sleep, recovery and strain are worked out on your phone.",
            style = body(16.sp),
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("GET STARTED", onClick = onStart)
        Spacer(Modifier.height(12.dp))
        Text("Setup takes about two minutes", style = body(13.sp, Palette.Faint), modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ProfilePage(draft: ProfileDraft, unit: MassUnit, error: String?, onNext: () -> Unit) {
    val complete = draft.parsedAge != null && draft.parsedHeight != null && draft.parsedWeightKg(unit) != null
    Page(
        "A little about you",
        "These set your heart rate zones and calorie estimates. They stay on this phone, are backed up if you connect Firebase, and you can change them later.",
        footer = { PrimaryButton("CONTINUE", enabled = complete, onClick = onNext) },
    ) {
        Card { ProfileFields(draft, unit, showMaxHeartRate = false) }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, style = body(13.sp, Palette.Warn))
        }
    }
}

@Composable
fun AccessPage(access: Access, onNext: () -> Unit, onSkip: () -> Unit) {
    Page(
        "Let Circlet reach your strap",
        "Three settings keep your data flowing, including overnight while the app is closed.",
        footer = {
            PrimaryButton(if (access.nearby) "CONTINUE" else "ALLOW NEARBY DEVICES TO CONTINUE", enabled = access.nearby, onClick = onNext)
            if (!access.nearby) QuietButton("SET UP WITHOUT A STRAP", onClick = onSkip)
        },
    ) {
        Card { AccessRows(access) }
    }
}

@Composable
fun PairingPage(
    phase: PairingPhase,
    scanning: Boolean,
    found: List<FoundStrap>,
    onScan: () -> Unit,
    onPair: (FoundStrap) -> Unit,
    onFix: (PairingFix) -> Unit,
    onSkip: () -> Unit,
) {
    Page(
        "Pair your strap",
        "A strap only talks to one app at a time, so free it up first.",
        footer = { QuietButton("SKIP FOR NOW", onClick = onSkip) },
    ) {
        for ((n, line) in listOf(
            "1" to "In the official app, remove the strap from your account's devices.",
            "2" to "In Android Bluetooth settings, forget the strap if it is listed.",
            "3" to "Keep it on your wrist or within arm's reach, then pick it below.",
        )) {
            Row(Modifier.padding(bottom = 12.dp)) {
                Text(n, style = number(15.sp, Palette.Faint), modifier = Modifier.width(22.dp))
                Text(line, style = body(15.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Card { StrapFinder(phase, scanning, found, onScan, onPair, onFix) }
    }
}

@Composable
fun ReadyPage(strap: StrapState, onFinish: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.page),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Ring(1f, Palette.Teal, 168.dp, 10.dp) { Icon(Glyph.Check, Palette.Teal, 64.dp) }
        }
        Text("${strap.name ?: "Your strap"} is connected.", style = title(30.sp))
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                strap.sync.draining && strap.sync.samplesPulled > 0 ->
                    "${hoursMinutes((strap.sync.samplesPulled / 60).toInt())} of stored heart rate pulled so far. The rest arrives in the background, so you can head in now."
                strap.lastSync != null && !strap.sync.draining ->
                    "The first sync is finished. Everything the strap had stored is now on this phone."
                else -> "Circlet pulls the heart rate your strap has stored. That carries on in the background, so you can head in now."
            },
            style = body(16.sp),
        )
        Spacer(Modifier.height(16.dp))
        if (strap.sync.draining) Sweep(Palette.Teal)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("OPEN CIRCLET", onClick = onFinish)
        Spacer(Modifier.height(20.dp))
    }
}
