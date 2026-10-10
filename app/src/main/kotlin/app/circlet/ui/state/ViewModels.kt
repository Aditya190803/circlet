package app.circlet.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.circlet.AppContainer
import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.Haptic
import app.circlet.core.OnboardingChecks
import app.circlet.core.OnboardingState
import app.circlet.core.Outcome
import app.circlet.core.Profile
import app.circlet.core.ProfileRepository
import app.circlet.core.SettingsRepository
import app.circlet.core.StrapController
import app.circlet.core.StrapState
import app.circlet.core.UserSettings
import app.circlet.ui.design.words
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A view model built from the [AppContainer], scoped to the nearest owner. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer, SavedStateHandle) -> VM): VM {
    val container = AppContainer.get(LocalContext.current)
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}

/** Where a pairing attempt is. [error] stays until the next scan or attempt. */
data class PairingPhase(
    val connecting: FoundStrap? = null,
    val error: CoreError? = null,
)

/** Scan and pair. Used by onboarding and by the pairing screen under More. */
class PairingViewModel(private val strap: StrapController) : ViewModel() {
    val strapState: StateFlow<StrapState> = strap.state
    val scanning: StateFlow<Boolean> = strap.scanning
    val found: StateFlow<List<FoundStrap>> = strap.scanResults
    val phase = MutableStateFlow(PairingPhase())

    fun scan() {
        viewModelScope.launch {
            phase.value = PairingPhase()
            val result = strap.startScan()
            if (result is Outcome.Err) phase.value = PairingPhase(error = result.error)
        }
    }

    fun stopScan() {
        viewModelScope.launch { strap.stopScan() }
    }

    fun pair(target: FoundStrap) {
        if (phase.value.connecting != null) return
        phase.value = PairingPhase(connecting = target)
        viewModelScope.launch {
            strap.stopScan()
            phase.value = when (val result = strap.pair(target)) {
                is Outcome.Ok -> PairingPhase()
                is Outcome.Err -> PairingPhase(error = result.error)
            }
        }
    }
}

/** The profile step, and the flag that ends onboarding. */
class OnboardingViewModel(
    private val onboarding: OnboardingState,
    private val profiles: ProfileRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val profile: StateFlow<Profile> = profiles.profile
    val settings: StateFlow<UserSettings> = settings.settings
    val profileError = MutableStateFlow<String?>(null)

    fun saveProfile(profile: Profile, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = profiles.update(profile)) {
                is Outcome.Ok -> {
                    profileError.value = null
                    onSaved()
                }
                is Outcome.Err -> profileError.value = result.error.words()
            }
        }
    }

    fun finish() {
        viewModelScope.launch { onboarding.setCompleted(true) }
    }
}

/** Strap actions, profile and preferences behind the More tab and the screens pushed from it. */
class MoreViewModel(
    private val strap: StrapController,
    private val profiles: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    onboarding: OnboardingState,
) : ViewModel() {
    val strapState: StateFlow<StrapState> = strap.state
    val profile: StateFlow<Profile> = profiles.profile
    val settings: StateFlow<UserSettings> = settingsRepository.settings
    val checks: StateFlow<OnboardingChecks> = onboarding.checks

    /** The action that is running, so its button can show it and the others can wait. */
    val busy = MutableStateFlow<StrapAction?>(null)
    val profileError = MutableStateFlow<String?>(null)

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun sync() = run(StrapAction.SYNC, "Synced.") { strap.syncNow() }

    fun buzz() = run(StrapAction.BUZZ, "Buzz sent.") { strap.buzz(Haptic.TEST) }

    fun find() = run(StrapAction.FIND, "Your strap is buzzing.") { strap.buzz(Haptic.FIND) }

    fun rename(name: String) = run(StrapAction.RENAME, "Strap renamed.") { strap.rename(name) }

    fun forget() = run(StrapAction.FORGET, "Strap forgotten. Its history stays on this phone.") { strap.forget() }

    fun setBroadcast(enabled: Boolean) = run(StrapAction.BROADCAST, null) {
        when (val result = strap.setBroadcast(enabled)) {
            is Outcome.Ok -> settingsRepository.update(settings.value.copy(heartRateBroadcast = enabled))
            is Outcome.Err -> result
        }
    }

    fun update(settings: UserSettings) {
        viewModelScope.launch {
            val result = settingsRepository.update(settings)
            if (result is Outcome.Err) messageChannel.send(result.error.words())
        }
    }

    fun saveProfile(profile: Profile, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = profiles.update(profile)) {
                is Outcome.Ok -> {
                    profileError.value = null
                    onSaved()
                }
                is Outcome.Err -> profileError.value = result.error.words()
            }
        }
    }

    private fun run(action: StrapAction, done: String?, call: suspend () -> Outcome<*>) {
        if (busy.value != null) return
        busy.value = action
        viewModelScope.launch {
            when (val result = call()) {
                is Outcome.Ok -> done?.let { messageChannel.send(it) }
                is Outcome.Err -> messageChannel.send(result.error.words())
            }
            busy.value = null
        }
    }
}

enum class StrapAction { SYNC, BUZZ, FIND, RENAME, FORGET, BROADCAST }
