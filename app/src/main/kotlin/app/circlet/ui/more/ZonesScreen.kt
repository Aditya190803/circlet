package app.circlet.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.circlet.core.HeartRateZone
import app.circlet.core.MetricsRepository
import app.circlet.core.Outcome
import app.circlet.core.Profile
import app.circlet.core.ProfileRepository
import app.circlet.core.SettingsRepository
import app.circlet.core.TimeSource
import app.circlet.core.UserSettings
import app.circlet.core.ZoneDefaults
import app.circlet.core.problems
import app.circlet.ui.day.DetailHeader
import app.circlet.ui.day.ScreenColumn
import app.circlet.ui.design.Card
import app.circlet.ui.design.Divider
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.SmallButton
import app.circlet.ui.design.Space
import app.circlet.ui.design.Toggle
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.number
import app.circlet.ui.design.words
import app.circlet.ui.state.containerViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val HISTORY_DAYS = 60L
private const val LOWEST_BPM = 30

/** What the zones screen is built from: the default bounds, and the latest resting rate that bounds zone 1 from below. */
class ZoneBasis(val defaults: List<Int>?, val resting: Int?)

class ZonesViewModel(
    metrics: MetricsRepository,
    time: TimeSource,
    defaults: ZoneDefaults,
    private val profiles: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val profile: StateFlow<Profile> = profiles.profile
    val settings: StateFlow<UserSettings> = settingsRepository.settings
    val error = MutableStateFlow<String?>(null)

    private val today: LocalDate = time.now().atZone(time.zone()).toLocalDate()

    val basis: StateFlow<ZoneBasis?> = combine(
        profiles.profile,
        metrics.observeRange(today.minusDays(HISTORY_DAYS - 1), today).catch { emit(emptyList()) },
    ) { profile, days ->
        val resting = days.sortedBy { it.date }.mapNotNull { it.heart.resting.bpm.value?.toDouble() }
        ZoneBasis(defaults.bounds(profile, today, resting), resting.lastOrNull()?.toInt())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(settings: UserSettings) {
        viewModelScope.launch { settingsRepository.update(settings) }
    }

    fun save(profile: Profile, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = profiles.update(profile)) {
                is Outcome.Ok -> {
                    error.value = null
                    onSaved()
                }
                is Outcome.Err -> error.value = result.error.words()
            }
        }
    }
}

/** Zone lower bounds with the top of zone 5 last, from custom zones; null when the profile has none. */
private fun Profile.customBounds(): List<Int>? {
    if (customZones.isEmpty()) return null
    val ordered = customZones.sortedBy { it.index }
    return ordered.map { it.fromBpm } + ordered.last().untilBpm
}

private fun List<Int>.asZones(): List<HeartRateZone> =
    (0 until 5).map { HeartRateZone(it + 1, this[it], this[it + 1]) }

/**
 * [bounds] moved by [delta] at [index], held strictly between its neighbours.
 * The first bound stays above [floor] and the last one below the fixed top.
 */
internal fun List<Int>.moved(index: Int, delta: Int, floor: Int): List<Int> {
    val lowest = (if (index == 0) floor else this[index - 1]) + 1
    val highest = this[index + 1] - 1
    if (lowest > highest) return this
    return toMutableList().also { it[index] = (this[index] + delta).coerceIn(lowest, highest) }
}

@Composable
fun ZonesScreen(onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> ZonesViewModel(c.metrics, c.time, c.zoneDefaults, c.profile, c.settings) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val basis by vm.basis.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        DetailHeader("HEART RATE ZONES", onBack = onBack)
        ScreenColumn {
            ZonesEditor(profile, basis, error, vm::save)
            Card("ZONE CHANGES") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Buzz when I change zone during a workout",
                        style = body(15.sp, Palette.White),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.md))
                    Toggle(settings.zoneBuzz, "Buzz when I change zone during a workout") {
                        vm.update(settings.copy(zoneBuzz = it))
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    "A new zone has to hold for 10 seconds, and buzzes are at least 30 seconds apart. Nothing buzzes while the workout is paused.",
                    style = body(13.sp, Palette.Faint),
                )
            }
        }
    }
}

@Composable
private fun ZonesEditor(profile: Profile, basis: ZoneBasis?, error: String?, onSave: (Profile, () -> Unit) -> Unit) {
    val defaults = basis?.defaults
    var own by remember(profile.customZones) { mutableStateOf(profile.customZones.isNotEmpty()) }
    var saved by remember(profile.customZones) { mutableStateOf(false) }
    val edited = remember(profile.customZones, defaults) {
        mutableStateListOf<Int>().apply { addAll(profile.customBounds() ?: defaults.orEmpty()) }
    }
    val shown = if (own) edited.toList() else defaults.orEmpty()
    val floor = maxOf(basis?.resting ?: 0, LOWEST_BPM - 1)
    val candidate = profile.copy(customZones = if (own && edited.size == 6) edited.asZones() else emptyList())
    val problems = candidate.problems()

    Card("YOUR ZONES") {
        if (shown.size != 6) {
            Text(
                "Add your age or max heart rate in your profile and the zones appear here.",
                style = body(14.sp, Palette.Dim),
            )
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use my own zones", style = body(15.sp, Palette.White), modifier = Modifier.weight(1f))
            Toggle(own, "Use my own zones") {
                own = it
                saved = false
            }
        }
        shown.dropLast(1).forEachIndexed { index, bpm ->
            Divider()
            ZoneBoundRow(index + 1, bpm, shown[index + 1], editable = own) { delta ->
                edited.clear()
                edited.addAll(shown.moved(index, delta, floor))
                saved = false
            }
        }
        Divider()
        Text("MAX HEART RATE  ${shown.last()} bpm", style = label(12.sp, Palette.Dim), modifier = Modifier.padding(vertical = Space.md))
        if (own && problems.isNotEmpty()) {
            Text(problems.joinToString(". "), style = body(13.sp, Palette.Warn))
            Spacer(Modifier.height(Space.sm))
        }
        if (error != null) {
            Text(error, style = body(13.sp, Palette.Warn))
            Spacer(Modifier.height(Space.sm))
        }
        SecondaryButton("RESET TO DEFAULT", enabled = own || profile.customZones.isNotEmpty()) {
            own = false
            edited.clear()
            edited.addAll(defaults.orEmpty())
            saved = false
        }
        Spacer(Modifier.height(Space.sm))
        PrimaryButton(
            if (saved) "SAVED" else "SAVE ZONES",
            enabled = !saved && problems.isEmpty() && candidate.customZones != profile.customZones,
        ) {
            onSave(candidate) { saved = true }
        }
    }
}

@Composable
private fun ZoneBoundRow(zone: Int, bpm: Int, until: Int, editable: Boolean, onStep: (Int) -> Unit) {
    Column(Modifier.padding(vertical = Space.sm)) {
        Text("ZONE $zone  ·  $bpm to $until bpm", style = label(12.sp, Palette.Dim))
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            SmallButton("−5", enabled = editable) { onStep(-5) }
            Spacer(Modifier.width(Space.sm))
            SmallButton("−1", enabled = editable) { onStep(-1) }
            Text(
                "$bpm",
                style = number(28.sp, if (editable) Palette.White else Palette.Dim),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            SmallButton("+1", enabled = editable) { onStep(1) }
            Spacer(Modifier.width(Space.sm))
            SmallButton("+5", enabled = editable) { onStep(5) }
        }
    }
}
